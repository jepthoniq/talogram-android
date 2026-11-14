/*
 * Talogram mod - settings screen. Mirrors the iOS Joker Settings screen.
 */

package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.TalogramSettings;
import org.telegram.messenger.TalogramSubscription;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class TalogramSettingsActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_VALUE = 2;
    private static final int TYPE_INFO = 3;

    private static final int ROW_FEATURES_HEADER = 0;
    private static final int ROW_AUTO_SAVE = 1;
    private static final int ROW_100_ACCOUNTS = 2;
    private static final int ROW_GHOST = 3;
    private static final int ROW_FORCE_TRANSLATION = 4;
    private static final int ROW_FREEZE_LS = 5;
    private static final int ROW_BYPASS_COPY = 6;
    private static final int ROW_KEEP_DELETED = 7;
    private static final int ROW_STORY_FREEZE = 8;
    private static final int ROW_CALL_RECORDING = 9;
    private static final int ROW_VOICE_TO_TEXT = 10;
    private static final int ROW_SECRET_CHAT_TOOLS = 11;
    private static final int ROW_LOCAL_HEADER = 12;
    private static final int ROW_QUICK_REPLIES = 13;
    private static final int ROW_DEBUG_LOG = 14;
    private static final int ROW_CREDITS_HEADER = 15;
    private static final int ROW_CREDITS = 16;
    private static final int ROW_COUNT = 17;

    private RecyclerListView listView;
    private ListAdapter adapter;

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(LocaleController.getString(R.string.JokerSettings));
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;
        refreshContent(context);
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Joker Mod: re-verify membership every time the screen appears. If the user left the
        // channel, the screen flips back to the "subscription required" view automatically.
        refreshContent(getContext());
    }

    private void refreshContent(Context context) {
        if (context == null || !(fragmentView instanceof FrameLayout)) {
            return;
        }
        FrameLayout root = (FrameLayout) fragmentView;
        root.removeAllViews();
        if (TalogramSubscription.isGateEnabled() && !TalogramSubscription.isSubscribedCached()) {
            // لا تُفتح القائمة إطلاقاً قبل الاشتراك في القناة: تظهر شاشة الاشتراك فقط.
            root.addView(createGateLayout(context));
            TalogramSubscription.checkSubscription(getCurrentAccount(), subscribed -> {
                if (subscribed) {
                    AndroidUtilities.runOnUIThread(() -> refreshContent(context));
                }
            });
            return;
        }
        if (this.listView == null) {
            this.listView = new RecyclerListView(context);
            this.listView.setSections();
            this.listView.setSectionsDrawBackground(true);
            this.listView.setLayoutManager(new LinearLayoutManager(context));
            this.listView.setVerticalScrollBarEnabled(false);
            this.listView.setAdapter(adapter = new ListAdapter(context));
            this.listView.setOnItemClickListener((view, position) -> onRowClicked(position));
        }
        root.addView(this.listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT
        ));
    }

    private LinearLayout createGateLayout(Context context) {
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
        title.setText(LocaleController.getString(R.string.JokerSubscribeTitle));
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        TextView info = new TextView(context);
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        info.setTextSize(14);
        info.setGravity(Gravity.CENTER);
        info.setText(LocaleController.getString(R.string.JokerSubscribeMessage));
        layout.addView(info, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        TextView joinButton = createGateButton(context, LocaleController.getString(R.string.JokerJoinChannel), true, () -> {
            try {
                org.telegram.messenger.browser.Browser.openUrl(getParentActivity(), TalogramSubscription.CHANNEL_LINK);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
        layout.addView(joinButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 0));

        TextView checkButton = createGateButton(context, LocaleController.getString(R.string.JokerCheckAgain), false, () -> {
            TalogramSubscription.checkSubscription(getCurrentAccount(), subscribed -> {
                AndroidUtilities.runOnUIThread(() -> {
                    refreshContent(context);
                    if (!subscribed) {
                        android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.JokerSubscriptionNotFound), android.widget.Toast.LENGTH_LONG).show();
                    }
                });
            });
        });
        layout.addView(checkButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));

        return layout;
    }

    private TextView createGateButton(Context context, CharSequence text, boolean primary, Runnable action) {
        TextView button = new TextView(context);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setText(text);
        if (primary) {
            button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
            button.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(22), Theme.getColor(Theme.key_featuredStickers_addButton)));
        } else {
            button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
            button.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(22), Theme.getColor(Theme.key_featuredStickers_addButton)));
            button.setAlpha(0.9f);
        }
        button.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(12), AndroidUtilities.dp(24), AndroidUtilities.dp(12));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Nullable
    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    private void onRowClicked(int position) {
        String key = null;
        switch (position) {
            case ROW_AUTO_SAVE:
                key = TalogramSettings.KEY_AUTO_SAVE_MEDIA;
                break;
            case ROW_100_ACCOUNTS:
                key = TalogramSettings.KEY_ALLOW_100_ACCOUNTS;
                break;
            case ROW_GHOST:
                key = TalogramSettings.KEY_GHOST_MODE;
                break;
            case ROW_FORCE_TRANSLATION:
                key = TalogramSettings.KEY_FORCE_PREMIUM_TRANSLATION;
                break;
            case ROW_FREEZE_LS:
                key = TalogramSettings.KEY_FREEZE_LAST_SEEN;
                break;
            case ROW_BYPASS_COPY:
                key = TalogramSettings.KEY_BYPASS_COPY_PROTECTION;
                break;
            case ROW_KEEP_DELETED:
                key = TalogramSettings.KEY_KEEP_DELETED;
                break;
            case ROW_STORY_FREEZE:
                key = TalogramSettings.KEY_STORY_FREEZE;
                break;
            case ROW_CALL_RECORDING:
                key = TalogramSettings.KEY_CALL_RECORDING;
                break;
            case ROW_VOICE_TO_TEXT:
                key = TalogramSettings.KEY_VOICE_TO_TEXT;
                break;
            case ROW_SECRET_CHAT_TOOLS:
                key = TalogramSettings.KEY_SECRET_CHAT_TOOLS;
                break;
            default:
                break;
        }
        if (key != null) {
            boolean defaultValue = key != null && (key.equals(TalogramSettings.KEY_VOICE_TO_TEXT) || key.equals(TalogramSettings.KEY_SECRET_CHAT_TOOLS) || key.equals(TalogramSettings.KEY_CALL_RECORDING));
            TalogramSettings.setBoolean(key, !TalogramSettings.getBoolean(key, defaultValue));
            // Joker Mod: start fetching the speech model as soon as the feature
            // is enabled, so the first voice message does not have to wait for
            // a 100 MB download.
            if (TalogramSettings.KEY_VOICE_TO_TEXT.equals(key) && TalogramSettings.getBoolean(key, true)) {
                org.telegram.messenger.TalogramVoiceTranscriber.preloadModel();
            }
            adapter.notifyItemChanged(position);
            return;
        }
        if (position == ROW_QUICK_REPLIES) {
            presentFragment(new TalogramQuickRepliesActivity());
            return;
        }
        if (position == ROW_DEBUG_LOG) {
            presentFragment(new TalogramDebugLogActivity());
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        private ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position >= ROW_FEATURES_HEADER && position <= ROW_SECRET_CHAT_TOOLS) {
                return position != ROW_FEATURES_HEADER;
            }
            return position == ROW_QUICK_REPLIES || position == ROW_DEBUG_LOG;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_HEADER) {
                view = new HeaderCell(context);
            } else if (viewType == TYPE_CHECK) {
                view = new TextCheckCell(context);
            } else if (viewType == TYPE_VALUE) {
                TextSettingsCell cell = new TextSettingsCell(context);
                cell.setCanDisable(true);
                view = cell;
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
            ));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == TYPE_HEADER) {
                HeaderCell cell = (HeaderCell) holder.itemView;
                int resId;
                switch (position) {
                    case ROW_LOCAL_HEADER:
                        resId = R.string.JokerLocalFeaturesHeader;
                        break;
                    case ROW_CREDITS_HEADER:
                        resId = R.string.JokerSourceCreditsHeader;
                        break;
                    default:
                        resId = R.string.JokerFeaturesHeader;
                        break;
                }
                cell.setText(LocaleController.getString(resId));
            } else if (holder.getItemViewType() == TYPE_CHECK) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                switch (position) {
                    case ROW_AUTO_SAVE:
                        cell.setTextAndValueAndCheck(LocaleController.getString(R.string.JokerAutoSaveSelfDestructTitle), LocaleController.getString(R.string.JokerAutoSaveSelfDestructText), TalogramSettings.getBoolean(TalogramSettings.KEY_AUTO_SAVE_MEDIA), true, true);
                        break;
                    case ROW_100_ACCOUNTS:
                        cell.setTextAndValueAndCheck(LocaleController.getString(R.string.JokerAllow100AccountsTitle), LocaleController.getString(R.string.JokerAllow100AccountsText), TalogramSettings.getBoolean(TalogramSettings.KEY_ALLOW_100_ACCOUNTS), true, true);
                        break;
                    case ROW_GHOST:
                        cell.setTextAndValueAndCheck(LocaleController.getString(R.string.JokerGhostModeTitle), LocaleController.getString(R.string.JokerGhostModeText), TalogramSettings.getBoolean(TalogramSettings.KEY_GHOST_MODE), true, true);
                        break;
                    case ROW_FORCE_TRANSLATION:
                        cell.setTextAndValueAndCheck(LocaleController.getString(R.string.JokerForcePremiumTranslationTitle), LocaleController.getString(R.string.JokerForcePremiumTranslationText), TalogramSettings.getBoolean(TalogramSettings.KEY_FORCE_PREMIUM_TRANSLATION), true, true);
                        break;
                    case ROW_FREEZE_LS:
                        cell.setTextAndValueAndCheck(LocaleController.getString(R.string.JokerFreezeLastSeenTitle), LocaleController.getString(R.string.JokerFreezeLastSeenText), TalogramSettings.getBoolean(TalogramSettings.KEY_FREEZE_LAST_SEEN), true, true);
                        break;
                    case ROW_BYPASS_COPY:
                        cell.setTextAndValueAndCheck(LocaleController.getString(R.string.JokerBypassCopyProtectionTitle), LocaleController.getString(R.string.JokerBypassCopyProtectionText), TalogramSettings.getBoolean(TalogramSettings.KEY_BYPASS_COPY_PROTECTION), true, true);
                        break;
                    case ROW_KEEP_DELETED:
                        cell.setTextAndValueAndCheck(LocaleController.getString(R.string.JokerKeepDeletedMessagesTitle), LocaleController.getString(R.string.JokerKeepDeletedMessagesText), TalogramSettings.getBoolean(TalogramSettings.KEY_KEEP_DELETED), true, true);
                        break;
                    case ROW_CALL_RECORDING:
                        cell.setTextAndValueAndCheck(LocaleController.getString(R.string.JokerCallRecordingTitle), LocaleController.getString(R.string.JokerCallRecordingText), TalogramSettings.getBoolean(TalogramSettings.KEY_CALL_RECORDING, true), true, true);
                        break;
                    case ROW_STORY_FREEZE:
                        cell.setTextAndValueAndCheck(LocaleController.getString(R.string.JokerFreezeStoryViewsTitle), LocaleController.getString(R.string.JokerFreezeStoryViewsText), TalogramSettings.getBoolean(TalogramSettings.KEY_STORY_FREEZE), true, true);
                        break;
                    case ROW_VOICE_TO_TEXT:
                        cell.setTextAndValueAndCheck(LocaleController.getString(R.string.JokerVoiceToTextTitle), LocaleController.getString(R.string.JokerVoiceToTextText), TalogramSettings.getBoolean(TalogramSettings.KEY_VOICE_TO_TEXT, true), true, true);
                        break;
                    case ROW_SECRET_CHAT_TOOLS:
                        cell.setTextAndValueAndCheck(LocaleController.getString(R.string.JokerSecretChatToolsTitle), LocaleController.getString(R.string.JokerSecretChatToolsText), TalogramSettings.getBoolean(TalogramSettings.KEY_SECRET_CHAT_TOOLS, true), true, true);
                        break;
                }
            } else if (holder.getItemViewType() == TYPE_VALUE) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                if (position == ROW_DEBUG_LOG) {
                    cell.setTextAndValue(LocaleController.getString(R.string.JokerDebugLog), null, true);
                } else {
                    cell.setTextAndValue(LocaleController.getString(R.string.JokerQuickRepliesTitle), String.valueOf(TalogramSettings.getQuickRepliesCount()), true);
                }
            } else {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText(LocaleController.getString(R.string.JokerCreditsText));
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_FEATURES_HEADER || position == ROW_LOCAL_HEADER || position == ROW_CREDITS_HEADER) {
                return TYPE_HEADER;
            }
            if (position == ROW_QUICK_REPLIES || position == ROW_DEBUG_LOG) {
                return TYPE_VALUE;
            }
            if (position == ROW_CREDITS) {
                return TYPE_INFO;
            }
            return TYPE_CHECK;
        }
    }
}
