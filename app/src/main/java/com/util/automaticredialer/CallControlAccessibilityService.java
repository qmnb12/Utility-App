package com.util.automaticredialer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Displays a Stop Redial control only while an Automatic Redialer outgoing
 * attempt is in progress and the Phone UI exposes an elapsed call timer.
 * This intentionally does not distinguish a human answer from voicemail.
 */
public class CallControlAccessibilityService extends AccessibilityService {
    public static final String ACTION_STOP_REDIAL =
            "com.util.automaticredialer.action.STOP_REDIAL_FROM_CONNECTED_CALL";

    private static final long SESSION_HEARTBEAT_MAX_AGE_MS = 10000L;
    private static final long UI_MONITOR_INTERVAL_MS = 700L;
    private static final long DIAL_START_GRACE_MS = 25000L;
    private static final Pattern TIMER_PATTERN =
            Pattern.compile("\\b(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})\\b");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View overlayView;
    private boolean overlayDismissedForCurrentCall;
    private boolean monitorRunning;

    private final Runnable uiMonitor = new Runnable() {
        @Override public void run() {
            if (!isTrackedRedialCall()) {
                hideStopOverlay();
                overlayDismissedForCurrentCall = false;
                monitorRunning = false;
                return;
            }

            if (isPhoneCallIdle()) {
                long startedAt = getSharedPreferences("ar_prefs", MODE_PRIVATE)
                        .getLong("redial_call_started_at", 0L);
                if (startedAt <= 0L || System.currentTimeMillis() - startedAt > DIAL_START_GRACE_MS) {
                    hideStopOverlay();
                    overlayDismissedForCurrentCall = false;
                    monitorRunning = false;
                    return;
                }
            } else if (!overlayDismissedForCurrentCall
                    && !isOverlayVisible() && hasConnectedCallTimer()) {
                showStopOverlay();
            }

            handler.postDelayed(this, UI_MONITOR_INTERVAL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            setServiceInfo(info);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!isPhoneUiPackage(event.getPackageName().toString())) return;

        if (!isTrackedRedialCall()) {
            if (isOverlayVisible()) hideStopOverlay();
            return;
        }

        if (!monitorRunning) {
            monitorRunning = true;
            handler.removeCallbacks(uiMonitor);
            handler.post(uiMonitor);
        }
    }

    @Override
    public void onInterrupt() {
        hideStopOverlay();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacksAndMessages(null);
        hideStopOverlay();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hideStopOverlay();
        super.onDestroy();
    }

    private boolean isPhoneUiPackage(String packageName) {
        String value = packageName.toLowerCase(Locale.ROOT);
        return value.equals("com.google.android.dialer")
                || value.equals("com.android.dialer")
                || value.equals("com.android.incallui")
                || value.equals("com.android.phone")
                || value.equals("com.samsung.android.dialer")
                || value.equals("com.samsung.android.incallui");
    }

    private boolean isTrackedRedialCall() {
        SharedPreferences prefs = getSharedPreferences("ar_prefs", MODE_PRIVATE);
        if (!prefs.getBoolean("redial_session_active", false)
                || !prefs.getBoolean("redial_call_in_progress", false)) {
            return false;
        }
        long heartbeat = prefs.getLong("redial_session_heartbeat", 0L);
        return heartbeat > 0L
                && System.currentTimeMillis() - heartbeat <= SESSION_HEARTBEAT_MAX_AGE_MS;
    }

    private boolean isPhoneCallIdle() {
        try {
            TelephonyManager manager =
                    (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            return manager == null
                    || manager.getCallState() == TelephonyManager.CALL_STATE_IDLE;
        } catch (SecurityException e) {
            return false;
        }
    }

    private boolean hasConnectedCallTimer() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            Object rootPackage = root.getPackageName();
            if (rootPackage == null || !isPhoneUiPackage(rootPackage.toString())) return false;
            boolean[] found = new boolean[3];
            int[] visited = new int[1];
            scanCallUi(root, 0, visited, found);
            return found[1] || (found[0] && found[2]);
        } finally {
            root.recycle();
        }
    }

