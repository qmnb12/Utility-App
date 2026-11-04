package com.util.features;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telephony.SmsManager;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.util.R;
import com.util.util.PhoneNumberHelper;
import com.util.util.SmsDispatch;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class FeatureHubActivity extends AppCompatActivity {
    private static final String PREFS = "utility_feature_data";
    private static final String KEY_TEMPLATES = "templates";
    private static final String KEY_HISTORY = "history";
    private ActivityResultLauncher<String> requestSmsPermissionLauncher;
    private ActivityResultLauncher<String> requestCallPermissionLauncher;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_feature_hub);
        requestSmsPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    toast(granted
                            ? "SMS permission granted. Tap the action again."
                            : "SMS permission is required for this action.");
                });
        requestCallPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    toast(granted
                            ? "Phone permission granted. Tap the call action again."
                            : "Phone permission is required for this action.");
                });

        findViewById(R.id.feature_templates).setOnClickListener(v -> templatesDialog());
        findViewById(R.id.feature_multi_sms).setOnClickListener(v -> multiSmsDialog(false));
        findViewById(R.id.feature_schedule).setOnClickListener(v -> multiSmsDialog(true));
        findViewById(R.id.feature_history).setOnClickListener(v -> showHistory());
        findViewById(R.id.feature_direct_dial).setOnClickListener(v -> directDialDialog());
        findViewById(R.id.feature_clipboard_dial).setOnClickListener(v -> dialFromClipboard());
        findViewById(R.id.feature_device_info).setOnClickListener(v -> deviceInfoDialog());
        findViewById(R.id.feature_backup).setOnClickListener(v -> backupDialog());
        findViewById(R.id.feature_theme).setOnClickListener(v -> themeDialog());
        findViewById(R.id.feature_clear_data).setOnClickListener(v -> clearDataDialog());
        findViewById(R.id.feature_call_timer).setOnClickListener(v -> callTimerInfo());
        findViewById(R.id.feature_back).setOnClickListener(v -> finish());
    }

    private void templatesDialog() {
        EditText input = new EditText(this);
        input.setHint("One template per line");
        input.setMinLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setText(getPrefs().getString(KEY_TEMPLATES, ""));
        new AlertDialog.Builder(this).setTitle("SMS Templates")
                .setMessage("Save reusable message templates. Copy a template into Message Sender when needed.")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    getPrefs().edit().putString(KEY_TEMPLATES, input.getText().toString()).apply();
                    addHistory("Saved SMS templates");
                    toast("Templates saved");
                })
                .setNeutralButton("View", (d, w) -> new AlertDialog.Builder(this)
                        .setTitle("Saved templates")
                        .setMessage(getPrefs().getString(KEY_TEMPLATES, "").isEmpty()
                                ? "No templates saved." : getPrefs().getString(KEY_TEMPLATES, ""))
                        .setPositiveButton("OK", null).show())
                .setNegativeButton("Cancel", null).show();
    }

    private void multiSmsDialog(boolean schedule) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);

        EditText numbers = new EditText(this);
        numbers.setHint("Phone numbers separated by commas (default country: +91)");
        numbers.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        form.addView(numbers, new LinearLayout.LayoutParams(-1, -2));

        EditText message = new EditText(this);
        message.setHint("SMS message");
        message.setMinLines(3);
        message.setGravity(Gravity.TOP | Gravity.START);
        form.addView(message, new LinearLayout.LayoutParams(-1, -2));

        EditText delay = null;
        if (schedule) {
            delay = new EditText(this);
            delay.setHint("Send after how many minutes? (1–10080)");
            delay.setInputType(InputType.TYPE_CLASS_NUMBER);
            form.addView(delay, new LinearLayout.LayoutParams(-1, -2));
        }
        final EditText delayInput = delay;

        new AlertDialog.Builder(this).setTitle(schedule ? "Schedule SMS" : "Send to multiple contacts")
                .setMessage("Enter valid phone numbers. Long messages are automatically split into SMS parts.")
                .setView(form)
                .setPositiveButton(schedule ? "Schedule" : "Send", (d, w) -> {
                    String body = message.getText().toString().trim();
                    if (body.isEmpty()) {
                        toast("Enter a message.");
                        return;
                    }
                    if (!hasSmsPermission()) return;

                    final List<String> recipients;
                    try {
                        recipients = PhoneNumberHelper.parseRecipients(
                                numbers.getText().toString(), "+91");
                    } catch (IllegalArgumentException e) {
                        toast(e.getMessage());
                        return;
                    }

                    if (schedule) {
                        int minutes;
                        try {
                            minutes = Integer.parseInt(delayInput.getText().toString().trim());
                        } catch (Exception e) {
                            toast("Enter a valid delay.");
                            return;
                        }
                        if (minutes < 1 || minutes > 10080) {
                            toast("Delay must be between 1 and 10080 minutes.");
                            return;
                        }
                        confirmScheduling(recipients, body, minutes);
                    } else {
                        sendToRecipients(recipients, body);
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private boolean hasSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED) return true;
        requestSmsPermissionLauncher.launch(Manifest.permission.SEND_SMS);
        return false;
    }

    private void sendToRecipients(List<String> recipients, String body) {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            toast("This device does not support sending SMS.");
            return;
        }
        SmsManager manager = SmsManager.getDefault();
        int submitted = 0;
        int failed = 0;
        for (String recipient : recipients) {
            try {
                SmsDispatch.send(this, manager, recipient, body);
                submitted++;
            } catch (Exception e) {
                failed++;
                addHistory("SMS submission failed for " + PhoneNumberHelper.maskNumber(recipient)
                        + ": " + e.getClass().getSimpleName());
            }
        }
        addHistory("Multiple SMS submitted to " + submitted + " recipient(s); "
                + failed + " failed before submission.");
        toast("Submitted: " + submitted + " • Failed: " + failed
                + ". Carrier send results will appear in History.");
    }

    private void confirmScheduling(List<String> recipients, String body, int minutes) {
        boolean exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
            exactAllowed = alarm != null && alarm.canScheduleExactAlarms();
        }
        if (exactAllowed) {
            scheduleSms(recipients, body, minutes);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Exact alarm access is off")
                .setMessage("Android may delay an approximate alarm, usually by up to an hour and longer in Doze or battery-saving modes. You can schedule approximately now or enable exact alarms in Settings and then enter the schedule again.")
                .setPositiveButton("Schedule approximate", (d, w) ->
                        scheduleSms(recipients, body, minutes))
                .setNegativeButton("Open Settings", (d, w) -> openExactAlarmSettings())
                .setNeutralButton("Cancel", null)
                .show();
    }

    private void openExactAlarmSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
                toast("Open this app's Alarms & reminders access from Android Settings.");
            }
        }
    }

    private void scheduleSms(List<String> recipients, String body, int minutes) {
        long when = System.currentTimeMillis() + minutes * 60_000L;
        int id = ScheduledSmsStore.newId();
        String numberList = android.text.TextUtils.join(",", recipients);
        try {
            ScheduledSmsStore.save(this, id, when, numberList, body);
            if (!ScheduledSmsStore.schedule(this, id, when)) {
                ScheduledSmsStore.remove(this, id);
                toast("Android could not schedule this SMS.");
                return;
            }
            boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
                exact = alarm != null && alarm.canScheduleExactAlarms();
            }
            addHistory("Scheduled SMS for " + DateFormat.getDateTimeInstance().format(new Date(when))
                    + " to " + recipients.size() + " recipient(s) (" 
                    + (exact ? "exact alarm" : "approximate alarm") + ").");
            toast(exact
                    ? "SMS scheduled with exact alarm timing."
                    : "SMS scheduled approximately. Android may delay delivery.");
        } catch (Exception e) {
            ScheduledSmsStore.remove(this, id);
            toast("Could not schedule SMS: " + e.getMessage());
        }
    }

    private void showHistory() {
        String history = getPrefs().getString(KEY_HISTORY, "");
        new AlertDialog.Builder(this).setTitle("Call / SMS Activity History")
                .setMessage(history.isEmpty() ? "No app activity recorded yet." : history)
                .setPositiveButton("Clear", (d, w) -> getPrefs().edit().remove(KEY_HISTORY).apply())
                .setNegativeButton("Close", null).show();
    }

    private void addHistory(String item) {
        SmsStatusReceiver.appendHistory(this, item);
    }

    private void backupDialog() {
        String json;
        try {
            JSONObject root = new JSONObject();
            root.put("version", 2);
            root.put("feature_data", new JSONObject(getPrefs().getAll()));
            root.put("redialer", new JSONObject(
                    getSharedPreferences("ar_prefs", MODE_PRIVATE).getAll()));
            root.put("message_sender", new JSONObject(
                    getSharedPreferences("ms_prefs", MODE_PRIVATE).getAll()));
            root.put("scheduled_sms", new JSONObject(
                    getSharedPreferences("scheduled_sms_data", MODE_PRIVATE).getAll()));
            json = root.toString(2);
        } catch (Exception e) {
            toast("Could not create backup.");
            return;
        }
        EditText input = new EditText(this);
        input.setText(json);
        input.setMinLines(8);
        input.setGravity(Gravity.TOP | Gravity.START);
        new AlertDialog.Builder(this).setTitle("Backup / Restore")
                .setMessage("Copy this JSON to keep a backup. Restore accepts older version 1 backups too.")
                .setView(input)
                .setPositiveButton("Copy backup", (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Utility App backup", input.getText().toString()));
                    }
                    toast("Backup JSON copied.");
                })
                .setNeutralButton("Restore JSON", (d, w) -> restoreJson(input.getText().toString()))
                .setNegativeButton("Close", null).show();
    }

    private void restoreJson(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            int version = root.optInt("version", -1);
            if (version == 1) {
                if (!root.has(KEY_TEMPLATES) || !root.has(KEY_HISTORY)) {
                    toast("Incomplete backup; no data changed.");
                    return;
                }
                getPrefs().edit()
                        .putString(KEY_TEMPLATES, root.getString(KEY_TEMPLATES))
                        .putString(KEY_HISTORY, root.getString(KEY_HISTORY))
                        .apply();
            } else if (version == 2) {
                if (!root.has("feature_data") || !root.has("redialer")
                        || !root.has("message_sender") || !root.has("scheduled_sms")) {
                    toast("Incomplete backup; no data changed.");
                    return;
                }
                replacePreferences(getPrefs(), root.getJSONObject("feature_data"));
                replacePreferences(getSharedPreferences("ar_prefs", MODE_PRIVATE),
                        root.getJSONObject("redialer"));
                replacePreferences(getSharedPreferences("ms_prefs", MODE_PRIVATE),
                        root.getJSONObject("message_sender"));
                replacePreferences(getSharedPreferences("scheduled_sms_data", MODE_PRIVATE),
                        root.getJSONObject("scheduled_sms"));
                ScheduledSmsStore.rescheduleAll(this);
            } else {
                toast("Unsupported backup version; no data changed.");
                return;
            }
            toast("Backup restored.");
        } catch (Exception e) {
            toast("Invalid JSON; no data changed.");
        }
    }

    private void replacePreferences(SharedPreferences prefs, JSONObject values) throws Exception {
        SharedPreferences.Editor editor = prefs.edit().clear();
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = values.get(key);
            if (value == JSONObject.NULL) continue;
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Number) editor.putFloat(key, ((Number) value).floatValue());
            else if (value instanceof String) editor.putString(key, (String) value);
        }
        editor.apply();
    }

    private void themeDialog() {
        String[] choices = {"System default", "Light", "Dark"};
        new AlertDialog.Builder(this).setTitle("Appearance")
                .setItems(choices, (d, which) -> {
                    int mode = which == 1 ? AppCompatDelegate.MODE_NIGHT_NO
                            : which == 2 ? AppCompatDelegate.MODE_NIGHT_YES
                            : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                    AppCompatDelegate.setDefaultNightMode(mode);
                    getPrefs().edit().putInt("theme_mode", mode).apply();
                    toast("Theme preference updated.");
                }).show();
    }

    private void directDialDialog() {
        EditText input = new EditText(this);
        input.setHint("Enter phone number");
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(this).setTitle("Direct Dial")
                .setMessage("Enter a valid phone number.")
                .setView(input)
                .setPositiveButton("Call", (d, w) -> {
                    String number = PhoneNumberHelper.normalizePhoneNumber(input.getText().toString(), "+91");
                    if (number == null) {
                        toast("Enter a valid phone number.");
                        return;
                    }
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestCallPermissionLauncher.launch(Manifest.permission.CALL_PHONE);
                        return;
                    }
                    try {
                        startActivity(createCallIntent(number));
                        addHistory("Started direct call to " + PhoneNumberHelper.maskNumber(number));
                    } catch (Exception e) {
                        toast("Could not start call: " + e.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void dialFromClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                toast("Clipboard is empty.");
                return;
            }
            ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
            String raw = item == null || item.getText() == null ? "" : item.getText().toString().trim();
            String candidate = raw.replaceAll("[^+0-9]", "");
            String number = PhoneNumberHelper.normalizePhoneNumber(candidate, "+91");
            if (number == null) {
                toast("No valid phone number found in clipboard.");
                return;
            }
            new AlertDialog.Builder(this).setTitle("Dial from Clipboard")
                    .setMessage("Found: " + number + "\n\nCall this number?")
                    .setPositiveButton("Call", (d, w) -> {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                                != PackageManager.PERMISSION_GRANTED) {
                            requestCallPermissionLauncher.launch(Manifest.permission.CALL_PHONE);
                            return;
                        }
                        try {
                            startActivity(createCallIntent(number));
                            addHistory("Started clipboard call to " + PhoneNumberHelper.maskNumber(number));
                        } catch (Exception e) {
                            toast("Could not start call: " + e.getMessage());
                        }
                    })
                    .setNegativeButton("Cancel", null).show();
        } catch (Exception e) {
            toast("Could not read clipboard.");
        }
    }

    private Intent createCallIntent(String number) {
        Intent intent = new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null));
        boolean startOnSpeaker = getSharedPreferences("ar_prefs", MODE_PRIVATE)
                .getBoolean("call_on_speaker", false);
        intent.putExtra(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, startOnSpeaker);
        return intent;
    }

    private void deviceInfoDialog() {
        String info = "Brand: " + Build.BRAND + "\n"
                + "Model: " + Build.MODEL + "\n"
                + "Device: " + Build.DEVICE + "\n"
                + "Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n"
                + "Manufacturer: " + Build.MANUFACTURER + "\n"
                + "Board: " + Build.BOARD + "\n"
                + "Hardware: " + Build.HARDWARE;
        new AlertDialog.Builder(this).setTitle("Device Info")
                .setMessage(info)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("device_info", info));
                        toast("Copied to clipboard.");
                    }
                })
                .setNegativeButton("Close", null).show();
    }

    private void clearDataDialog() {
        new AlertDialog.Builder(this).setTitle("Clear All Data")
                .setMessage("This clears saved settings, templates, history and pending scheduled SMS messages.")
                .setPositiveButton("Clear", (d, w) -> {
                    ScheduledSmsStore.cancelAll(this);
                    getPrefs().edit().clear().apply();
                    getSharedPreferences("ar_prefs", MODE_PRIVATE).edit().clear().apply();
                    getSharedPreferences("ms_prefs", MODE_PRIVATE).edit().clear().apply();
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                    toast("All app data cleared.");
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void callTimerInfo() {
        new AlertDialog.Builder(this).setTitle("Call duration control")
                .setMessage("Call Start mode starts its timer as soon as the outgoing call is launched, so it can request hang-up even when the other person does not answer. After Active Signal mode starts from Android's OFFHOOK state; Android can report OFFHOOK while a call is still dialing, so this is not guaranteed to mean the other person answered. Exact answer detection requires this app to become the default Phone app and implement a complete InCallService interface. Auto hang-up uses TelecomManager and ANSWER_PHONE_CALLS where supported; Android or the phone manufacturer may still block it. Android 8 and below do not support this app's automatic hang-up mode.")
                .setPositiveButton("OK", null).show();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
