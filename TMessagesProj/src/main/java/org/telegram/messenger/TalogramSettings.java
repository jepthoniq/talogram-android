/*
 * Talogram mod - local settings and local quick replies storage.
 * Everything is stored on this device; nothing is sent to any server.
 */

package org.telegram.messenger;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TalogramSettings {

    public static final int MEDIA_TYPE_NONE = 0;
    public static final int MEDIA_TYPE_PHOTO = 1;
    public static final int MEDIA_TYPE_VIDEO = 2;

    private static final String PREFS = "talogram_settings";

    public static final String KEY_GHOST_MODE = "ghost_mode";
    public static final String KEY_FREEZE_LAST_SEEN = "freeze_last_seen";
    public static final String KEY_KEEP_DELETED = "keep_deleted_messages";
    public static final String KEY_FORCE_PREMIUM_TRANSLATION = "force_premium_translation";
    public static final String KEY_ALLOW_100_ACCOUNTS = "allow_100_accounts";
    public static final String KEY_BYPASS_COPY_PROTECTION = "bypass_copy_protection";
    public static final String KEY_AUTO_SAVE_MEDIA = "auto_save_self_destruct_media";
    public static final String KEY_STORY_FREEZE = "story_freeze";
    public static final String KEY_QUICK_REPLIES = "quick_replies_json";

    public static final class QuickReply {
        public String id;
        public String shortcut;
        public String text;
        public String mediaFileName;
        public int mediaType;
        public long mediaWidth;
        public long mediaHeight;
        public long mediaDuration;
        public String mediaMime;

        public boolean hasMedia() {
            return mediaType != MEDIA_TYPE_NONE && !TextUtils.isEmpty(mediaFileName);
        }
    }

    private TalogramSettings() {
    }

    private static android.content.SharedPreferences prefs() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean getBoolean(String key) {
        android.content.SharedPreferences p = prefs();
        return p != null && p.getBoolean(key, false);
    }

    public static void setBoolean(String key, boolean value) {
        android.content.SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putBoolean(key, value).apply();
        }
    }

    public static boolean isGhostMode() {
        return getBoolean(KEY_GHOST_MODE);
    }

    public static boolean isFreezeLastSeen() {
        return getBoolean(KEY_FREEZE_LAST_SEEN);
    }

    public static boolean isKeepDeletedMessages() {
        return getBoolean(KEY_KEEP_DELETED);
    }

    public static boolean isForcePremiumTranslation() {
        return getBoolean(KEY_FORCE_PREMIUM_TRANSLATION);
    }

    public static boolean isAllow100Accounts() {
        return getBoolean(KEY_ALLOW_100_ACCOUNTS);
    }

    public static boolean isBypassCopyProtection() {
        return getBoolean(KEY_BYPASS_COPY_PROTECTION);
    }

    public static boolean isAutoSaveSelfDestructMedia() {
        return getBoolean(KEY_AUTO_SAVE_MEDIA);
    }

    public static boolean isStoryFreeze() {
        return getBoolean(KEY_STORY_FREEZE);
    }

    public static String normalizeShortcut(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        result = result.replace(" ", "").replace("\n", "").replace("\t", "").trim();
        return result;
    }

    public static List<QuickReply> getQuickReplies() {
        ArrayList<QuickReply> result = new ArrayList<>();
        android.content.SharedPreferences p = prefs();
        if (p == null) {
            return result;
        }
        String json = p.getString(KEY_QUICK_REPLIES, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                QuickReply r = new QuickReply();
                r.id = o.optString("id");
                r.shortcut = o.optString("shortcut");
                r.text = o.optString("text");
                r.mediaFileName = o.optString("mediaFileName", null);
                r.mediaType = o.optInt("mediaType", MEDIA_TYPE_NONE);
                r.mediaWidth = o.optLong("mediaWidth", 0);
                r.mediaHeight = o.optLong("mediaHeight", 0);
                r.mediaDuration = o.optLong("mediaDuration", 0);
                r.mediaMime = o.optString("mediaMime", null);
                if (!TextUtils.isEmpty(r.id)) {
                    result.add(r);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    public static void saveQuickReplies(List<QuickReply> list) {
        JSONArray array = new JSONArray();
        for (QuickReply r : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("shortcut", r.shortcut);
                o.put("text", r.text == null ? "" : r.text);
                o.put("mediaFileName", r.mediaFileName);
                o.put("mediaType", r.mediaType);
                o.put("mediaWidth", r.mediaWidth);
                o.put("mediaHeight", r.mediaHeight);
                o.put("mediaDuration", r.mediaDuration);
                o.put("mediaMime", r.mediaMime);
                array.put(o);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        android.content.SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putString(KEY_QUICK_REPLIES, array.toString()).apply();
        }
    }

    public static QuickReply findQuickReply(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String t = text.trim();
        if (!t.startsWith("/")) {
            return null;
        }
        String shortcut = normalizeShortcut(t);
        if (shortcut.isEmpty()) {
            return null;
        }
        for (QuickReply r : getQuickReplies()) {
            if (normalizeShortcut(r.shortcut).equalsIgnoreCase(shortcut)) {
                return r;
            }
        }
        return null;
    }

    public static QuickReply findQuickReplyById(String id) {
        if (TextUtils.isEmpty(id)) {
            return null;
        }
        for (QuickReply r : getQuickReplies()) {
            if (id.equals(r.id)) {
                return r;
            }
        }
        return null;
    }

    public static int getQuickRepliesCount() {
        return getQuickReplies().size();
    }

    public static File quickReplyMediaDirectory() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "talogram_quick_replies");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File quickReplyMediaFile(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return null;
        }
        File dir = quickReplyMediaDirectory();
        if (dir == null) {
            return null;
        }
        return new File(dir, fileName);
    }

    public static void deleteQuickReply(String id) {
        List<QuickReply> list = getQuickReplies();
        for (int i = list.size() - 1; i >= 0; i--) {
            QuickReply r = list.get(i);
            if (r.id != null && r.id.equals(id)) {
                if (r.hasMedia()) {
                    File f = quickReplyMediaFile(r.mediaFileName);
                    if (f != null && f.exists()) {
                        f.delete();
                    }
                }
                list.remove(i);
            }
        }
        saveQuickReplies(list);
    }

    public static String copyQuickReplyMedia(Uri uri, String mimeType) {
        try {
            if (ApplicationLoader.applicationContext == null || uri == null) {
                return null;
            }
            String ext = ".bin";
            if (mimeType != null) {
                if (mimeType.startsWith("image/")) {
                    if (mimeType.endsWith("png")) {
                        ext = ".png";
                    } else if (mimeType.endsWith("webp")) {
                        ext = ".webp";
                    } else if (mimeType.endsWith("gif")) {
                        ext = ".gif";
                    } else {
                        ext = ".jpg";
                    }
                } else if (mimeType.startsWith("video/")) {
                    if (mimeType.endsWith("webm")) {
                        ext = ".webm";
                    } else if (mimeType.endsWith("mkv")) {
                        ext = ".mkv";
                    } else {
                        ext = ".mp4";
                    }
                }
            }
            File dir = quickReplyMediaDirectory();
            if (dir == null) {
                return null;
            }
            String name = UUID.randomUUID().toString() + ext;
            File target = new File(dir, name);
            try (InputStream input = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(target)) {
                if (input == null) {
                    target.delete();
                    return null;
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
            return name;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public static long getQuickReplyMediaSize(Uri uri) {
        try {
            if (ApplicationLoader.applicationContext == null || uri == null) {
                return -1;
            }
            android.content.ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
            try (android.os.ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
                if (pfd != null) {
                    return pfd.getStatSize();
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return -1;
    }
}
