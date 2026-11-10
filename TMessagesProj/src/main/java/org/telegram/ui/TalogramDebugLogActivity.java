/*
 * Talogram mod - shows the in-app error log so crashes and request
 * failures can be read and copied off the device without adb/logcat.
 */

package org.telegram.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.TalogramLog;

import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Locale;

public class TalogramDebugLogActivity extends BaseFragment {

    private ScrollView scrollView;
    private TextView logView;
    private org.telegram.ui.Components.Switch toastToggle;
    private TextView emptyView;
    private TextView sizeView;

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(LocaleController.getString(R.string.JokerDebugLog));
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

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        content.addView(createToggleRow(context));
        content.addView(createInfoCell(context));

        logView = new TextView(context);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        logView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        logView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        logView.setTextIsSelectable(true);
        logView.setText(loadLog());
        scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setPadding(0, AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight(), 0, AndroidUtilities.navigationBarHeight);
        scrollView.addView(logView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout contentArea = new FrameLayout(context);
        emptyView = new TextView(context);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        emptyView.setTextSize(15);
        emptyView.setPadding(AndroidUtilities.dp(32), AndroidUtilities.dp(24), AndroidUtilities.dp(32), AndroidUtilities.dp(24));
        emptyView.setText(LocaleController.getString(R.string.JokerLogEmpty));
        emptyView.setVisibility(TextUtils.isEmpty(loadLog()) ? View.VISIBLE : View.GONE);
        contentArea.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        contentArea.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        content.addView(contentArea, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        sizeView = new TextView(context);
        sizeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        sizeView.setTextSize(12);
        sizeView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        sizeView.setText(sizeLabel());
        content.addView(sizeView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        content.addView(createButtonsRow(context));
        frameLayout.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        frameLayout.addView(simpleActionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.TOP));
        return fragmentView;
    }

    private View createToggleRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));

        TextView title = new TextView(context);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setTextSize(15);
        title.setText(LocaleController.getString(R.string.JokerErrorsToggle));
        row.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        toastToggle = new org.telegram.ui.Components.Switch(context);
        toastToggle.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
        toastToggle.setChecked(TalogramLog.isToastEnabled(), false);
        toastToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            TalogramLog.setToastEnabled(isChecked);
            android.widget.Toast.makeText(context,
                    LocaleController.getString(isChecked ? R.string.JokerErrorsToastOn : R.string.JokerErrorsToastOff),
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        row.addView(toastToggle, LayoutHelper.createLinear(37, 20, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        return row;
    }

    private View createInfoCell(Context context) {
        TextView info = new TextView(context);
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        info.setTextSize(13);
        info.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        info.setText(LocaleController.getString(R.string.JokerDebugLogInfo));
        return info;
    }

    private View createButtonsRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(12));

        TextView copyButton = createButton(context, LocaleController.getString(R.string.JokerCopyLog), true);
        copyButton.setOnClickListener(v -> {
            String text = TalogramLog.readAll();
            if (TextUtils.isEmpty(text)) {
                Toast.makeText(context, LocaleController.getString(R.string.JokerLogEmpty), Toast.LENGTH_SHORT).show();
                return;
            }
            if (AndroidUtilities.addToClipboard(text)) {
                Toast.makeText(context, LocaleController.getString(R.string.JokerLogCopied), Toast.LENGTH_LONG).show();
            }
        });
        row.addView(copyButton, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        TextView clearButton = createButton(context, LocaleController.getString(R.string.JokerClearLog), false);
        clearButton.setOnClickListener(v -> {
            TalogramLog.clear();
            logView.setText(loadLog());
            emptyView.setVisibility(TextUtils.isEmpty(loadLog()) ? View.VISIBLE : View.GONE);
            sizeView.setText(sizeLabel());
            Toast.makeText(context, LocaleController.getString(R.string.JokerLogCleared), Toast.LENGTH_SHORT).show();
        });
        row.addView(clearButton, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));
        return row;
    }

    private TextView createButton(Context context, String label, boolean filled) {
        TextView button = new TextView(context);
        button.setText(label);
        button.setTextSize(15);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        if (filled) {
            button.setTextColor(0xFFFFFFFF);
            button.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(22), Theme.getColor(Theme.key_featuredStickers_addButton)));
        } else {
            button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            button.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
        }
        return button;
    }

    private String loadLog() {
        String text = TalogramLog.readAll();
        return text == null ? "" : text;
    }

    private String sizeLabel() {
        long bytes = TalogramLog.size();
        if (bytes < 1024) {
            return bytes + " B";
        }
        return String.format(Locale.US, "%.1f KB", bytes / 1024f);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (logView != null) {
            logView.setText(loadLog());
        }
        if (emptyView != null) {
            emptyView.setVisibility(TextUtils.isEmpty(loadLog()) ? View.VISIBLE : View.GONE);
        }
        if (sizeView != null) {
            sizeView.setText(sizeLabel());
        }
    }
}
