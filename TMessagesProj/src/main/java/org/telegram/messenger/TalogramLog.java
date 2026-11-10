/*
 * Talogram mod - lightweight error log shown inside the app itself.
 * Writes errors (crashes + request failures) to a plain-text file and
 * can also show them as toasts. Used to debug without adb/logcat.
 */

package org.telegram.messenger;

import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class TalogramLog {

    private static final String FILE_NAME = "talogram_error.log";
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_TOAST_CHARS = 220;
    private static final String PREFS = "talogram_settings";
    private static final String KEY_TOASTS = "debug_toasts";

    private TalogramLog() {
    }

    private static File logFile() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        try {
            return new File(ApplicationLoader.applicationContext.getFilesDir(), FILE_NAME);
        } catch (Exception e) {
            return null;
        }
    }

    public static void log(String tag, String message) {
        append(tag, message);
    }

    public static void log(String tag, Throwable e) {
        StringBuilder builder = new StringBuilder();
        if (e != null) {
            builder.append(e.getClass().getSimpleName());
            if (e.getMessage() != null) {
                builder.append(": ").append(e.getMessage());
            }
        } else {
            builder.append("unknown error");
        }
        append(tag, builder.toString());
    }

    public static void logFull(String tag, String message, Throwable e) {
        if (e == null) {
            append(tag, message);
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(message);
        String cause = null;
        Throwable trace = e;
        int depth = 0;
        while (trace != null && depth < 6) {
            builder.append(" [").append(trace.getClass().getSimpleName());
            if (trace.getMessage() != null) {
                builder.append(": ").append(trace.getMessage());
            }
            builder.append("]");
            trace = trace.getCause();
            depth++;
        }
        append(tag, builder.toString());
    }

    private static void append(String tag, String message) {
        try {
            File file = logFile();
            if (file == null) {
                return;
            }
            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String line = "[" + now + "] [" + (tag == null ? "" : tag) + "] " + message;
            line = line.replace('\r', ' ').replace('\n', ' ');
            if (file.length() > MAX_FILE_BYTES) {
                file.delete();
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes("UTF-8"));
                out.write('\n');
            }
            if (isToastEnabled()) {
                showToast(line);
            }
        } catch (Throwable ignored) {
        }
    }

    public static String readAll() {
        try {
            File file = logFile();
            if (file == null || !file.exists()) {
                return "";
            }
            try (FileInputStream in = new FileInputStream(file)) {
                int length = (int) file.length();
                byte[] bytes = new byte[Math.min(length, MAX_FILE_BYTES)];
                int read = in.read(bytes);
                if (read <= 0) {
                    return "";
                }
                return new String(bytes, 0, read, "UTF-8");
            }
        } catch (Exception e) {
            return "";
        }
    }

    public static void clear() {
        try {
            File file = logFile();
            if (file != null && file.exists()) {
                file.delete();
            }
        } catch (Exception ignored) {
        }
    }

    public static long size() {
        File file = logFile();
        return file != null && file.exists() ? file.length() : 0;
    }

    public static boolean isToastEnabled() {
        try {
            if (ApplicationLoader.applicationContext == null) {
                return false;
            }
            return ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_TOASTS, true);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setToastEnabled(boolean enabled) {
        try {
            if (ApplicationLoader.applicationContext != null) {
                ApplicationLoader.applicationContext
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_TOASTS, enabled).apply();
            }
        } catch (Exception ignored) {
        }
    }

    private static void showToast(final String line) {
        try {
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    String text = line;
                    if (text.length() > MAX_TOAST_CHARS) {
                        text = text.substring(0, MAX_TOAST_CHARS) + "…";
                    }
                    Toast toast = Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG);
                    toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, AndroidUtilities.dp(96));
                    toast.show();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public static String maskPhone(String phone) {
        if (TextUtils.isEmpty(phone)) {
            return "";
        }
        if (phone.length() <= 6) {
            return "***";
        }
        return phone.substring(0, 2) + "******" + phone.substring(phone.length() - 2);
    }

    static {
        SystemClock.elapsedRealtime();
    }
}
