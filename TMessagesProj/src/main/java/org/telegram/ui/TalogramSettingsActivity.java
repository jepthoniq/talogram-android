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
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

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
    private static final int ROW_LOCAL_HEADER = 9;
    private static final int ROW_QUICK_REPLIES = 10;
    private static final int ROW_CREDITS_HEADER = 11;
    private static final int ROW_CREDITS = 12;
    private static final int ROW_COUNT = 13;

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

        listView = new RecyclerListView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> onRowClicked(position));
        frameLayout.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT
        ));
        return fragmentView;
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
            default:
                break;
        }
        if (key != null) {
            TalogramSettings.setBoolean(key, !TalogramSettings.getBoolean(key));
            adapter.notifyItemChanged(position);
            return;
        }
        if (position == ROW_QUICK_REPLIES) {
            presentFragment(new TalogramQuickRepliesActivity());
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
            if (position >= ROW_FEATURES_HEADER && position <= ROW_STORY_FREEZE) {
                return position != ROW_FEATURES_HEADER;
            }
            return position == ROW_QUICK_REPLIES;
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
                    case ROW_STORY_FREEZE:
                        cell.setTextAndValueAndCheck(LocaleController.getString(R.string.JokerFreezeStoryViewsTitle), LocaleController.getString(R.string.JokerFreezeStoryViewsText), TalogramSettings.getBoolean(TalogramSettings.KEY_STORY_FREEZE), true, true);
                        break;
                }
            } else if (holder.getItemViewType() == TYPE_VALUE) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue(LocaleController.getString(R.string.JokerQuickRepliesTitle), String.valueOf(TalogramSettings.getQuickRepliesCount()), true);
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
            if (position == ROW_QUICK_REPLIES) {
                return TYPE_VALUE;
            }
            if (position == ROW_CREDITS) {
                return TYPE_INFO;
            }
            return TYPE_CHECK;
        }
    }
}
