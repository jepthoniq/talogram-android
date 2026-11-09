/*
 * Talogram mod - quick reply editor. Mirrors the iOS quick reply editor.
 */

package org.telegram.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.TalogramSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.util.List;
import java.util.UUID;

public class TalogramQuickReplyEditorActivity extends BaseFragment {

    private static final int REQUEST_PICK_MEDIA = 9101;
    private static final long MAX_MEDIA_BYTES = 64L * 1024 * 1024;

    private final String replyId;

    private android.widget.EditText shortcutField;
    private android.widget.EditText textField;
    private LinearLayout attachRow;
    private TextView attachLabel;
    private LinearLayout mediaContainer;
    private ImageView mediaThumbnail;
    private TextView mediaTypeLabel;
    private TextView mediaSizeLabel;
    private TextView errorView;
    private TextView saveButton;
    private TextView deleteButton;

    private TalogramSettings.QuickReply draft;
    private boolean creatingNew;
    private String pendingDeleteFileName;
    private String newlyCreatedFileName;

    public TalogramQuickReplyEditorActivity(@Nullable String replyId) {
        super();
        this.replyId = replyId;
        TalogramSettings.QuickReply existing = replyId != null ? TalogramSettings.findQuickReplyById(replyId) : null;
        if (existing != null) {
            creatingNew = false;
            draft = copy(existing);
        } else {
            creatingNew = true;
            draft = new TalogramSettings.QuickReply();
            draft.id = UUID.randomUUID().toString();
            draft.shortcut = "";
            draft.text = "";
            draft.mediaType = TalogramSettings.MEDIA_TYPE_NONE;
        }
    }

    private static TalogramSettings.QuickReply copy(TalogramSettings.QuickReply r) {
        TalogramSettings.QuickReply copy = new TalogramSettings.QuickReply();
        copy.id = r.id;
        copy.shortcut = r.shortcut;
        copy.text = r.text;
        copy.mediaFileName = r.mediaFileName;
        copy.mediaType = r.mediaType;
        copy.mediaWidth = r.mediaWidth;
        copy.mediaHeight = r.mediaHeight;
        copy.mediaDuration = r.mediaDuration;
        copy.mediaMime = r.mediaMime;
        return copy;
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(LocaleController.getString(creatingNew ? R.string.JokerQuickReplyNew : R.string.JokerQuickReplyEdit));
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    cancelAndFinish();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(18);
        content.setPadding(pad, AndroidUtilities.dp(8), pad, AndroidUtilities.dp(18));

        content.addView(buildShortcutRow(context));
        content.addView(buildTextField(context));
        content.addView(buildAttachRow(context));
        content.addView(buildMediaPreview(context));
        content.addView(buildErrorView(context));
        content.addView(buildSaveRow(context));

        scroll.addView(content);
        frameLayout.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        populateFromDraft(context);
        updateSaveState();
        return fragmentView;
    }

