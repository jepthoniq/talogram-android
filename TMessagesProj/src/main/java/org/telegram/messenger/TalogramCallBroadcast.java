/*
 * Talogram mod - broadcast a message's audio (or the audio track of a video)
 * into the voice chat of the same group/channel.
 *
 * The file is decoded once into memory. While broadcasting is active, every
 * call-frame tick mixes the broadcast audio into the outgoing microphone
 * stream (mic + file), so the audience hears the file while the broadcaster
 * can still talk. Works for both private and group voice calls, since both
 * go through the same WebRTC audio device module.
 */

package org.telegram.messenger;

import org.telegram.messenger.FileLog;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.text.TextUtils;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public final class TalogramCallBroadcast {

    public interface JoinVoiceChatCallback {
        void onJoin();
    }

    public enum State {
        IDLE,
        DECODING,
        BROADCASTING
    }

    private static final TalogramCallBroadcast INSTANCE = new TalogramCallBroadcast();

    private static final int SAMPLE_RATE = 48000;
    private static final int BYTES_PER_SAMPLE = 2;

    private volatile State state = State.IDLE;
    private volatile long playingMessageId = 0;

    // decoded PCM of the file, 16-bit signed mono at SAMPLE_RATE
    private byte[] pcm;
    private int pcmOffset;

    // Audio that has already been mixed into the outgoing mic; kept here so the
    // playout (speaker) path can mirror it with no extra decode.
    private final java.util.ArrayDeque<byte[]> playoutQueue = new java.util.ArrayDeque<>(64);

    private volatile String filePath;

    private TalogramCallBroadcast() {
    }

    public static TalogramCallBroadcast getInstance() {
        return INSTANCE;
    }

    public static boolean isBroadcasting() {
        return INSTANCE.state == State.BROADCASTING;
    }

    public static boolean isDecoding() {
        return INSTANCE.state == State.DECODING;
    }

    public static boolean isBusy() {
        return INSTANCE.state != State.IDLE;
    }

    // Returns the message id that is currently being broadcast, or 0.
    public static long getPlayingMessageId() {
        return INSTANCE.playingMessageId;
    }

    public static String getFilePath() {
        return INSTANCE.filePath;
    }

    public void stop() {
        state = State.IDLE;
        playingMessageId = 0;
        filePath = null;
        pcm = null;
        pcmOffset = 0;
    }

    // Called from the user's menu action. The joinVoiceChat callback is used to open the
    // voice-chat UI (and create it when missing). It returns true when the call UI was opened.
    public void toggle(Context context, int currentAccount, MessageObject message, JoinVoiceChatCallback joinVoiceChat) {
        if (message == null || message.messageOwner == null) {
            return;
        }
        if (state != State.IDLE) {
            if (message.getId() == playingMessageId) {
                stop();
            }
            return;
        }
        String path = findLocalPath(currentAccount, message);
        if (TextUtils.isEmpty(path)) {
            showToast(context, R.string.JokerCallBroadcastDownloadFirst);
            return;
        }
        long messageId = message.getId();
        this.playingMessageId = messageId;
        this.filePath = path;
        state = State.DECODING;
        Thread thread = new Thread(() -> {
            try {
                byte[] decoded = decodeToPcmBytes(path);
                if (decoded == null) {
                    AndroidUtilities.runOnUIThread(() -> {
                        showToast(context, R.string.JokerCallBroadcastFailed);
                        stop();
                    });
                    return;
                }
                synchronized (TalogramCallBroadcast.this) {
                    pcm = decoded;
                    pcmOffset = 0;
                }
                state = State.BROADCASTING;
                if (joinVoiceChat != null) {
                    joinVoiceChat.onJoin();
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (context != null) {
                        android.widget.Toast.makeText(context, LocaleController.getString(R.string.JokerCallBroadcastStarted), android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    showToast(context, R.string.JokerCallBroadcastFailed);
                    stop();
                });
            }
        }, "talogram-broadcast-decoder");
        thread.start();
    }

    // Mixes the broadcast PCM into the provided buffer (mic) in-place.
    // Returns true if some broadcast bytes were consumed.
    public static boolean mixInto(byte[] buffer, int offset, int length) {
        if (!isBroadcasting() || buffer == null || length <= 0) {
            return false;
        }
        byte[] chunk;
        synchronized (INSTANCE) {
            byte[] data = INSTANCE.pcm;
            if (data == null) {
                return false;
            }
            int available = data.length - INSTANCE.pcmOffset;
            if (available <= 0) {
                INSTANCE.stop();
                return false;
            }
            int count = Math.min(available, length);
            chunk = new byte[count];
            System.arraycopy(data, INSTANCE.pcmOffset, chunk, 0, count);
            INSTANCE.pcmOffset += count;
            if (INSTANCE.playoutQueue.size() < 256) {
                INSTANCE.playoutQueue.offer(chunk.clone());
            }
        }
        applyChunk(buffer, offset, length, chunk);
        return true;
    }

    // Drains the local-speaker queue with the same mix as the mic path.
    public static boolean mixIntoPlayout(byte[] buffer, int offset, int length) {
        if (!isBroadcasting() || buffer == null || length <= 0) {
            return false;
        }
        byte[] chunk;
        synchronized (INSTANCE) {
            chunk = INSTANCE.playoutQueue.poll();
        }
        if (chunk == null) {
            return false;
        }
        applyChunk(buffer, offset, length, chunk);
        return true;
    }

    private static void applyChunk(byte[] buffer, int offset, int length, byte[] chunk) {
        int samples = length / BYTES_PER_SAMPLE;
        for (int i = 0; i < samples; i++) {
            int micLow = buffer[offset + i * 2] & 0xff;
            int micHigh = buffer[offset + i * 2 + 1];
            int mic = (micLow | (micHigh << 8));
            if (mic >= 0x8000) {
                mic -= 0x10000;
            }
            int broadcast = 0;
            if (i * 2 + 1 < chunk.length) {
                broadcast = (chunk[i * 2] & 0xff) | (chunk[i * 2 + 1] << 8);
                if (broadcast >= 0x8000) {
                    broadcast -= 0x10000;
                }
            }
            int mixed = mic + broadcast;
            if (mixed > 32767) {
                mixed = 32767;
            } else if (mixed < -32768) {
                mixed = -32768;
            }
            buffer[offset + i * 2] = (byte) (mixed & 0xff);
            buffer[offset + i * 2 + 1] = (byte) ((mixed >> 8) & 0xff);
        }
    }

    // Resolves the local file path for a downloadable message media.
    private String findLocalPath(int currentAccount, MessageObject message) {
        String attachPath = message.messageOwner.attachPath;
        if (!TextUtils.isEmpty(attachPath)) {
            File f = new File(attachPath);
            if (f.exists() && f.length() > 0) {
                return attachPath;
            }
        }
        FileLoader loader = FileLoader.getInstance(currentAccount);
        File f = loader.getPathToMessage(message.messageOwner);
        if (f != null && f.exists() && f.length() > 0) {
            return f.getAbsolutePath();
        }
        return null;
    }

    // Decodes media audio into 48kHz mono 16-bit PCM.
    private static byte[] decodeToPcmBytes(String path) {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(path);
            int trackCount = extractor.getTrackCount();
            int audioTrack = -1;
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    break;
                }
            }
            if (audioTrack == -1) {
                return null;
            }
            extractor.selectTrack(audioTrack);
            MediaFormat format = extractor.getTrackFormat(audioTrack);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int inputSampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int inputChannels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            ByteBuffer[] outputBuffers = codec.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            byte[] encodedBuffer = null;
            ByteArrayBuffer decoded = new ByteArrayBuffer();
            while (!outputDone) {
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(5000);
                    if (inputIndex >= 0) {
                        ByteBuffer buffer = inputBuffers[inputIndex];
                        buffer.clear();
                        int sampleSize = extractor.readSampleData(buffer, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long sampleTime = extractor.getSampleTime();
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0);
                            extractor.advance();
                        }
                    }
                }
                int outputIndex = codec.dequeueOutputBuffer(info, 5000);
                if (outputIndex >= 0) {
                    ByteBuffer buffer = outputBuffers[outputIndex];
                    if (buffer != null && info.size > 0) {
                        byte[] chunk = new byte[info.size];
                        buffer.position(info.offset);
                        buffer.get(chunk);
                        decoded.append(chunk, 0, chunk.length);
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = codec.getOutputFormat();
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        inputSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        inputChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // wait for more input/output work
                }
            }
            extractor.unselectTrack(audioTrack);

            short[] rawPcm = new short[decoded.size() / 2];
            byte[] decodedBytes = decoded.toByteArray();
            for (int i = 0; i < rawPcm.length; i++) {
                int low = decodedBytes[i * 2] & 0xff;
                int high = decodedBytes[i * 2 + 1];
                rawPcm[i] = (short) (low | (high << 8));
            }
            if (inputChannels >= 2) {
                short[] mono = new short[rawPcm.length / inputChannels];
                int outIndex = 0;
                for (int i = 0; i + inputChannels - 1 < rawPcm.length; i += inputChannels) {
                    int sum = 0;
                    for (int c = 0; c < inputChannels; c++) {
                        sum += rawPcm[i + c];
                    }
                    mono[outIndex++] = (short) (sum / inputChannels);
                }
                rawPcm = mono;
            }
            if (inputSampleRate != SAMPLE_RATE) {
                rawPcm = resample(rawPcm, inputSampleRate, SAMPLE_RATE);
            }
            byte[] result = new byte[rawPcm.length * 2];
            for (int i = 0; i < rawPcm.length; i++) {
                result[i * 2] = (byte) (rawPcm[i] & 0xff);
                result[i * 2 + 1] = (byte) ((rawPcm[i] >> 8) & 0xff);
            }
            return result;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) {}
            try { if (extractor != null) { extractor.release(); } } catch (Exception ignored) {}
        }
    }

    private static short[] resample(short[] input, int fromRate, int toRate) {
        if (fromRate == toRate) {
            return input;
        }
        double ratio = (double) fromRate / (double) toRate;
        int outputLength = (int) (input.length / ratio);
        if (outputLength <= 0) {
            outputLength = 1;
        }
        short[] output = new short[outputLength];
        for (int i = 0; i < output.length; i++) {
            double srcPos = (double) i * ratio;
            int index = (int) srcPos;
            int next = Math.min(index + 1, input.length - 1);
            double fraction = srcPos - index;
            double value = input[index] + (input[next] - input[index]) * fraction;
            if (value > Short.MAX_VALUE) {
                value = Short.MAX_VALUE;
            } else if (value < Short.MIN_VALUE) {
                value = Short.MIN_VALUE;
            }
            output[i] = (short) value;
        }
        return output;
    }

    private static void showToast(Context context, int stringId) {
        if (context == null) {
            return;
        }
        android.widget.Toast.makeText(context, LocaleController.getString(stringId), android.widget.Toast.LENGTH_LONG).show();
    }

    // A trivial growable byte accumulator (avoids pulling in a whole ByteArrayOutputStream clone)
    private static final class ByteArrayBuffer {
        private byte[] buffer = new byte[8192];
        private int size;

        void append(byte[] data, int offset, int length) {
            ensure(size + length);
            System.arraycopy(data, offset, buffer, size, length);
            size += length;
        }

        int size() {
            return size;
        }

        byte[] toByteArray() {
            byte[] result = new byte[size];
            System.arraycopy(buffer, 0, result, 0, size);
            return result;
        }

        private void ensure(int required) {
            while (buffer.length < required) {
                byte[] bigger = new byte[buffer.length * 2];
                System.arraycopy(buffer, 0, bigger, 0, size);
                buffer = bigger;
            }
        }
    }
}