    private void scanCallUi(AccessibilityNodeInfo node, int depth,
                            int[] visited, boolean[] found) {
        if (node == null || depth > 28 || visited[0]++ > 500) return;

        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        String viewId = node.getViewIdResourceName();
        String className = node.getClassName() == null
                ? "" : node.getClassName().toString().toLowerCase(Locale.ROOT);
        String id = viewId == null ? "" : viewId.toLowerCase(Locale.ROOT);
        boolean timerId = id.contains("duration") || id.contains("elapsed")
                || id.contains("chronometer") || id.contains("call_timer")
                || id.contains("call_time");
        boolean chronoClass = className.contains("chronometer");

        int seconds = Math.max(parseTimerSeconds(text), parseTimerSeconds(description));
        if (seconds >= 1) {
            found[0] = true;
            if (timerId || chronoClass) found[1] = true;
        }

        String labels = ((text == null ? "" : text.toString()) + " "
                + (description == null ? "" : description.toString()) + " " + id)
                .toLowerCase(Locale.ROOT);
        if (labels.contains("end_call") || labels.contains("hang_up")
                || labels.contains("end call") || labels.contains("hang up")
                || labels.contains("disconnect")) {
            found[2] = true;
        }

        for (int i = 0; i < node.getChildCount() && visited[0] <= 500; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                scanCallUi(child, depth + 1, visited, found);
                child.recycle();
            }
        }
    }

    private int parseTimerSeconds(CharSequence value) {
        if (value == null) return -1;
        Matcher matcher = TIMER_PATTERN.matcher(value);
        if (!matcher.find()) return -1;
        String[] parts = matcher.group().split(":");
        try {
            if (parts.length == 2) {
                return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            }
            if (parts.length == 3) {
                return Integer.parseInt(parts[0]) * 3600
                        + Integer.parseInt(parts[1]) * 60
                        + Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException ignored) { }
        return -1;
    }

    private boolean isOverlayVisible() {
        return overlayView != null;
    }

    private void showStopOverlay() {
        if (overlayView != null || windowManager == null) return;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        int pad = dp(10);
        panel.setPadding(dp(14), pad, dp(14), pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(18, 29, 48));
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), Color.rgb(255, 120, 120));
        panel.setBackground(background);

        TextView heading = new TextView(this);
        heading.setText("CALL CONNECTED • REDIAL ACTIVE");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(12);
        heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        panel.addView(heading, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button stopButton = new Button(this);
        stopButton.setText("STOP REDIAL");
        stopButton.setAllCaps(false);
        stopButton.setTextColor(Color.WHITE);
        stopButton.setTextSize(16);
        stopButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(198, 40, 40)));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                dp(230), dp(50));
        buttonParams.topMargin = dp(5);
        panel.addView(stopButton, buttonParams);

        stopButton.setOnClickListener(v -> {
            overlayDismissedForCurrentCall = true;
            hideStopOverlay();
            Intent stopIntent = new Intent(this, AutomaticRedialerActivity.class);
            stopIntent.setAction(ACTION_STOP_REDIAL);
            stopIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                startActivity(stopIntent);
            } catch (RuntimeException ignored) {
                Intent fallback = new Intent(ACTION_STOP_REDIAL);
                fallback.setPackage(getPackageName());
                sendBroadcast(fallback);
            }
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(120);
        try {
            windowManager.addView(panel, params);
            overlayView = panel;
        } catch (RuntimeException ignored) {
            overlayView = null;
        }
    }

    private void hideStopOverlay() {
        if (overlayView == null || windowManager == null) {
            overlayView = null;
            return;
        }
        try {
            windowManager.removeView(overlayView);
        } catch (RuntimeException ignored) { }
        overlayView = null;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
