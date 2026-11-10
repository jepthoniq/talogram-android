/*
 * Talogram mod - local quick replies manager. Mirrors the iOS local quick replies screen.
 */

package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.TalogramSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class TalogramQuickRepliesActivity extends BaseFragment {

    private RecyclerListView listView;
    private QuickReplyAdapter adapter;
    private LinearLayout emptyLayout;

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(LocaleController.getString(R.string.JokerQuickRepliesScreenTitle));
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ImageView addButton = new ImageView(context);
        addButton.setImageResource(R.drawable.ic_talogram_add);
        addButton.setScaleType(ImageView.ScaleType.CENTER);
        addButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
        addButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        addButton.setContentDescription(LocaleController.getString(R.string.JokerQuickReplyNew));
        addButton.setOnClickListener(v -> openEditor(null));
        simpleActionBar.addView(addButton, LayoutHelper.createFrame(40, 40, Gravity.TOP | Gravity.RIGHT, 0, 8, 8, 0));

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setSections();
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new QuickReplyAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT
        ));
        emptyLayout = createEmptyLayout(context);
        frameLayout.addView(emptyLayout, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT
        ));
        listView.setEmptyView(emptyLayout);
        listView.setOnItemClickListener((view, position) -> {
            TalogramSettings.QuickReply reply = adapter.getItem(position);
            if (reply != null) {
                openEditor(reply.id);
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            TalogramSettings.QuickReply reply = adapter.getItem(position);
            if (reply != null) {
                confirmDelete(reply);
            }
            return true;
        });
        refresh();
        return fragmentView;
    }

    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    private LinearLayout createEmptyLayout(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(AndroidUtilities.dp(32), AndroidUtilities.dp(32), AndroidUtilities.dp(32), AndroidUtilities.dp(32));

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.settings_talogram);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        layout.addView(icon, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL));

        TextView title = new TextView(context);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setTypeface(AndroidUtilities.bold());
        title.setTextSize(18);
        title.setText(LocaleController.getString(R.string.JokerQuickReplyEmptyTitle));
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        TextView subtitle = new TextView(context);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setText(LocaleController.getString(R.string.JokerQuickReplyEmptyText));
        layout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 6, 0, 0));

        TextView button = new TextView(context);
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        button.setTypeface(AndroidUtilities.bold());
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setText(LocaleController.getString(R.string.JokerQuickReplyNew));
        button.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(22), Theme.getColor(Theme.key_featuredStickers_addButton)));
        button.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(12), AndroidUtilities.dp(24), AndroidUtilities.dp(12));
        button.setOnClickListener(v -> openEditor(null));
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 0));
        layout.setVisibility(View.GONE);
        return layout;
    }

    private void openEditor(@Nullable String replyId) {
        presentFragment(new TalogramQuickReplyEditorActivity(replyId));
    }

    private void confirmDelete(TalogramSettings.QuickReply reply) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.JokerQuickReplyDeleteTitle));
        builder.setMessage(LocaleController.getString(R.string.JokerQuickReplyDeleteMessage));
        builder.setPositiveButton(LocaleController.getString(R.string.JokerQuickReplyDelete), (dialog, which) -> {
            TalogramSettings.deleteQuickReply(reply.id);
            refresh();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.JokerQuickReplyCancel), null);
        showDialog(builder.create());
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (adapter == null) {
            return;
        }
        adapter.setItems(TalogramSettings.getQuickReplies());
        adapter.notifyDataSetChanged();
    }

    private class QuickReplyAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;
        private final List<TalogramSettings.QuickReply> items = new ArrayList<>();

        private QuickReplyAdapter(Context context) {
            this.context = context;
        }

        public void setItems(List<TalogramSettings.QuickReply> list) {
            items.clear();
            items.addAll(list);
        }

        public TalogramSettings.QuickReply getItem(int position) {
            if (position < 0 || position >= items.size()) {
                return null;
            }
            return items.get(position);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            QuickReplyCell cell = new QuickReplyCell(context);
            cell.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
            ));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((QuickReplyCell) holder.itemView).bind(items.get(position));
        }
    }

    private class QuickReplyCell extends LinearLayout {
        private final ImageView mediaView;
        private final FrameLayout mediaIcon;
        private final TextView shortcutView;
        private final TextView textView;
        private final ImageView arrowView;

        private QuickReplyCell(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int padding = AndroidUtilities.dp(14);
            setPadding(padding, AndroidUtilities.dp(10), padding, AndroidUtilities.dp(10));
            setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));

            mediaIcon = new FrameLayout(context);
            mediaIcon.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), Theme.getColor(Theme.key_featuredStickers_addButton)));
            mediaIcon.setVisibility(GONE);
            mediaView = new ImageView(context);
            mediaView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mediaIcon.addView(mediaView, LayoutHelper.createFrame(48, 48, Gravity.CENTER));
            addView(mediaIcon, LayoutHelper.createLinear(48, 48, 0, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

            LinearLayout textLayout = new LinearLayout(context);
            textLayout.setOrientation(VERTICAL);

            shortcutView = new TextView(context);
            shortcutView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            shortcutView.setTextSize(16);
            shortcutView.setTypeface(AndroidUtilities.bold());
            shortcutView.setSingleLine(true);
            shortcutView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textLayout.addView(shortcutView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            textView = new TextView(context);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            textView.setTextSize(13);
            textView.setMaxLines(2);
            textView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textLayout.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            arrowView = new ImageView(context);
            arrowView.setImageResource(R.drawable.msg_arrowright);
            arrowView.setScaleType(ImageView.ScaleType.CENTER);
            arrowView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
            addView(arrowView, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        }

        private void bind(TalogramSettings.QuickReply reply) {
            shortcutView.setText("/" + reply.shortcut);
            String preview = reply.text == null ? "" : reply.text.trim();
            if (preview.isEmpty()) {
                if (reply.hasMedia()) {
                    preview = reply.mediaType == TalogramSettings.MEDIA_TYPE_VIDEO
                            ? LocaleController.getString(R.string.JokerQuickReplyMediaVideo)
                            : LocaleController.getString(R.string.JokerQuickReplyMediaImage);
                }
            }
            textView.setText(preview);
            if (reply.hasMedia()) {
                mediaIcon.setVisibility(VISIBLE);
                if (reply.mediaType == TalogramSettings.MEDIA_TYPE_PHOTO) {
                    Bitmap bmp = decodeThumb(reply.mediaFileName);
                    if (bmp != null) {
                        mediaView.setImageBitmap(bmp);
                        mediaView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        mediaIcon.setBackground(null);
                    } else {
                        setVideoPlaceholder();
                    }
                } else {
                    setVideoPlaceholder();
                }
            } else {
                mediaIcon.setVisibility(GONE);
            }
        }

        private void setVideoPlaceholder() {
            mediaView.setImageResource(R.drawable.ic_talogram_play);
            mediaView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            mediaIcon.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), Theme.getColor(Theme.key_featuredStickers_addButton)));
        }

        private Bitmap decodeThumb(String fileName) {
            try {
                File file = TalogramSettings.quickReplyMediaFile(fileName);
                if (file == null || !file.exists()) {
                    return null;
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                int sample = 1;
                int max = Math.max(bounds.outWidth, bounds.outHeight);
                while (max / (sample * 2) >= 128) {
                    sample *= 2;
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