    private View buildShortcutRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14));

        TextView slash = new TextView(context);
        slash.setText("/");
        slash.setTextSize(20f);
        slash.setTypeface(AndroidUtilities.bold());
        slash.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.addView(slash, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));

        shortcutField = new android.widget.EditText(context);
        shortcutField.setHint(LocaleController.getString(R.string.JokerQuickReplyShortcutHint));
        shortcutField.setSingleLine(true);
        shortcutField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        shortcutField.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        shortcutField.setBackground(null);
        shortcutField.setTextSize(17f);
        shortcutField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        shortcutField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        shortcutField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        shortcutField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                textField.requestFocus();
                return true;
            }
            return false;
        });
        shortcutField.addTextChangedListener(simpleWatcher());
        row.addView(shortcutField, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        return row;
    }

    private View buildTextField(Context context) {
        textField = new android.widget.EditText(context);
        textField.setHint(LocaleController.getString(R.string.JokerQuickReplyTextHint));
        textField.setGravity(Gravity.TOP | Gravity.START);
        textField.setMinLines(5);
        textField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        textField.setImeOptions(EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        textField.setBackground(null);
        textField.setTextSize(16f);
        textField.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14));
        textField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        textField.addTextChangedListener(simpleWatcher());
        return textField;
    }

    private View buildAttachRow(Context context) {
        attachRow = new LinearLayout(context);
        attachRow.setOrientation(LinearLayout.HORIZONTAL);
        attachRow.setGravity(Gravity.CENTER_VERTICAL);
        attachRow.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14));
        attachRow.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        attachRow.setClickable(true);
        attachRow.setFocusable(true);

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_talogram_attach);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
        attachRow.addView(icon, LayoutHelper.createLinear(24, 24));

        attachLabel = new TextView(context);
        attachLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        attachLabel.setTextSize(16f);
        attachLabel.setPadding(AndroidUtilities.dp(14), 0, 0, 0);
        attachLabel.setText(LocaleController.getString(R.string.JokerQuickReplyAttach));
        attachRow.addView(attachLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        attachRow.setOnClickListener(v -> pickMedia());
        return attachRow;
    }

    private View buildMediaPreview(Context context) {
        mediaContainer = new LinearLayout(context);
        mediaContainer.setOrientation(LinearLayout.HORIZONTAL);
        mediaContainer.setGravity(Gravity.CENTER_VERTICAL);
        mediaContainer.setVisibility(View.GONE);
        mediaContainer.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(10));

        FrameLayout thumbContainer = new FrameLayout(context);
        thumbContainer.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(14), Color.BLACK));
        mediaThumbnail = new ImageView(context);
        mediaThumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbContainer.addView(mediaThumbnail, LayoutHelper.createFrame(96, 96, Gravity.CENTER));
        mediaContainer.addView(thumbContainer, LayoutHelper.createLinear(96, 96, 0, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        mediaTypeLabel = new TextView(context);
        mediaTypeLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        mediaTypeLabel.setTextSize(16f);
        mediaTypeLabel.setTypeface(AndroidUtilities.bold());
        info.addView(mediaTypeLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        mediaSizeLabel = new TextView(context);
        mediaSizeLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        mediaSizeLabel.setTextSize(13f);
        info.addView(mediaSizeLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        mediaContainer.addView(info, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        ImageView remove = new ImageView(context);
        remove.setImageResource(R.drawable.ic_talogram_remove);
        remove.setScaleType(ImageView.ScaleType.CENTER);
        remove.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        remove.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
        remove.setContentDescription(LocaleController.getString(R.string.JokerQuickReplyDelete));
        remove.setOnClickListener(v -> removeMedia());
        mediaContainer.addView(remove, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));
        return mediaContainer;
    }

    private View buildErrorView(Context context) {
        errorView = new TextView(context);
        errorView.setTextColor(Color.parseColor("#E53935"));
        errorView.setTextSize(13f);
        errorView.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        errorView.setVisibility(View.GONE);
        return errorView;
    }

    private View buildSaveRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, AndroidUtilities.dp(8), 0, 0);

        deleteButton = new TextView(context);
        deleteButton.setText(LocaleController.getString(R.string.JokerQuickReplyDelete));
        deleteButton.setTextColor(Color.parseColor("#E53935"));
        deleteButton.setTextSize(15f);
        deleteButton.setTypeface(AndroidUtilities.bold());
        deleteButton.setGravity(Gravity.CENTER);
        deleteButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        deleteButton.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(12), AndroidUtilities.dp(18), AndroidUtilities.dp(12));
        deleteButton.setOnClickListener(v -> confirmDelete());
        deleteButton.setVisibility(creatingNew ? View.GONE : View.VISIBLE);
        row.addView(deleteButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        TextView spacer = new TextView(context);
        row.addView(spacer, LayoutHelper.createLinear(0, 1, 1f));

        TextView cancel = new TextView(context);
        cancel.setText(LocaleController.getString(R.string.JokerQuickReplyCancel));
        cancel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        cancel.setTextSize(15f);
        cancel.setTypeface(AndroidUtilities.bold());
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        cancel.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        cancel.setOnClickListener(v -> cancelAndFinish());
        row.addView(cancel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));

        saveButton = new TextView(context);
        saveButton.setText(LocaleController.getString(R.string.JokerQuickReplySave));
        saveButton.setTextColor(Color.WHITE);
        saveButton.setTextSize(15f);
        saveButton.setTypeface(AndroidUtilities.bold());
        saveButton.setGravity(Gravity.CENTER);
        saveButton.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(22), Theme.getColor(Theme.key_featuredStickers_addButton)));
        saveButton.setPadding(AndroidUtilities.dp(26), AndroidUtilities.dp(12), AndroidUtilities.dp(26), AndroidUtilities.dp(12));
        saveButton.setOnClickListener(v -> save());
        row.addView(saveButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        return row;
    }

    private TextWatcher simpleWatcher() {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                showError(null);
                updateSaveState();
            }
        };
    }

    private void populateFromDraft(Context context) {
        shortcutField.setText(draft.shortcut == null ? "" : draft.shortcut);
        textField.setText(draft.text == null ? "" : draft.text);
        updateMediaUi();
    }

    private void updateSaveState() {
        String shortcut = TalogramSettings.normalizeShortcut(shortcutField == null ? "" : shortcutField.getText().toString());
        boolean hasText = textField != null && !TextUtils.isEmpty(textField.getText().toString().trim());
        boolean valid = !shortcut.isEmpty() && (hasText || (draft != null && draft.hasMedia()));
        if (saveButton != null) {
            saveButton.setAlpha(valid ? 1.0f : 0.45f);
        }
    }

    private void showError(@Nullable String message) {
        if (errorView == null) {
            return;
        }
        if (TextUtils.isEmpty(message)) {
            errorView.setVisibility(View.GONE);
        } else {
            errorView.setText(message);
            errorView.setVisibility(View.VISIBLE);
        }
    }

    private void pickMedia() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(Intent.createChooser(intent, null), REQUEST_PICK_MEDIA);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PICK_MEDIA && resultCode == android.app.Activity.RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            ContentResolver resolver = getParentActivity() != null ? getParentActivity().getContentResolver() : null;
            String mime = resolver != null ? resolver.getType(uri) : null;
            if (mime == null) {
                mime = "image/*";
            }
            if (!(mime.startsWith("image/") || mime.startsWith("video/"))) {
                return;
            }
            long size = TalogramSettings.getQuickReplyMediaSize(uri);
            if (size > MAX_MEDIA_BYTES) {
                showError(LocaleController.getString(R.string.JokerQuickReplyMediaTooBig));
                return;
            }
            String fileName = TalogramSettings.copyQuickReplyMedia(uri, mime);
            if (fileName == null) {
                return;
            }
            String old = draft.mediaFileName;
            if (old != null) {
                pendingDeleteFileName = old;
            }
            if (newlyCreatedFileName != null) {
                pendingDeleteFileName = newlyCreatedFileName;
            }
            draft.mediaFileName = fileName;
            draft.mediaMime = mime;
            draft.mediaType = mime.startsWith("video/") ? TalogramSettings.MEDIA_TYPE_VIDEO : TalogramSettings.MEDIA_TYPE_PHOTO;
            newlyCreatedFileName = fileName;
            readMediaMetadata(fileName, mime);
            updateMediaUi();
            updateSaveState();
        }
    }

    private void readMediaMetadata(String fileName, String mime) {
        File file = TalogramSettings.quickReplyMediaFile(fileName);
        if (file == null || !file.exists()) {
            return;
        }
        try {
            if (mime.startsWith("video/")) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(file.getAbsolutePath());
                    draft.mediaWidth = parseLongSafe(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    draft.mediaHeight = parseLongSafe(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    draft.mediaDuration = parseLongSafe(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    try {
                        retriever.release();
                    } catch (Exception ignore) {
                    }
                }
            } else {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                draft.mediaWidth = bounds.outWidth;
                draft.mediaHeight = bounds.outHeight;
                draft.mediaDuration = 0;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static long parseLongSafe(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private void removeMedia() {
        String old = draft.mediaFileName;
        if (old != null) {
            pendingDeleteFileName = old;
        }
        if (newlyCreatedFileName != null && newlyCreatedFileName.equals(old)) {
            newlyCreatedFileName = null;
        }
        draft.mediaFileName = null;
        draft.mediaMime = null;
        draft.mediaType = TalogramSettings.MEDIA_TYPE_NONE;
        draft.mediaWidth = 0;
        draft.mediaHeight = 0;
        draft.mediaDuration = 0;
        updateMediaUi();
        updateSaveState();
    }

    private void updateMediaUi() {
        boolean hasMedia = draft != null && draft.hasMedia();
        if (mediaContainer == null) {
            return;
        }
        mediaContainer.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
        attachLabel.setText(LocaleController.getString(hasMedia ? R.string.JokerQuickReplyChangeMedia : R.string.JokerQuickReplyAttach));
        if (!hasMedia) {
            return;
        }
        File file = TalogramSettings.quickReplyMediaFile(draft.mediaFileName);
        if (draft.mediaType == TalogramSettings.MEDIA_TYPE_VIDEO) {
            mediaTypeLabel.setText(LocaleController.getString(R.string.JokerQuickReplyMediaVideo));
            mediaThumbnail.setImageResource(R.drawable.ic_talogram_play);
            mediaThumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            mediaThumbnail.setBackgroundColor(Color.BLACK);
        } else {
            mediaTypeLabel.setText(LocaleController.getString(R.string.JokerQuickReplyMediaImage));
            mediaThumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mediaThumbnail.setBackground(null);
            if (file != null && file.exists()) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                int sample = 1;
                int max = Math.max(bounds.outWidth, bounds.outHeight);
                while (max / (sample * 2) >= 256) {
                    sample *= 2;
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                mediaThumbnail.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath(), options));
            }
        }
        if (file != null) {
            long bytes = file.length();
            String size = bytes >= 1024 * 1024
                    ? String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f))
                    : String.format(java.util.Locale.US, "%.0f KB", bytes / 1024f);
            mediaSizeLabel.setText(size);
        } else {
            mediaSizeLabel.setText("");
        }
    }

    private void save() {
        String shortcut = TalogramSettings.normalizeShortcut(shortcutField.getText().toString());
        if (shortcut.isEmpty()) {
            showError(LocaleController.getString(R.string.JokerQuickReplyShortcutRequired));
            return;
        }
        String text = textField.getText().toString().trim();
        boolean hasMedia = draft != null && draft.hasMedia();
        if (text.isEmpty() && !hasMedia) {
            showError(LocaleController.getString(R.string.JokerQuickReplyTextRequired));
            return;
        }
        List<TalogramSettings.QuickReply> replies = TalogramSettings.getQuickReplies();
        for (TalogramSettings.QuickReply r : replies) {
            if (draft.id.equals(r.id)) {
                continue;
            }
            if (TalogramSettings.normalizeShortcut(r.shortcut).equalsIgnoreCase(shortcut)) {
                showError(LocaleController.getString(R.string.JokerQuickReplyShortcutUsed));
                return;
            }
        }
        draft.shortcut = shortcut;
        draft.text = text;
        boolean replaced = false;
        for (int i = 0; i < replies.size(); i++) {
            if (replies.get(i).id.equals(draft.id)) {
                replies.set(i, draft);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            replies.add(draft);
        }
        TalogramSettings.saveQuickReplies(replies);
        if (pendingDeleteFileName != null && !pendingDeleteFileName.equals(draft.mediaFileName)) {
            File file = TalogramSettings.quickReplyMediaFile(pendingDeleteFileName);
            if (file != null && file.exists()) {
                file.delete();
            }
        }
        newlyCreatedFileName = null;
        finishFragment();
    }

    private void confirmDelete() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.JokerQuickReplyDeleteTitle));
        builder.setMessage(LocaleController.getString(R.string.JokerQuickReplyDeleteMessage));
        builder.setPositiveButton(LocaleController.getString(R.string.JokerQuickReplyDelete), (dialog, which) -> {
            if (draft != null && draft.id != null) {
                TalogramSettings.deleteQuickReply(draft.id);
            }
            newlyCreatedFileName = null;
            finishFragment();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.JokerQuickReplyCancel), null);
        showDialog(builder.create());
    }

    private void cancelAndFinish() {
        if (newlyCreatedFileName != null) {
            File file = TalogramSettings.quickReplyMediaFile(newlyCreatedFileName);
            if (file != null && file.exists()) {
                file.delete();
            }
        }
        finishFragment();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }
}
