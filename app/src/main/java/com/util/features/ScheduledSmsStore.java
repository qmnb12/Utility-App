package com.util.features;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Persists scheduled SMS jobs so they can be restored after a device reboot. */
public final class ScheduledSmsStore {
    private static final String PREFS = "scheduled_sms_data";
    private static final String PREFIX = "schedule_";
    private static final AtomicInteger IDS =
            new AtomicInteger((int) (System.currentTimeMillis() & 0x3fffffff));

    private ScheduledSmsStore() { }

    public static int newId() {
        int id = IDS.incrementAndGet() & 0x7fffffff;
        return id == 0 ? IDS.incrementAndGet() & 0x7fffffff : id;
    }

    public static void save(Context context, int id, long triggerAt, String numbers, String message) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", id);
        item.put("triggerAt", triggerAt);
        item.put("numbers", numbers);
        item.put("message", message);
        item.put("status", "pending");
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREFIX + id, item.toString()).apply();
    }

    public static synchronized JSONObject claim(Context context, int id) {
        String key = PREFIX + id;
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(key, null);
        if (raw == null) return null;
        try {
            JSONObject item = new JSONObject(raw);
            if (!"pending".equals(item.optString("status", "pending"))) return null;
            item.put("status", "sending");
            prefs.edit().putString(key, item.toString()).commit();
            return item;
        } catch (Exception ignored) {
            prefs.edit().remove(key).apply();
            return null;
        }
    }

    public static void remove(Context context, int id) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(PREFIX + id).apply();
        cancelAlarm(context, id);
    }

    /** Returns true when an alarm (exact or approximate) was successfully registered. */
    public static boolean schedule(Context context, int id, long triggerAt) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return false;
        PendingIntent pi = pendingIntent(context, id);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarm.canScheduleExactAlarms()) {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                } else {
                    // Inexact alarms do not require special "Alarms & reminders" access.
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                alarm.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
            return true;
        } catch (SecurityException denied) {
            try {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                return true;
            } catch (Exception fallbackFailure) {
                return false;
            }
        } catch (RuntimeException failure) {
            return false;
        }
    }

    public static void rescheduleAll(Context context) {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Map<String, ?> all = prefs.getAll();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (!entry.getKey().startsWith(PREFIX) || !(entry.getValue() instanceof String)) continue;
            try {
                JSONObject item = new JSONObject((String) entry.getValue());
                if (!"pending".equals(item.optString("status", "pending"))) continue;
                int id = item.getInt("id");
                long triggerAt = item.getLong("triggerAt");
                // If the device was powered off at the requested time, deliver the
                // pending message as soon as practical after boot instead of dropping it.
                schedule(context, id, Math.max(triggerAt, now + 1000L));
            } catch (Exception ignored) {
                prefs.edit().remove(entry.getKey()).apply();
            }
        }
    }

    public static void cancelAll(Context context) {
        Map<String, ?> all = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll();
        for (String key : all.keySet()) {
            if (key.startsWith(PREFIX)) {
                try {
                    cancelAlarm(context, Integer.parseInt(key.substring(PREFIX.length())));
                } catch (NumberFormatException ignored) { }
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static void cancelAlarm(Context context, int id) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        PendingIntent pi = pendingIntent(context, id);
        alarm.cancel(pi);
        pi.cancel();
    }

    private static PendingIntent pendingIntent(Context context, int id) {
        Intent intent = new Intent(context, ScheduledSmsReceiver.class)
                .setAction(ScheduledSmsReceiver.ACTION_DELIVER_SCHEDULED_SMS)
                .setData(Uri.parse("utility-app://scheduled-sms/" + id))
                .putExtra(ScheduledSmsReceiver.EXTRA_SCHEDULE_ID, id);
        return PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
