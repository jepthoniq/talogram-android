/*
 * Talogram mod - local quick replies pane inside the attachment menu.
 * Full-height attach-alert panel built in Telegram style.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Color;
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
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.TalogramQuickReplySender;
import org.telegram.messenger.TalogramSettings;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.TalogramQuickReplyEditorActivity;

import java.util.ArrayList;
import java.util.List;

public class TalogramQuickRepliesLayout extends ChatAttachAlert.AttachAlertLayout {

    private static final int ROW_HEIGHT = 64;
    private static final int VIEW_TYPE_CREATE = 0;
    private static final int VIEW_TYPE_REPLY = 1;

    private RecyclerListView listView;
    private FillLastLinearLayoutManager layoutManager;
    private FrameLayout emptyView;
    private ReplyAdapter adapter;

    public TalogramQuickRepliesLayout(ChatAttachAlert alert, Context context, Theme.ResourcesProvider resourcesProvider) {
        super(alert, context, resourcesProvider);

        occupyStatusBar = true;
        occupyNavigationBar = true;

        listView = new RecyclerListView(context);
        listView.setClipToPadding(false);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(layoutManager = new FillLastLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false, AndroidUtilities.dp(9), listView) {
            @Override
            public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {
                LinearSmoothScroller scroller = new LinearSmoothScroller(recyclerView.getContext()) {
                    @Override
                    public int calculateDyToMakeVisible(View view, int snapPreference) {
                        int dy = super.calculateDyToMakeVisible(view, snapPreference);
                        dy -= listView.getPaddingTop() - AndroidUtilities.statusBarHeight - AndroidUtilities.dp(8);
                        return dy;
                    }

                    @Override
                    protected int calculateTimeForDeceleration(int dx) {
                        return super.calculateTimeForDeceleration(dx) * 2;
                    }
                };
                scroller.setTargetPosition(position);
                startSmoothScroll(scroller);
            }
        });
        layoutManager.setBind(false);
        listView.setAdapter(adapter = new ReplyAdapter());
        listView.setOnItemClickListener((view, position) -> {
            if (position == 0) {
                openEditor();
                return;
            }
            TalogramSettings.QuickReply reply = adapter.getItem(position);
            if (reply == null) {
                return;
            }
            TalogramQuickReplySender.send(parentAlert.currentAccount, parentAlert.getDialogId(), reply);
            parentAlert.dismiss();
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                parentAlert.updateLayout(TalogramQuickRepliesLayout.this, true, dy);
                updateEmptyViewPosition();
            }
        });
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new FrameLayout(context);
        emptyView.setVisibility(GONE);
        LinearLayout emptyLayout = new LinearLayout(context);
        emptyLayout.setOrientation(LinearLayout.VERTICAL);
        emptyLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyLayout.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);

        ImageView emptyIcon = new ImageView(context);
        emptyIcon.setImageResource(R.drawable.settings_talogram);
        emptyIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        emptyIcon.setAlpha(0.9f);
        emptyLayout.addView(emptyIcon, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL));

        TextView emptyTitle = new TextView(context);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        emptyTitle.setTextSize(16);
        emptyTitle.setTypeface(AndroidUtilities.bold());
        emptyTitle.setText(LocaleController.getString(R.string.JokerQuickReplyEmptyTitle));
        emptyLayout.addView(emptyTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 14, 0, 0));

        TextView emptyText = new TextView(context);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        emptyText.setTextSize(14);
        emptyText.setText(LocaleController.getString(R.string.JokerQuickReplyEmptyText));
        emptyLayout.addView(emptyText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 6, 0, 0));

        emptyView.addView(emptyLayout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        refresh();
    }

    public void refresh() {
        if (adapter == null) {
            return;
        }
        adapter.setItems(TalogramSettings.getQuickReplies());
        adapter.notifyDataSetChanged();
        updateEmptyView();
    }

    private void openEditor() {
        parentAlert.dismiss();
        if (parentAlert.baseFragment != null) {
            parentAlert.baseFragment.presentFragment(new TalogramQuickReplyEditorActivity(null));
        }
    }

    private void updateEmptyView() {
        if (emptyView != null) {
            emptyView.setVisibility(adapter.getItemCount() <= 1 ? VISIBLE : GONE);
        }
        updateEmptyViewPosition();
    }

    private void updateEmptyViewPosition() {
        if (emptyView == null || emptyView.getVisibility() != VISIBLE || listView == null) {
            return;
        }
        emptyView.setTranslationY((listView.getPaddingTop() + AndroidUtilities.dp(ROW_HEIGHT)) / 2f);
    }

    @Override
    public boolean shouldRestoreKeyboardOnDismiss() {
        return false;
    }

    @Override
    public void onShow(ChatAttachAlert.AttachAlertLayout previousLayout) {
        refresh();
        if (layoutManager != null) {
            layoutManager.scrollToPositionWithOffset(0, 0);
        }
    }

    @Override
    public void scrollToTop() {
        if (listView != null) {
            listView.smoothScrollToPosition(0);
        }
    }

    @Override
    public int getCurrentItemTop() {
        if (listView == null || listView.getChildCount() <= 0) {
            return Integer.MAX_VALUE;
        }
        View child = listView.getChildAt(0);
        RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findContainingViewHolder(child);
        int top = child.getTop() - AndroidUtilities.statusBarHeight - AndroidUtilities.dp(8);
        return (top > 0 && holder != null && holder.getAdapterPosition() == 0 ? top : 0) + AndroidUtilities.dp(12);
    }

    @Override
    public int getFirstOffset() {
        return getListTopPadding() + AndroidUtilities.dp(4);
    }

    @Override
    public void setTranslationY(float translationY) {
        super.setTranslationY(translationY);
        parentAlert.getSheetContainer().invalidate();
    }

    @Override
    public int getListTopPadding() {
        return listView == null ? 0 : listView.getPaddingTop();
    }

    @Override
    public void onPreMeasure(int availableWidth, int availableHeight) {
        int padding;
        if (parentAlert.sizeNotifierFrameLayout.measureKeyboardHeight() > AndroidUtilities.dp(20)) {
            padding = AndroidUtilities.dp(8);
            parentAlert.setAllowNestedScroll(false);
        } else {
            if (!AndroidUtilities.isTablet() && AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) {
                padding = (int) (availableHeight / 3.5f);
            } else {
                padding = (availableHeight / 5 * 2);
            }
            parentAlert.setAllowNestedScroll(true);
        }
        padding += AndroidUtilities.statusBarHeight;
        listView.setPaddingWithoutRequestLayout(0, padding, 0, listPaddingBottom);
        updateEmptyViewPosition();
    }

    private abstract class BaseCell extends FrameLayout {

        protected final LinearLayout content;

        private BaseCell(Context context) {
            super(context);
            content = new LinearLayout(context);
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
            content.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 2));
            addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }

        protected FrameLayout createIconBox(Context context, int iconRes) {
            FrameLayout iconBox = new FrameLayout(context);
            iconBox.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)));
            ImageView iconView = new ImageView(context);
            iconView.setImageResource(iconRes);
            iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iconView.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            iconBox.addView(iconView, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
            return iconBox;
        }

        protected void addDivider() {
            View divider = new View(getContext());
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider, resourcesProvider));
            addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1, Gravity.BOTTOM | Gravity.LEFT, 72, 0, 0, 0));
        }
    }

    private class CreateCell extends BaseCell {

        private CreateCell(Context context) {
            super(context);
            content.addView(createIconBox(context, R.drawable.msg_add), LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL));

            TextView titleView = new TextView(context);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2, resourcesProvider));
            titleView.setTextSize(15);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setMaxLines(1);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setText(LocaleController.getString(R.string.JokerQuickReplyNew));
            content.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

            addDivider();
        }
    }

    private class ReplyCell extends BaseCell {

        private final ImageView iconView;
        private final TextView shortcutView;
        private final TextView previewView;

        private ReplyCell(Context context) {
            super(context);
            FrameLayout iconBox = new FrameLayout(context);
            iconBox.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)));
            iconView = new ImageView(context);
            iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iconView.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            iconBox.addView(iconView, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
            content.addView(iconBox, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL));

            LinearLayout textLayout = new LinearLayout(context);
            textLayout.setOrientation(LinearLayout.VERTICAL);

            shortcutView = new TextView(context);
            shortcutView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            shortcutView.setTextSize(15);
            shortcutView.setTypeface(AndroidUtilities.bold());
            shortcutView.setMaxLines(1);
            shortcutView.setEllipsize(TextUtils.TruncateAt.END);
            textLayout.addView(shortcutView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            previewView = new TextView(context);
            previewView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
            previewView.setTextSize(13.5f);
            previewView.setMaxLines(1);
            previewView.setEllipsize(TextUtils.TruncateAt.END);
            textLayout.addView(previewView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            content.addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

            addDivider();
        }

        private void bind(TalogramSettings.QuickReply reply) {
            shortcutView.setText("/" + reply.shortcut);
            String preview = reply.text == null ? "" : reply.text.trim();
            if (preview.isEmpty() && reply.hasMedia()) {
                preview = reply.mediaType == TalogramSettings.MEDIA_TYPE_VIDEO
                        ? LocaleController.getString(R.string.JokerQuickReplyMediaVideo)
                        : LocaleController.getString(R.string.JokerQuickReplyMediaImage);
            }
            previewView.setText(preview);
            previewView.setVisibility(TextUtils.isEmpty(preview) ? GONE : VISIBLE);
            if (reply.hasMedia()) {
                iconView.setImageResource(reply.mediaType == TalogramSettings.MEDIA_TYPE_VIDEO ? R.drawable.msg_video : R.drawable.msg_gallery);
            } else {
                iconView.setImageResource(R.drawable.msg_message);
            }
        }
    }

    private class ReplyAdapter extends RecyclerListView.SelectionAdapter {

        private final List<TalogramSettings.QuickReply> items = new ArrayList<>();

        private void setItems(List<TalogramSettings.QuickReply> list) {
            items.clear();
            if (list != null) {
                items.addAll(list);
            }
        }

        private TalogramSettings.QuickReply getItem(int position) {
            int index = position - 1;
            if (index < 0 || index >= items.size()) {
                return null;
            }
            return items.get(index);
        }

        @Override
        public int getItemCount() {
            return items.size() + 1;
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? VIEW_TYPE_CREATE : VIEW_TYPE_REPLY;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            BaseCell cell = viewType == VIEW_TYPE_CREATE ? new CreateCell(parent.getContext()) : new ReplyCell(parent.getContext());
            cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(ROW_HEIGHT)));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == VIEW_TYPE_REPLY) {
                ((ReplyCell) holder.itemView).bind(items.get(position - 1));
            }
        }
    }
}
