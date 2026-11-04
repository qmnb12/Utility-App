package com.util.features;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.text.DateFormat;
import java.util.Date;

/** Receives carrier/SMS framework submission results for individual SMS parts. */
public class SmsStatusReceiver extends BroadcastReceiver {
    public static final String ACTION_SMS_SENT = "com.util.action.SMS_SENT";
    public static final String EXTRA_RECIPIENT = "recipient";
    public static final String EXTRA_PART = "part";
    public static final String EXTRA_PARTS = "parts";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SMS_SENT.equals(intent.getAction())) return;
        String recipient = intent.getStringExtra(EXTRA_RECIPIENT);
        int part = intent.getIntExtra(EXTRA_PART, 1);
        int parts = intent.getIntExtra(EXTRA_PARTS, 1);
        boolean sent = getResultCode() == Activity.RESULT_OK;
        String result = sent
                ? "SMS send accepted for " + recipient + " (part " + part + "/" + parts + ")"
                : "SMS send failed for " + recipient + " (part " + part + "/" + parts
                    + ", result " + getResultCode() + ")";
        appendHistory(context, result);
    }

    public static void appendHistory(Context context, String item) {
        SharedPreferences prefs = context.getSharedPreferences("utility_feature_data", Context.MODE_PRIVATE);
        String previous = prefs.getString("history", "");
        String entry = DateFormat.getDateTimeInstance().format(new Date()) + " — " + item;
        String next = entry + (previous.isEmpty() ? "" : "\n" + previous);
        String[] lines = next.split("\\n");
        StringBuilder limited = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 100); i++) {
            if (i > 0) limited.append('\n');
            limited.append(lines[i]);
        }
        prefs.edit().putString("history", limited.toString()).apply();
    }
}
