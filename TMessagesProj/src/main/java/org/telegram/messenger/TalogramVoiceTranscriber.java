/*
 * Talogram mod - on-device voice-to-text transcription.
 *
 * Uses the small Arabic Vosk model (Apache 2.0) so transcription works without
 * Telegram Premium and without sending the voice message to the server. The
 * model is downloaded once (~100 MB) and stored in the app's private files
 * directory. Audio is decoded with MediaCodec, converted to 16 kHz mono PCM and
 * fed to Vosk. The caller can fall back to the server if anything fails.
 */

package org.telegram.messenger;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class TalogramVoiceTranscriber {

    public interface Callback {
        void onSuccess(String text);

        void onFailure();
    }

    private static final String TAG = "TalogramVoiceTranscriber";
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ar-0.3.zip";
    private static final String MODEL_DIR = "vosk-model-small-ar-0.3";
    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int FEED_CHUNK_BYTES = 6400;
    private static final String NOTIFICATION_CHANNEL_ID = "talogram_voice_model";
    private static final int NOTIFICATION_ID = 0x7a01;

    private static final Object MODEL_LOCK = new Object();
    private static volatile Model model;
    private static volatile boolean downloading;
    // True from the moment a background preparation starts until its thread
    // finishes; the UI uses it to tell "still fetching the model" apart from
    // "preparation failed".
    private static volatile boolean preparing;
    private static volatile int lastToastPercent = -100;
    private static Toast progressToast;

    private TalogramVoiceTranscriber() {
    }

    // The bundled model only understands Arabic. Other locales keep using the
    // server path so we never turn English speech into random Arabic text.
    public static boolean isSupported() {
        try {
            Locale locale = LocaleController.getInstance().getCurrentLocale();
            if (locale != null && "ar".equals(locale.getLanguage())) {
                return true;
            }
            LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
            if (info != null && info.shortName != null && info.shortName.startsWith("ar")) {
                return true;
            }
            // The app can be translated with a remote pack while the phone
            // itself is Arabic; the voice notes are still Arabic there.
            Locale system = Locale.getDefault();
            return system != null && "ar".equals(system.getLanguage());
        } catch (Exception ignore) {
            return false;
        }
    }

    public static boolean isModelReady() {
        File dir = getModelDir();
        return new File(dir, "am/final.mdl").exists() || new File(dir, "conf/model.conf").exists();
    }

    public static boolean isModelDownloading() {
        return downloading || preparing;
    }

    // Joker Mod: fetch the model in the background without tying a voice
    // message (and its A button) to the download. The button uses this so it
    // never spins for minutes over the ~100 MB archive.
    public static void preloadModel() {
        if (isModelReady() || preparing || downloading) {
            return;
        }
        synchronized (MODEL_LOCK) {
            if (isModelReady() || preparing || downloading) {
                return;
            }
            preparing = true;
        }
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            FileLog.e(TAG + ": preloading the Vosk model");
            try {
                getModel();
            } finally {
                preparing = false;
            }
        }, "TalogramVoskModelPreload").start();
    }

    private static File getModelDir() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), MODEL_DIR);
    }

    public static void transcribe(final MessageObject messageObject, final Callback callback) {
        final int account = messageObject.currentAccount;
        final TLRPC.Document document = MessageObject.getDocument(messageObject.messageOwner);
        final String attachFileName = document != null ? FileLoader.getAttachFileName(document) : null;
        FileLog.e(TAG + ": transcribe start, msg=" + messageObject.getId() + " doc=" + (document != null) + " modelReady=" + isModelReady());
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            String text = null;
            String failure = null;
            try {
                if (document == null || attachFileName == null) {
                    throw new IOException("no document");
                }
                File file = waitForFile(account, messageObject, document, attachFileName);
                if (file == null || !file.exists()) {
                    throw new IOException("voice file is not available");
                }
                FileLog.e(TAG + ": audio file=" + file.getAbsolutePath() + " size=" + file.length());
                Model localModel = getModel();
                if (localModel == null) {
                    throw new IOException("model is not available");
                }
                text = recognize(localModel, file);
                FileLog.e(TAG + ": recognition finished, text length=" + (text == null ? -1 : text.length()));
            } catch (Throwable e) {
                failure = e.toString();
                FileLog.e(TAG + ": local transcription failed", e);
            }
            final String result = text != null ? text.trim() : null;
            final String failureReason = failure;
            AndroidUtilities.runOnUIThread(() -> {
                if (!TextUtils.isEmpty(result)) {
                    callback.onSuccess(result);
                } else {
                    FileLog.e(TAG + ": local transcription failure reported, reason=" + failureReason);
                    callback.onFailure();
                }
            });
        }, "TalogramVoskTranscriber").start();
    }

    private static File waitForFile(int account, MessageObject messageObject, TLRPC.Document document, String attachFileName) throws InterruptedException {
        FileLoader loader = FileLoader.getInstance(account);
        File file = loader.getPathToAttach(document, true);
        if (file != null && file.exists()) {
            return file;
        }
        if (!ApplicationLoader.isNetworkOnline()) {
            FileLog.e(TAG + ": audio is not downloaded and the network is offline");
            return null;
        }
        int cacheType = (MessageObject.isVoiceDocument(document) || MessageObject.isRoundVideoDocument(document)) && messageObject.shouldEncryptPhotoOrVideo() ? 2 : 0;
        loader.loadFile(document, messageObject, FileLoader.PRIORITY_NORMAL, cacheType);
        FileLog.e(TAG + ": waiting for the audio download");
        // Voice messages are small and already auto-downloaded in most cases.
        // Give a manual download a reasonable window without blocking the
        // transcription request forever.
        for (int i = 0; i < 120; i++) {
            Thread.sleep(250);
            file = loader.getPathToAttach(document, true);
            if (file != null && file.exists()) {
                FileLog.e(TAG + ": audio downloaded after " + (i + 1) * 250 + " ms");
                return file;
            }
            if (!loader.isLoadingFile(attachFileName)) {
                Thread.sleep(500);
                file = loader.getPathToAttach(document, true);
                if (file == null || !file.exists()) {
                    FileLog.e(TAG + ": audio download stopped without a file");
                }
                return file != null && file.exists() ? file : null;
            }
        }
        FileLog.e(TAG + ": audio download timed out");
        return null;
    }

    private static Model getModel() {
        Model result = model;
        if (result != null) {
            return result;
        }
        synchronized (MODEL_LOCK) {
            result = model;
            if (result != null) {
                return result;
            }
            if (!isModelReady()) {
                FileLog.e(TAG + ": Vosk model is missing, downloading " + MODEL_URL);
                if (!downloadModel()) {
                    return null;
                }
            }
            try {
                FileLog.e(TAG + ": loading the Vosk model");
                toast(R.string.JokerVoiceModelLoading);
                long started = SystemClock.elapsedRealtime();
                result = new Model(getModelDir().getAbsolutePath());
                model = result;
                FileLog.e(TAG + ": Vosk model loaded in " + (SystemClock.elapsedRealtime() - started) + " ms");
            } catch (Throwable e) {
                FileLog.e(TAG + ": can't load Vosk model", e);
                // A broken model directory should be downloaded again next time.
                // A missing native library is not the model's fault: keep the
                // 100 MB archive extraction instead of forcing a new download on
                // every attempt.
                if (!(e instanceof LinkageError)) {
                    deleteRecursive(getModelDir());
                }
                return null;
            }
            return result;
        }
    }

    private static boolean downloadModel() {
        synchronized (MODEL_LOCK) {
            if (isModelReady()) {
                return true;
            }
            if (downloading) {
                return false;
            }
            downloading = true;
        }
        File zipFile = new File(ApplicationLoader.applicationContext.getFilesDir(), MODEL_DIR + ".zip.part");
        File tmpDir = new File(ApplicationLoader.applicationContext.getFilesDir(), MODEL_DIR + ".tmp");
        boolean success = false;
        boolean downloaded = false;
        try {
            File filesDir = ApplicationLoader.applicationContext.getFilesDir();
            if (filesDir.getUsableSpace() < 350L * 1024 * 1024) {
                FileLog.e(TAG + ": not enough free space for the Vosk model");
                toast(R.string.JokerVoiceModelDownloadFailed);
                return false;
            }
            toast(R.string.JokerVoiceModelDownloading);
            reportDownloadProgress(0);
            downloadFile(MODEL_URL, zipFile);
            downloaded = true;
            FileLog.e(TAG + ": model archive downloaded (" + zipFile.length() + " bytes), extracting");
            reportDownloadProgress(100);
            deleteRecursive(tmpDir);
            if (!tmpDir.mkdirs() && !tmpDir.isDirectory()) {
                throw new IOException("can't create model directory");
            }
            extractZip(zipFile, tmpDir);
            File modelDir = getModelDir();
            deleteRecursive(modelDir);
            if (!tmpDir.renameTo(modelDir)) {
                throw new IOException("can't move the extracted model");
            }
            success = isModelReady();
            if (!success) {
                deleteRecursive(getModelDir());
            } else {
                toast(R.string.JokerVoiceModelReady);
            }
            FileLog.e(TAG + ": model extraction finished, ready=" + success);
        } catch (Throwable e) {
            FileLog.e(TAG + ": model download failed", e);
            deleteRecursive(tmpDir);
            deleteRecursive(getModelDir());
            toast(R.string.JokerVoiceModelDownloadFailed);
        } finally {
            // Keep an incomplete archive so the next attempt resumes instead of
            // downloading 100 MB again. A complete but corrupt archive is
            // deleted so it is fetched from scratch.
            if (success || downloaded) {
                zipFile.delete();
            }
            downloading = false;
            cancelDownloadNotification();
        }
        return success;
    }

    private static void downloadFile(String urlString, File target) throws IOException {
        long existing = target.exists() ? target.length() : 0;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(120000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Talogram");
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (existing > 0) {
                connection.setRequestProperty("Range", "bytes=" + existing + "-");
            }
            int code = connection.getResponseCode();
            if (code == 413 || code == 416) {
                // The partial file does not match the remote one; restart.
                target.delete();
                existing = 0;
                connection.disconnect();
                connection = (HttpURLConnection) new URL(urlString).openConnection();
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(120000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "Talogram");
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Accept-Encoding", "identity");
                code = connection.getResponseCode();
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("HTTP " + code);
            }
            boolean append = code == HttpURLConnection.HTTP_PARTIAL;
            if (!append) {
                existing = 0;
            }
            long total = -1;
            int contentLength = connection.getContentLength();
            if (contentLength > 0) {
                total = existing + contentLength;
            }
            FileLog.e(TAG + ": download started at " + existing + " bytes of " + total);
            long written = existing;
            int lastPercent = -1;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(target, append)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    written += read;
                    if (total > 0) {
                        int percent = (int) Math.min(99, written * 100 / total);
                        if (percent >= lastPercent + 2) {
                            lastPercent = percent;
                            reportDownloadProgress(percent);
                        }
                    }
                }
                output.flush();
            }
            if (total > 0 && target.length() != total) {
                throw new IOException("incomplete download: " + target.length() + " of " + total);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void extractZip(File zipFile, File targetDir) throws IOException {
        String targetPath = targetDir.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                int slash = name.indexOf('/');
                if (slash >= 0) {
                    name = name.substring(slash + 1);
                }
                if (name.isEmpty()) {
                    continue;
                }
                File out = new File(targetDir, name);
                String outPath = out.getCanonicalPath();
                if (!outPath.startsWith(targetPath)) {
                    throw new IOException("bad zip entry");
                }
                if (entry.isDirectory()) {
                    if (!out.mkdirs() && !out.isDirectory()) {
                        throw new IOException("can't create " + out);
                    }
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IOException("can't create " + parent);
                }
                try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(out))) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static String recognize(Model localModel, File file) throws IOException {
        StringBuilder text = new StringBuilder();
        Recognizer recognizer = null;
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        try {
            recognizer = new Recognizer(localModel, TARGET_SAMPLE_RATE);
            extractor = new MediaExtractor();
            extractor.setDataSource(file.getAbsolutePath());
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = candidate;
                    break;
                }
            }
            if (trackIndex < 0 || format == null) {
                throw new IOException("no audio track");
            }
            String mime = format.getString(MediaFormat.KEY_MIME);
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : TARGET_SAMPLE_RATE;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? Math.max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) : 1;
            int encoding = MediaCodecInfo.ENCODING_PCM_16BIT;

            extractor.selectTrack(trackIndex);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
            FileLog.e(TAG + ": decoding " + mime + " " + sampleRate + "Hz " + channels + "ch");

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            LinearResampler resampler = new LinearResampler(sampleRate, TARGET_SAMPLE_RATE);
            ByteArrayOutputStream pending = new ByteArrayOutputStream(FEED_CHUNK_BYTES * 2);
            boolean inputDone = false;
            boolean outputDone = false;
            // Some decoders never report END_OF_STREAM for a broken file. A
            // deadline turns that into a normal failure instead of an endless
            // loading spinner.
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            long deadline = SystemClock.elapsedRealtime() + Math.max(120000L, durationUs / 1000 * 3 + 60000L);
            while (!outputDone) {
                if (SystemClock.elapsedRealtime() > deadline) {
                    throw new IOException("audio decode timeout");
                }
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
                    if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        resampler = new LinearResampler(sampleRate, TARGET_SAMPLE_RATE);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = Math.max(1, outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    }
                    if (Build.VERSION.SDK_INT >= 24 && outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                } else if (outputIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                        if (outputBuffer != null) {
                            outputBuffer.position(info.offset);
                            outputBuffer.limit(info.offset + info.size);
                            byte[] raw = new byte[info.size];
                            outputBuffer.get(raw);
                            short[] samples = toShorts(raw, encoding);
                            short[] mono = toMono(samples, channels);
                            short[] resampled = resampler.process(mono);
                            feed(recognizer, resampled, pending, text);
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }

            feed(recognizer, new short[0], pending, text);
            appendText(text, recognizer.getFinalResult());
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignore) {
                }
                try {
                    codec.release();
                } catch (Exception ignore) {
                }
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception ignore) {
                }
            }
            if (recognizer != null) {
                try {
                    recognizer.close();
                } catch (Exception ignore) {
                }
            }
        }
        return text.toString();
    }

    private static void feed(Recognizer recognizer, short[] samples, ByteArrayOutputStream pending, StringBuilder text) {
        if (samples.length > 0) {
            byte[] bytes = new byte[samples.length * 2];
            for (int i = 0; i < samples.length; i++) {
                short value = samples[i];
                bytes[i * 2] = (byte) (value & 0xff);
                bytes[i * 2 + 1] = (byte) ((value >> 8) & 0xff);
            }
            pending.write(bytes, 0, bytes.length);
        }
        if (pending.size() >= FEED_CHUNK_BYTES || (samples.length == 0 && pending.size() > 0)) {
            byte[] data = pending.toByteArray();
            pending.reset();
            if (recognizer.acceptWaveForm(data, data.length)) {
                appendText(text, recognizer.getResult());
            }
        }
    }

    private static void appendText(StringBuilder text, String json) {
        if (TextUtils.isEmpty(json)) {
            return;
        }
        try {
            String part = new JSONObject(json).optString("text", "").trim();
            if (!part.isEmpty()) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(part);
            }
        } catch (Exception ignore) {
        }
    }

    private static short[] toShorts(byte[] raw, int encoding) {
        if (encoding == MediaCodecInfo.ENCODING_PCM_FLOAT) {
            int count = raw.length / 4;
            float[] floats = new float[count];
            ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder()).asFloatBuffer().get(floats);
            short[] result = new short[count];
            for (int i = 0; i < count; i++) {
                result[i] = clamp((int) Math.round(floats[i] * 32767f));
            }
            return result;
        }
        if (encoding == MediaCodecInfo.ENCODING_PCM_8BIT) {
            short[] result = new short[raw.length];
            for (int i = 0; i < raw.length; i++) {
                result[i] = (short) (((raw[i] & 0xff) - 128) << 8);
            }
            return result;
        }
        int count = raw.length / 2;
        short[] result = new short[count];
        for (int i = 0; i < count; i++) {
            result[i] = (short) ((raw[i * 2] & 0xff) | (raw[i * 2 + 1] << 8));
        }
        return result;
    }

    private static short[] toMono(short[] samples, int channels) {
        if (channels <= 1) {
            return samples;
        }
        int frames = samples.length / channels;
        short[] result = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += samples[i * channels + c];
            }
            result[i] = clamp(sum / channels);
        }
        return result;
    }

    private static short clamp(int value) {
        if (value > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (value < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) value;
    }

    private static void toast(final int resId) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Toast.makeText(ApplicationLoader.applicationContext, resId, Toast.LENGTH_LONG).show();
            } catch (Exception ignore) {
            }
        });
    }

    // The download of the model is large, so it must be visible: a toast every
    // 25% and an ongoing notification with the exact progress. Otherwise the A
    // button looks stuck for several minutes on a slow connection.
    private static void reportDownloadProgress(final int percent) {
        final boolean newToastStep = percent >= 100 || lastToastPercent / 25 != percent / 25;
        if (newToastStep) {
            lastToastPercent = percent;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    if (progressToast == null) {
                        progressToast = Toast.makeText(ApplicationLoader.applicationContext, "", Toast.LENGTH_SHORT);
                    }
                    progressToast.setText(LocaleController.formatString("JokerVoiceModelDownloadProgress", R.string.JokerVoiceModelDownloadProgress, percent));
                    progressToast.show();
                } catch (Exception ignore) {
                }
            });
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                showDownloadNotification(percent);
            } catch (Throwable e) {
                FileLog.e(TAG + ": progress notification failed", e);
            }
        });
    }

    private static void showDownloadNotification(int percent) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    LocaleController.getString(R.string.JokerVoiceModelNotificationChannel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID);
        builder.setSmallIcon(R.drawable.notification);
        builder.setContentTitle(LocaleController.getString(R.string.JokerVoiceModelNotificationTitle));
        builder.setContentText(LocaleController.formatString("JokerVoiceModelDownloadProgress", R.string.JokerVoiceModelDownloadProgress, percent));
        builder.setProgress(100, percent, false);
        builder.setOngoing(true);
        builder.setOnlyAlertOnce(true);
        builder.setPriority(NotificationCompat.PRIORITY_LOW);
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    private static void cancelDownloadNotification() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (progressToast != null) {
                    progressToast.cancel();
                }
                NotificationManager manager = (NotificationManager) ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) {
                    manager.cancel(NOTIFICATION_ID);
                }
            } catch (Throwable ignore) {
            }
        });
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /**
     * Streaming linear resampler. Keeps the fractional phase and the last
     * sample between decoder chunks so there are no clicks at chunk borders.
     */
    private static final class LinearResampler {
        private final double step;
        private double position;
        private short lastSample;
        private boolean hasLastSample;

        LinearResampler(int fromRate, int toRate) {
            this.step = fromRate / (double) toRate;
        }

        short[] process(short[] input) {
            if (input.length == 0) {
                return input;
            }
            if (step == 1.0) {
                return input;
            }
            int capacity = (int) Math.ceil(input.length / step) + 2;
            short[] output = new short[capacity];
            int count = 0;
            while (position < input.length - 1) {
                int index = (int) Math.floor(position);
                double fraction = position - index;
                int a;
                if (index < 0) {
                    a = hasLastSample ? lastSample : input[0];
                } else {
                    a = input[index];
                }
                int b = index + 1 < 0 ? input[0] : input[Math.min(index + 1, input.length - 1)];
                if (index < 0) {
                    fraction = 0;
                }
                output[count++] = clamp((int) Math.round(a + (b - a) * fraction));
                position += step;
            }
            position -= input.length;
            lastSample = input[input.length - 1];
            hasLastSample = true;
            if (count == output.length) {
                return output;
            }
            short[] trimmed = new short[count];
            System.arraycopy(output, 0, trimmed, 0, count);
            return trimmed;
        }
    }

    // Avoid a dependency on android.media.MediaCodecInfo constants at call sites.
    private static final class MediaCodecInfo {
        static final int ENCODING_PCM_16BIT = 2;
        static final int ENCODING_PCM_8BIT = 3;
        static final int ENCODING_PCM_FLOAT = 4;
    }
}
