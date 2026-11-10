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
    private LinearLayout attachCard;
    private TextView attachLabel;
    private TextView attachHint;
    private LinearLayout mediaCard;
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

        int sheetColor = Theme.getColor(Theme.key_windowBackgroundGray);
        frameLayout.setBackgroundColor(sheetColor);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight(), 0, AndroidUtilities.navigationBarHeight);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(20));

        TextView shortcutLabel = buildSectionLabel(context, LocaleController.getString(R.string.JokerQuickReplyShortcutLabel));
        content.addView(shortcutLabel, createLabelParams());
        content.addView(buildShortcutRow(context), createCardParams());
        TextView shortcutCaption = buildCaption(context, LocaleController.getString(R.string.JokerQuickReplyShortcutCaption));
        content.addView(shortcutCaption, createCaptionParams());

        TextView textLabel = buildSectionLabel(context, LocaleController.getString(R.string.JokerQuickReplyTextLabel));
        content.addView(textLabel, createLabelParams());
        content.addView(buildTextCard(context), createCardParams());

        TextView mediaLabel = buildSectionLabel(context, LocaleController.getString(R.string.JokerQuickReplyMediaLabel));
        content.addView(mediaLabel, createLabelParams());
        content.addView(buildMediaAttachCard(context), createCardParams());
        content.addView(buildMediaPreviewCard(context), createCardParams());

        errorView = new TextView(context);
        errorView.setTextColor(Color.parseColor("#E53935"));
        errorView.setTextSize(13f);
        errorView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        errorView.setPadding(AndroidUtilities.dp(2), AndroidUtilities.dp(4), AndroidUtilities.dp(2), AndroidUtilities.dp(4));
        errorView.setVisibility(View.GONE);
        content.addView(errorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 2, 10, 2));

        content.addView(buildSaveButton(context));
        if (!creatingNew) {
            content.addView(buildDeleteButton(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 16, 10, 16, 0));
        }

        scroll.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        frameLayout.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        frameLayout.addView(simpleActionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.TOP));

        populateFromDraft(context);
        updateSaveState();
        return fragmentView;
    }

    private LinearLayout createSectionCard(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(12), 0);
        card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16), Theme.getColor(Theme.key_windowBackgroundWhite)));
        card.setClipChildren(false);
        card.setClipToPadding(false);
        return card;
    }

    private LinearLayout.LayoutParams createCardParams() {
        return LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 16, 4, 16, 0);
    }

    private LinearLayout.LayoutParams createLabelParams() {
        return LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 18, 14, 18, 2);
    }

    private LinearLayout.LayoutParams createCaptionParams() {
        return LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 18, 4, 18, 0);
    }

    private TextView buildSectionLabel(Context context, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(13f);
        label.setTypeface(AndroidUtilities.bold());
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        label.setLetterSpacing(0.06f);
        return label;
    }

    private TextView buildCaption(Context context, String text) {
        TextView caption = new TextView(context);
        caption.setText(text);
        caption.setTextSize(12f);
        caption.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        caption.setPadding(0, 0, 0, 0);
        return caption;
    }

    private LinearLayout buildShortcutRow(Context context) {
        LinearLayout card = createSectionCard(context);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(12), 0);

        TextView slash = new TextView(context);
        slash.setText("/");
        slash.setTextSize(22f);
        slash.setTypeface(AndroidUtilities.bold());
        slash.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        card.addView(slash, LayoutHelper.createLinear(20, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

        shortcutField = new android.widget.EditText(context);
        shortcutField.setBackground(null);
        shortcutField.setHint(LocaleController.getString(R.string.JokerQuickReplyShortcutHint));
        shortcutField.setSingleLine(true);
        shortcutField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        shortcutField.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        shortcutField.setTextSize(17f);
        shortcutField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        shortcutField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        shortcutField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        shortcutField.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16));
        shortcutField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                textField.requestFocus();
                return true;
            }
            return false;
        });
        shortcutField.addTextChangedListener(simpleWatcher());
        card.addView(shortcutField, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        return card;
    }

    private LinearLayout buildTextCard(Context context) {
        LinearLayout card = createSectionCard(context);
        card.setPadding(0, 0, 0, 0);
        textField = new android.widget.EditText(context);
        textField.setBackground(null);
        textField.setHint(LocaleController.getString(R.string.JokerQuickReplyTextHint));
        textField.setGravity(Gravity.TOP | Gravity.START);
        textField.setMinLines(4);
        textField.setMaxLines(10);
        textField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        textField.setImeOptions(EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        textField.setTextSize(16f);
        textField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        textField.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        textField.addTextChangedListener(simpleWatcher());
        card.addView(textField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return card;
    }

    private LinearLayout buildMediaAttachCard(Context context) {
        attachCard = createSectionCard(context);
        attachCard.setClickable(true);
        attachCard.setFocusable(true);
        attachCard.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14));
        attachCard.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                Theme.createRoundRectDrawable(AndroidUtilities.dp(16), Theme.getColor(Theme.key_windowBackgroundWhite)),
                Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 7, AndroidUtilities.dp(16))
        }));

        FrameLayout iconBox = new FrameLayout(context);
        iconBox.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), Theme.getColor(Theme.key_featuredStickers_addButton)));
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.msg_gallery);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        iconBox.addView(icon, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
        attachCard.addView(iconBox, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 0, 0, 14));

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        attachLabel = new TextView(context);
        attachLabel.setText(LocaleController.getString(R.string.JokerQuickReplyAttach));
        attachLabel.setTextSize(16f);
        attachLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textColumn.addView(attachLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        attachHint = new TextView(context);
        attachHint.setText(LocaleController.getString(R.string.JokerQuickReplyAttachHint));
        attachHint.setTextSize(12f);
        attachHint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        textColumn.addView(attachHint, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        attachCard.addView(textColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        attachCard.setOnClickListener(v -> pickMedia());
        return attachCard;
    }

    private LinearLayout buildMediaPreviewCard(Context context) {
        mediaCard = createSectionCard(context);
        mediaCard.setVisibility(View.GONE);
        mediaCard.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12));

        FrameLayout thumbWrap = new FrameLayout(context);
        thumbWrap.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), Color.BLACK));
        thumbWrap.setClipToOutline(true);
        mediaThumbnail = new ImageView(context);
        mediaThumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbWrap.addView(mediaThumbnail, LayoutHelper.createFrame(84, 84, Gravity.CENTER));
        mediaCard.addView(thumbWrap, LayoutHelper.createLinear(84, 84, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        mediaTypeLabel = new TextView(context);
        mediaTypeLabel.setTextSize(16f);
        mediaTypeLabel.setTypeface(AndroidUtilities.bold());
        mediaTypeLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textColumn.addView(mediaTypeLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        mediaSizeLabel = new TextView(context);
        mediaSizeLabel.setTextSize(13f);
        mediaSizeLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        textColumn.addView(mediaSizeLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        mediaCard.addView(textColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        FrameLayout removeBox = new FrameLayout(context);
        removeBox.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        ImageView remove = new ImageView(context);
        remove.setImageResource(R.drawable.ic_talogram_remove);
        remove.setScaleType(ImageView.ScaleType.CENTER);
        remove.setColorFilter(new PorterDuffColorFilter(Color.parseColor("#E53935"), PorterDuff.Mode.SRC_IN));
        removeBox.addView(remove, LayoutHelper.createFrame(40, 40, Gravity.CENTER));
        removeBox.setContentDescription(LocaleController.getString(R.string.JokerQuickReplyDelete));
        removeBox.setOnClickListener(v -> removeMedia());
        mediaCard.addView(removeBox, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));
        return mediaCard;
    }

    private TextView buildSaveButton(Context context) {
        saveButton = new TextView(context);
        saveButton.setText(LocaleController.getString(R.string.JokerQuickReplySave));
        saveButton.setTextColor(Color.WHITE);
        saveButton.setTextSize(16f);
        saveButton.setTypeface(AndroidUtilities.bold());
        saveButton.setGravity(Gravity.CENTER);
        saveButton.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16));
        saveButton.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(26), Theme.getColor(Theme.key_featuredStickers_addButton)));
        saveButton.setOnClickListener(v -> save());
        saveButton.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 16, 0, 16, 0));
        return saveButton;
    }

    private TextView buildDeleteButton(Context context) {
        deleteButton = new TextView(context);
        deleteButton.setText(LocaleController.getString(R.string.JokerQuickReplyDelete));
        deleteButton.setTextColor(Color.parseColor("#E53935"));
        deleteButton.setTextSize(16f);
        deleteButton.setTypeface(AndroidUtilities.bold());
        deleteButton.setGravity(Gravity.CENTER);
        deleteButton.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14));
        deleteButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
        deleteButton.setOnClickListener(v -> confirmDelete());
        return deleteButton;
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
        if (mediaCard == null || attachCard == null) {
            return;
        }
        attachCard.setVisibility(hasMedia ? View.GONE : View.VISIBLE);
        mediaCard.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
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
