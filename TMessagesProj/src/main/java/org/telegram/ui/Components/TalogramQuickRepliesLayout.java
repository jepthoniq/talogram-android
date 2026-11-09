/*
 * Talogram mod - local quick replies pane inside the attachment menu.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.TalogramQuickReplySender;
import org.telegram.messenger.TalogramSettings;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.List;

public class TalogramQuickRepliesLayout extends ChatAttachAlert.AttachAlertLayout {

    private static final int ROW_HEIGHT = 56;

    private RecyclerListView listView;
    private TextView emptyView;
    private ReplyAdapter adapter;

    public TalogramQuickRepliesLayout(ChatAttachAlert alert, Context context, Theme.ResourcesProvider resourcesProvider) {
        super(alert, context, resourcesProvider);

        FrameLayout container = new FrameLayout(context);

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ReplyAdapter(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setOnItemClickListener((view, position) -> {
            TalogramSettings.QuickReply reply = adapter.getItem(position);
            if (reply == null) {
                return;
            }
            TalogramQuickReplySender.send(UserConfig.selectedAccount, parentAlert.getDialogId(), reply);
            parentAlert.dismiss();
        });
        emptyView = new TextView(context);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        emptyView.setTextSize(14);
        emptyView.setText(LocaleController.getString(R.string.JokerQuickReplyEmptyTitle));
        container.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setEmptyView(emptyView);
        container.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        addView(container, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(ROW_HEIGHT * 5), Gravity.CENTER));

        setWillNotDraw(false);
        refresh();
    }

    public void refresh() {
        if (adapter == null) {
            return;
        }
        adapter.setItems(TalogramSettings.getQuickReplies());
        adapter.notifyDataSetChanged();
    }

    @Override
    public boolean shouldRestoreKeyboardOnDismiss() {
        return false;
    }

    private class ReplyAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;
        private List<TalogramSettings.QuickReply> items = new java.util.ArrayList<>();

        private ReplyAdapter(Context context) {
            this.context = context;
        }

        public void setItems(List<TalogramSettings.QuickReply> list) {
            items = list;
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
            ReplyCell cell = new ReplyCell(context);
            cell.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    AndroidUtilities.dp(ROW_HEIGHT)
            ));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((ReplyCell) holder.itemView).bind(items.get(position));
        }
    }

    private class ReplyCell extends LinearLayout {
        private final TextView shortcutView;
        private final TextView textView;
        private final ImageView mediaIconView;

        private ReplyCell(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int padding = AndroidUtilities.dp(16);
            setPadding(padding, 0, padding, 0);
            setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));

            shortcutView = new TextView(context);
            shortcutView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            shortcutView.setTextSize(15);
            shortcutView.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
            shortcutView.setMaxLines(1);
            shortcutView.setEllipsize(TextUtils.TruncateAt.END);
            addView(shortcutView, LayoutHelper.createLinear(120, LayoutHelper.WRAP_CONTENT, 0, Gravity.CENTER_VERTICAL));

            textView = new TextView(context);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            textView.setTextSize(14);
            textView.setMaxLines(1);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            addView(textView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

            mediaIconView = new ImageView(context);
            mediaIconView.setImageResource(R.drawable.ic_talogram_play);
            mediaIconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            mediaIconView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
            mediaIconView.setVisibility(GONE);
            addView(mediaIconView, LayoutHelper.createLinear(32, 32, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
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
            mediaIconView.setVisibility(reply.hasMedia() ? VISIBLE : GONE);
        }
    }
}
