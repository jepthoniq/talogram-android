/*
 * Talogram mod - the record pill on the private call screen.
 * Tap to start/stop recording the call (merged mic + peer audio) into a local M4A file.
 * The recording is sent to Saved Messages when it stops (or when the call ends).
 */

package org.telegram.ui.Components.voip;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.Components.LayoutHelper;

public class JokerRecordPillView extends FrameLayout {

    public interface OnToggleRecordingListener {
        void onToggle();
    }

    private final ImageView iconView;
    private final TextView timerView;
    private boolean recording;
    private volatile int durationSeconds;

    public JokerRecordPillView(Context context, OnToggleRecordingListener toggleListener) {
        super(context);
        setWillNotDraw(true);

        iconView = new ImageView(context);
        iconView.setImageResource(R.drawable.ic_joker_record_dot);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iconView.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        setBackground(org.telegram.ui.ActionBar.Theme.createCircleDrawable(AndroidUtilities.dp(48), 0xffE53935));
        setOnClickListener(v -> {
            if (toggleListener != null) {
                toggleListener.onToggle();
            }
        });
        addView(iconView, LayoutHelper.createFrame(48, 48, Gravity.CENTER_VERTICAL | Gravity.LEFT));

        timerView = new TextView(context);
        timerView.setTextColor(Color.WHITE);
        timerView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
        timerView.setTypeface(Typeface.DEFAULT_BOLD);
        timerView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        timerView.setSingleLine(true);
        timerView.setText("  0:00");
        timerView.setVisibility(GONE);
        addView(timerView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.LEFT, 54, 0, 0, 0));
    }

    public void setRecording(boolean value) {
        recording = value;
        iconView.setImageResource(value ? R.drawable.ic_joker_record_stop : R.drawable.ic_joker_record_dot);
        timerView.setVisibility(value ? VISIBLE : GONE);
        if (!value) {
            timerView.setText("  0:00");
        } else {
            updateTimerText(durationSeconds);
        }
    }

    public void setTimerText(int seconds) {
        durationSeconds = seconds;
        if (recording) {
            updateTimerText(seconds);
        }
    }

    private void updateTimerText(int seconds) {
        timerView.setText("  " + formatDuration(seconds));
    }

    private String formatDuration(int seconds) {
        int minutes = seconds / 60;
        int secs = seconds % 60;
        return String.format(java.util.Locale.ROOT, "%d:%02d", minutes, secs);
    }
}
