package com.util.util;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.telephony.SmsManager;

import com.util.features.SmsStatusReceiver;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared SMS dispatch path. Uses multipart APIs for both short and long messages. */
public final class SmsDispatch {
    private static final AtomicInteger REQUEST_CODES =
            new AtomicInteger((int) (SystemClock.elapsedRealtime() & 0x3fffffff));

    private SmsDispatch() { }

    public static int send(Context context, SmsManager manager, String destination, String body) {
        if (context == null || manager == null) throw new IllegalArgumentException("SMS service is unavailable.");
        if (destination == null || destination.trim().isEmpty()) throw new IllegalArgumentException("Phone number is empty.");
        if (body == null || body.trim().isEmpty()) throw new IllegalArgumentException("Message is empty.");

        ArrayList<String> parts = manager.divideMessage(body);
        if (parts == null || parts.isEmpty()) throw new IllegalArgumentException("Message could not be split.");

        ArrayList<PendingIntent> sentIntents = new ArrayList<>();
        String masked = PhoneNumberHelper.maskNumber(destination);
        int messageId = REQUEST_CODES.incrementAndGet() & 0x7fffffff;
        for (int index = 0; index < parts.size(); index++) {
            int requestCode = REQUEST_CODES.incrementAndGet() & 0x7fffffff;
            Intent sent = new Intent(context, SmsStatusReceiver.class)
                    .setAction(SmsStatusReceiver.ACTION_SMS_SENT)
                    .setData(Uri.parse("utility-app://sms-sent/" + messageId + "/" + index))
                    .putExtra(SmsStatusReceiver.EXTRA_RECIPIENT, masked)
                    .putExtra(SmsStatusReceiver.EXTRA_PART, index + 1)
                    .putExtra(SmsStatusReceiver.EXTRA_PARTS, parts.size());
            sentIntents.add(PendingIntent.getBroadcast(
                    context, requestCode, sent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }

        manager.sendMultipartTextMessage(destination, null, parts, sentIntents, null);
        return parts.size();
    }
}
