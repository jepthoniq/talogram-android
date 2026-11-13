/*
 * Talogram mod - private call recording.
 * Tap the record button during a call to record both sides (mic + remote) into a local
 * temporary track. When the call ends (or you stop), the two tracks are merged and
 * encoded into M4A/AAC which is automatically sent to Saved Messages.
 */

package org.telegram.messenger.voip;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.text.TextUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.UserConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public final class TalogramCallRecorder {
    public interface Listener {
        void onRecordingStateChanged(boolean recording);

        void onRecordingStarted(String peerName);
    }

    private static final TalogramCallRecorder INSTANCE = new TalogramCallRecorder();
    private static final String TAG = "TalogramCallRecorder";

    private volatile boolean recording;
    private volatile long recordingStartedAt;
    private volatile int durationSeconds;
    private final ByteArrayOutputStream micStream = new ByteArrayOutputStream();
    private final ByteArrayOutputStream playoutStream = new ByteArrayOutputStream();
    private final Object streamLock = new Object();
    private String peerName = "call";
    private int currentAccount;
    private Listener listener;
    private final Runnable durationTask = new Runnable() {
        @Override
        public void run() {
            if (!recording) {
                return;
            }
            durationSeconds = (int) ((System.currentTimeMillis() - recordingStartedAt) / 1000);
            final Listener l = listener;
            if (l != null) {
                AndroidUtilities.runOnUIThread(() -> l.onRecordingStateChanged(true));
            }
            Utilities.globalQueue.postRunnable(this, 1000);
        }
    };

    private TalogramCallRecorder() {
    }

    public static TalogramCallRecorder getInstance() {
        return INSTANCE;
    }

    public static boolean isRecordingActive() {
        return INSTANCE.recording;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public void start(int currentAccount, String peerName) {
        if (recording) {
            return;
        }
        this.currentAccount = currentAccount;
        this.peerName = TextUtils.isEmpty(peerName) ? "call" : peerName;
        synchronized (streamLock) {
            micStream.reset();
            playoutStream.reset();
        }
        durationSeconds = 0;
        recordingStartedAt = System.currentTimeMillis();
        recording = true;
        listenersNotifyStarted(this.peerName);
        Utilities.globalQueue.postRunnable(durationTask, 1000);
    }

    public void stopAndSend(final Context context) {
        if (!recording) {
            return;
        }
        recording = false;
        final byte[] mic;
        final byte[] playout;
        final String name = peerName;
        synchronized (streamLock) {
            mic = micStream.toByteArray();
            playout = playoutStream.toByteArray();
            micStream.reset();
            playoutStream.reset();
        }
        listenersNotifyStopped();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final byte[] merged = mergeTracks(mic, playout);
                final String fileName = formatRecordingFileName(name);
                File outFile = encodeToM4A(merged, fileName);
                if (outFile == null) {
                    outFile = encodeToWav(merged, stripExtension(fileName) + ".wav");
                }
                if (outFile != null && outFile.exists() && outFile.length() > 0) {
                    sendRecording(currentAccount, outFile.getAbsolutePath(), outFile.getName());
                } else {
                    showToastOnUiThread(context, R.string.JokerCallRecordingFailed);
                }
            } catch (Exception e) {
                FileLog.e(e);
                showToastOnUiThread(context, R.string.JokerCallRecordingFailed);
            }
        });
    }

    // Called when the call ends; iOS also stops and sends on call end.
    public void stopIfRecording() {
        stopAndSend(null);
    }

    // Called from the audio record thread, receives the microphone bytes.
    public void onCapture(byte[] data, int offset, int length) {
        if (!recording || data == null || length <= 0) {
            return;
        }
        synchronized (streamLock) {
            try {
                micStream.write(data, offset, length);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    // Called from the audio playback thread, receives the remote side bytes.
    public void onPlayout(byte[] data, int offset, int length) {
        if (!recording || data == null || length <= 0) {
            return;
        }
        synchronized (streamLock) {
            try {
                playoutStream.write(data, offset, length);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private void listenersNotifyStarted(String name) {
        final Listener l = listener;
        if (l == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            l.onRecordingStarted(name);
            l.onRecordingStateChanged(true);
        });
    }

    private void listenersNotifyStopped() {
        final Listener l = listener;
        if (l == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> l.onRecordingStateChanged(false));
    }

    private void showToastOnUiThread(final Context context, final int stringId) {
        AndroidUtilities.runOnUIThread(() -> {
            Context ctx = context != null ? context : org.telegram.messenger.ApplicationLoader.applicationContext;
            if (ctx != null) {
                android.widget.Toast.makeText(ctx, LocaleController.getString(stringId), android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }

    private String formatRecordingFileName(String name) {
        String safeName = name == null ? "call" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safeName.length() == 0) {
            safeName = "call";
        }
        String datePart = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(new Date(System.currentTimeMillis()));
        String prefix = "Recording ";
        try {
            Locale locale = LocaleController.getInstance().getCurrentLocale();
            if (locale != null && locale.getLanguage().startsWith("ar")) {
                prefix = "تسجيل ";
            }
        } catch (Exception e) {
            // fall back to English
        }
        return prefix + safeName + " " + datePart + ".m4a";
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private byte[] mergeTracks(byte[] mic, byte[] playout) {
        int micSamples = mic.length / 2;
        int playoutSamples = playout.length / 2;
        int sampleCount = Math.max(micSamples, playoutSamples);
        byte[] merged = new byte[sampleCount * 2];
        for (int i = 0; i < sampleCount; i++) {
            int a = i < micSamples ? readShortLe(mic, i * 2) : 0;
            int b = i < playoutSamples ? readShortLe(playout, i * 2) : 0;
            int s = a + b;
            if (s > Short.MAX_VALUE) {
                s = Short.MAX_VALUE;
            } else if (s < Short.MIN_VALUE) {
                s = Short.MIN_VALUE;
            }
            merged[i * 2] = (byte) (s & 0xff);
            merged[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        return merged;
    }

    private int readShortLe(byte[] bytes, int index) {
        if (index + 1 >= bytes.length) {
            return 0;
        }
        return (short) ((bytes[index] & 0xff) | (bytes[index + 1] << 8));
    }

    private File outputDir() {
        File dir = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), "talogram-call-recordings");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private File encodeToM4A(byte[] pcm, String fileName) {
        final int SAMPLE_RATE = 48000;
        final int CHANNELS = 1;
        MediaCodec codec = null;
        MediaMuxer muxer = null;
        File outFile = new File(outputDir(), fileName);
        try {
            if (outFile.exists()) {
                outFile.delete();
            }
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            ByteBuffer[] outputBuffers = codec.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int inputPosition = 0;
            long presentationTimeUs = 0;
            boolean inputDone = false;
            boolean outputDone = false;
            int trackIndex = -1;
            boolean muxerStarted = false;
            int bytesPerSecond = SAMPLE_RATE * CHANNELS * 2;

            while (!outputDone) {
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer buf = inputBuffers[inputIndex];
                        buf.clear();
                        int maxChunk = Math.min(buf.capacity(), pcm.length - inputPosition);
                        int chunk = Math.min(maxChunk, 4096);
                        if (chunk <= 0) {
                            inputDone = true;
                            codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            buf.put(pcm, inputPosition, chunk);
                            codec.queueInputBuffer(inputIndex, 0, chunk, presentationTimeUs, 0);
                            inputPosition += chunk;
                            presentationTimeUs += (long) chunk * 1000000L / bytesPerSecond;
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outputIndex >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size > 0) {
                        ByteBuffer buf = outputBuffers[outputIndex];
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.getOutputFormat());
                            muxer.start();
                            muxerStarted = true;
                        }
                        if (trackIndex >= 0) {
                            muxer.writeSampleData(trackIndex, buf, info);
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = codec.getOutputFormat();
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(newFormat);
                        muxer.start();
                        muxerStarted = true;
                    }
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }

            if (muxerStarted) {
                muxer.stop();
            }
            if (outFile.length() > 0) {
                return outFile;
            }
            return null;
        } catch (Exception e) {
            Log_RecorderError(e);
            return null;
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignored) {
            }
            try {
                if (muxer != null) {
                    muxer.release();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void Log_RecorderError(Exception e) {
        org.telegram.messenger.FileLog.e(e);
    }

    private File encodeToWav(byte[] pcm, String fileName) {
        try {
            File outFile = new File(outputDir(), fileName);
            if (outFile.exists()) {
                outFile.delete();
            }
            int sampleRate = 48000;
            int channels = 1;
            int bitsPerSample = 16;
            int byteRate = sampleRate * channels * bitsPerSample / 8;
            int blockAlign = channels * bitsPerSample / 8;
            int dataSize = pcm.length;

            FileOutputStream out = new FileOutputStream(outFile);
            try {
                out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
                writeIntLE(out, 36 + dataSize);
                out.write("WAVE".getBytes(StandardCharsets.US_ASCII));
                out.write("fmt ".getBytes(StandardCharsets.US_ASCII));
                writeIntLE(out, 16);
                writeShortLE(out, (short) 1);
                writeShortLE(out, (short) channels);
                writeIntLE(out, sampleRate);
                writeIntLE(out, byteRate);
                writeShortLE(out, (short) blockAlign);
                writeShortLE(out, (short) bitsPerSample);
                out.write("data".getBytes(StandardCharsets.US_ASCII));
                writeIntLE(out, dataSize);
                out.write(pcm);
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
            return outFile;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private void writeIntLE(FileOutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private void writeShortLE(FileOutputStream out, short value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private void sendRecording(int currentAccount, String filePath, String fileName) {
        try {
            AccountInstance accountInstance = AccountInstance.getInstance(currentAccount);
            long dialogId = UserConfig.getInstance(currentAccount).getClientUserId();
            ArrayList<String> paths = new ArrayList<>();
            paths.add(filePath);
            ArrayList<String> originalPaths = new ArrayList<>();
            originalPaths.add(filePath);
            SendMessagesHelper.prepareSendingDocuments(
                    accountInstance,
                    paths,
                    originalPaths,
                    null,
                    "",
                    null,
                    "audio/x-m4a",
                    dialogId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true,
                    0,
                    0,
                    null,
                    null,
                    0,
                    false,
                    0,
                    0,
                    null
            );
            AndroidUtilities.runOnUIThread(() -> showToastOnUiThread(null, R.string.JokerCallRecordingSaved));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
