package com.util.features;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.telephony.SmsManager;
import android.widget.Toast;

public class ScheduledSmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String rawNumbers = intent.getStringExtra("numbers");
        String message = intent.getStringExtra("message");
        if (rawNumbers == null || message == null || message.trim().isEmpty()) return;
        try {
            SmsManager manager = SmsManager.getDefault();
            int sent = 0;
            for (String raw : rawNumbers.split("[,;\\n]+")) {
                String number = raw.trim();
                if (!number.isEmpty()) {
                    manager.sendTextMessage(number, null, message, null, null);
                    sent++;
                }
            }
            SharedPreferences prefs = context.getSharedPreferences("utility_feature_data", Context.MODE_PRIVATE);
            String previous = prefs.getString("history", "");
            String entry = java.text.DateFormat.getDateTimeInstance().format(new java.util.Date())
                + " — Scheduled SMS request sent to " + sent + " recipient(s)";
            prefs.edit().putString("history", entry + (previous.isEmpty() ? "" : "\n" + previous)).apply();
            Toast.makeText(context, "Scheduled SMS request submitted", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(context, "Scheduled SMS failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
