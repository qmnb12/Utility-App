package com.util.features;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.widget.Toast;

import com.util.util.PhoneNumberHelper;
import com.util.util.SmsDispatch;

import org.json.JSONObject;

import java.util.List;

public class ScheduledSmsReceiver extends BroadcastReceiver {
    public static final String ACTION_DELIVER_SCHEDULED_SMS = "com.util.action.DELIVER_SCHEDULED_SMS";
    public static final String EXTRA_SCHEDULE_ID = "schedule_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_DELIVER_SCHEDULED_SMS.equals(intent.getAction())) return;
        int id = intent.getIntExtra(EXTRA_SCHEDULE_ID, -1);
        if (id < 0) return;

        JSONObject job = ScheduledSmsStore.claim(context, id);
        if (job == null) return;
        try {
            if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                SmsStatusReceiver.appendHistory(context, "Scheduled SMS cancelled: SMS permission is not granted.");
                return;
            }
            String rawNumbers = job.optString("numbers", "");
            String message = job.optString("message", "");
            List<String> recipients = PhoneNumberHelper.parseRecipients(rawNumbers, "+91");
            SmsManager manager = SmsManager.getDefault();
            int submitted = 0;
            for (String number : recipients) {
                SmsDispatch.send(context, manager, number, message);
                submitted++;
            }
            SmsStatusReceiver.appendHistory(context,
                    "Scheduled SMS submitted to " + submitted + " recipient(s); carrier results will follow.");
            Toast.makeText(context, "Scheduled SMS submitted", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            SmsStatusReceiver.appendHistory(context,
                    "Scheduled SMS failed: " + e.getClass().getSimpleName());
            Toast.makeText(context, "Scheduled SMS failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            ScheduledSmsStore.remove(context, id);
        }
    }
}
