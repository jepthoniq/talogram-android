/*
 * Talogram mod - plays a local video file as the outgoing group-call video.
 *
 * The file is decoded with MediaCodec and every frame is pushed to the WebRTC
 * capturer observer as I420. This is used by VideoCapturerDevice when the user
 * starts a voice-chat broadcast from a video message, so other participants see
 * the video while the audio is mixed into the microphone stream by
 * TalogramCallBroadcast.
 */

package org.telegram.messenger.voip;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.telegram.messenger.FileLog;
import org.webrtc.CapturerObserver;
import org.webrtc.JavaI420Buffer;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;

import java.nio.ByteBuffer;

public class TalogramFileVideoCapturer implements VideoCapturer {

    private static final String TAG = "TalogramFileVideo";
    private static final int MIN_FRAME_INTERVAL_NS = 33_000_000;
    private static final int COLOR_FormatYUV420Planar = 19;
    private static final int COLOR_FormatYUV420SemiPlanar = 21;
    private static final int COLOR_FormatYUV420Flexible = 0x7F420888;

    private final String path;
    private volatile boolean running;
    private Thread decodeThread;
    private CapturerObserver capturerObserver;

    public TalogramFileVideoCapturer(String path) {
        this.path = path;
    }

    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper, android.content.Context applicationContext, CapturerObserver capturerObserver) {
        this.capturerObserver = capturerObserver;
    }

    @Override
    public synchronized void startCapture(int width, int height, int framerate) {
        if (running) {
            return;
        }
        running = true;
        decodeThread = new Thread(this::decodeLoop, "TalogramFileVideo");
        decodeThread.start();
    }

    @Override
    public synchronized void stopCapture() {
        running = false;
        Thread thread = decodeThread;
        decodeThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(2000);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
    }

    @Override
    public synchronized void dispose() {
        stopCapture();
        capturerObserver = null;
    }

    @Override
    public boolean isScreencast() {
        return false;
    }

    private void decodeLoop() {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        CapturerObserver observer = capturerObserver;
        try {
            if (observer == null) {
                return;
            }
            extractor = new MediaExtractor();
            extractor.setDataSource(path);
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    trackIndex = i;
                    format = candidate;
                    break;
                }
            }
            if (trackIndex < 0 || format == null) {
                throw new IllegalStateException("no video track");
            }
            int rotation = 0;
            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                rotation = format.getInteger(MediaFormat.KEY_ROTATION);
            }
            int outputWidth = format.containsKey(MediaFormat.KEY_WIDTH) ? format.getInteger(MediaFormat.KEY_WIDTH) : 0;
            int outputHeight = format.containsKey(MediaFormat.KEY_HEIGHT) ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0;
            String mime = format.getString(MediaFormat.KEY_MIME);
            extractor.selectTrack(trackIndex);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int colorFormat = COLOR_FormatYUV420Flexible;
            long firstPtsUs = -1;
            long playbackStartNs = 0;
            long lastSentPtsUs = -1;
            long nextTargetNs = 0;
            observer.onCapturerStarted(true);

            while (running && !outputDone) {
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        int size = extractor.readSampleData(inputBuffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    if (outputFormat.containsKey(MediaFormat.KEY_COLOR_FORMAT)) {
                        colorFormat = outputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                        outputWidth = outputFormat.getInteger(MediaFormat.KEY_WIDTH);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                        outputHeight = outputFormat.getInteger(MediaFormat.KEY_HEIGHT);
                    }
                } else if (outputIndex >= 0) {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                        long ptsUs = info.presentationTimeUs;
                        boolean send = lastSentPtsUs < 0 || ptsUs - lastSentPtsUs >= 33000;
                        if (send) {
                            if (firstPtsUs < 0) {
                                firstPtsUs = ptsUs;
                                playbackStartNs = System.nanoTime();
                            }
                            long targetNs = playbackStartNs + (ptsUs - firstPtsUs) * 1000L;
                            if (targetNs <= nextTargetNs) {
                                targetNs = nextTargetNs + MIN_FRAME_INTERVAL_NS;
                            }
                            nextTargetNs = targetNs;
                            sleepUntil(targetNs);
                            if (!running) {
                                codec.releaseOutputBuffer(outputIndex, false);
                                break;
                            }
                            Image image = null;
                            try {
                                image = codec.getOutputImage(outputIndex);
                            } catch (Throwable ignore) {
                            }
                            if (image != null) {
                                pushImage(image, rotation);
                                image.close();
                            } else {
                                ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                                if (outputBuffer != null) {
                                    outputBuffer.position(info.offset);
                                    outputBuffer.limit(info.offset + info.size);
                                    byte[] raw = new byte[info.size];
                                    outputBuffer.get(raw);
                                    pushRaw(raw, colorFormat, rotation, outputWidth, outputHeight);
                                }
                            }
                            lastSentPtsUs = ptsUs;
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": video broadcast failed", e);
        } finally {
            running = false;
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignore) {
                }
                try {
                    codec.release();
                } catch (Throwable ignore) {
                }
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable ignore) {
                }
            }
            try {
                observer.onCapturerStopped();
            } catch (Throwable ignore) {
            }
        }
    }

    private void sleepUntil(long targetNs) {
        while (running) {
            long delta = targetNs - System.nanoTime();
            if (delta <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.max(1, Math.min(20, delta / 1000000L)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pushImage(Image image, int rotation) {
        int width = image.getWidth() & ~1;
        int height = image.getHeight() & ~1;
        if (width <= 0 || height <= 0) {
            return;
        }
        JavaI420Buffer buffer = JavaI420Buffer.allocate(width, height);
        Image.Plane[] planes = image.getPlanes();
        copyPlane(planes[0], buffer.getDataY(), buffer.getStrideY(), width, height);
        copyPlane(planes[1], buffer.getDataU(), buffer.getStrideU(), width / 2, height / 2);
        copyPlane(planes[2], buffer.getDataV(), buffer.getStrideV(), width / 2, height / 2);
        pushBuffer(buffer, rotation);
    }

    private void pushRaw(byte[] raw, int colorFormat, int rotation, int width, int height) {
        width &= ~1;
        height &= ~1;
        if (width <= 0 || height <= 0) {
            return;
        }
        int ySize = width * height;
        int chromaWidth = width / 2;
        int chromaSize = chromaWidth * (height / 2);
        if (raw.length < ySize + chromaSize * 2) {
            return;
        }
        JavaI420Buffer buffer = JavaI420Buffer.allocate(width, height);
        copyY(raw, 0, width, buffer.getDataY(), buffer.getStrideY(), width, height);
        if (colorFormat == COLOR_FormatYUV420SemiPlanar) {
            // NV12: interleaved UV after the Y plane. NV21 has V first; Android
            // decoders normally output NV12 with the COLOR_FormatYUV420SemiPlanar.
            int uvOffset = ySize;
            ByteBuffer u = buffer.getDataU();
            ByteBuffer v = buffer.getDataV();
            for (int row = 0; row < height / 2; row++) {
                int srcRow = uvOffset + row * width;
                int dstRow = row * buffer.getStrideU();
                for (int col = 0; col < chromaWidth; col++) {
                    u.put(dstRow + col, raw[srcRow + col * 2]);
                    v.put(dstRow + col, raw[srcRow + col * 2 + 1]);
                }
            }
        } else {
            // I420 planar: full Y plane, then U, then V.
            copyY(raw, ySize, chromaWidth, buffer.getDataU(), buffer.getStrideU(), chromaWidth, height / 2);
            copyY(raw, ySize + chromaSize, chromaWidth, buffer.getDataV(), buffer.getStrideV(), chromaWidth, height / 2);
        }
        pushBuffer(buffer, rotation);
    }

    private static void copyY(byte[] src, int srcOffset, int srcStride, ByteBuffer dst, int dstStride, int width, int height) {
        for (int row = 0; row < height; row++) {
            int srcRow = srcOffset + row * srcStride;
            int dstRow = row * dstStride;
            for (int col = 0; col < width; col++) {
                dst.put(dstRow + col, src[srcRow + col]);
            }
        }
    }

    private void pushBuffer(JavaI420Buffer buffer, int rotation) {
        CapturerObserver observer = capturerObserver;
        if (observer == null) {
            buffer.release();
            return;
        }
        VideoFrame frame = new VideoFrame(buffer, rotation % 360, System.nanoTime());
        try {
            observer.onFrameCaptured(frame);
        } finally {
            frame.release();
        }
    }

    private static void copyPlane(Image.Plane plane, ByteBuffer dst, int dstStride, int width, int height) {
        ByteBuffer src = plane.getBuffer();
        int srcStride = plane.getRowStride();
        int srcPixelStride = plane.getPixelStride();
        for (int row = 0; row < height; row++) {
            int srcRow = row * srcStride;
            int dstRow = row * dstStride;
            for (int col = 0; col < width; col++) {
                dst.put(dstRow + col, src.get(srcRow + col * srcPixelStride));
            }
        }
    }
}
