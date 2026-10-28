package com.util.features;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.util.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

public class FeatureHubActivity extends AppCompatActivity {
    private static final String PREFS = "utility_feature_data";
    private static final String KEY_TEMPLATES = "templates";
    private static final String KEY_HISTORY = "history";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_feature_hub);
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
        input.setGravity( android.view.Gravity.TOP | android.view.Gravity.START);
        input.setText(getPrefs().getString(KEY_TEMPLATES, ""));
        new AlertDialog.Builder(this).setTitle("SMS Templates")
            .setMessage("Save reusable message templates. Select and copy a template into the sender.")
            .setView(input)
            .setPositiveButton("Save", (d, w) -> {
                getPrefs().edit().putString(KEY_TEMPLATES, input.getText().toString()).apply();
                addHistory("Saved SMS templates");
                Toast.makeText(this, "Templates saved", Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton("View", (d, w) -> new AlertDialog.Builder(this).setTitle("Saved templates")
                .setMessage(getPrefs().getString(KEY_TEMPLATES, "").isEmpty() ? "No templates saved." : getPrefs().getString(KEY_TEMPLATES, ""))
                .setPositiveButton("OK", null).show())
            .setNegativeButton("Cancel", null).show();
    }

    private void multiSmsDialog(boolean schedule) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);
        EditText numbers = new EditText(this);
        numbers.setHint("Phone numbers separated by commas");
        numbers.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        form.addView(numbers, new LinearLayout.LayoutParams(-1, -2));
        EditText message = new EditText(this);
        message.setHint("SMS message");
        message.setMinLines(3);
        message.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
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
            .setMessage("Use full phone numbers with country code where needed.")
            .setView(form)
            .setPositiveButton(schedule ? "Schedule" : "Send", (d, w) -> {
                String[] recipients = numbers.getText().toString().split("[,;\\n]+");
                String body = message.getText().toString().trim();
                if (body.isEmpty() || recipients.length == 0 || numbers.getText().toString().trim().isEmpty()) {
                    toast("Enter at least one number and a message");
                    return;
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    toast("Grant SMS permission in Android Settings, then try again");
                    return;
                }
                if (schedule) {
                    int minutes;
                    try { minutes = Integer.parseInt(delayInput.getText().toString().trim()); }
                    catch (Exception e) { toast("Enter a valid delay"); return; }
                    if (minutes < 1 || minutes > 10080) { toast("Delay must be 1–10080 minutes"); return; }
                    long when = System.currentTimeMillis() + minutes * 60_000L;
                    int id = (int)(System.currentTimeMillis() & 0x7fffffff);
                    Intent intent = new Intent(this, ScheduledSmsReceiver.class);
                    intent.putExtra("numbers", numbers.getText().toString());
                    intent.putExtra("message", body);
                    intent.putExtra("id", id);
                    PendingIntent pi = PendingIntent.getBroadcast(this, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    AlarmManager alarm = (AlarmManager)getSystemService(ALARM_SERVICE);
                    if (alarm == null) { toast("Scheduling is unavailable on this device"); return; }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
                        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
                    } else {
                        alarm.setExact(AlarmManager.RTC_WAKEUP, when, pi);
                    }
                    addHistory("Scheduled SMS for " + DateFormat.getDateTimeInstance().format(new Date(when)) + " to " + numbers.getText());
                    toast("SMS scheduled. Android battery restrictions may delay delivery.");
                } else {
                    int sent = 0;
                    try {
                        SmsManager manager = SmsManager.getDefault();
                        for (String raw : recipients) {
                            String number = raw.trim();
                            if (number.isEmpty()) continue;
                            manager.sendTextMessage(number, null, body, null, null);
                            sent++;
                        }
                        addHistory("Requested SMS to " + sent + " recipient(s): " + numbers.getText());
                        toast("SMS send request submitted for " + sent + " recipient(s)");
                    } catch (Exception e) {
                        addHistory("SMS send error: " + e.getClass().getSimpleName());
                        toast("Could not send SMS: " + e.getMessage());
                    }
                }
            }).setNegativeButton("Cancel", null).show();
    }

    private void showHistory() {
        String history = getPrefs().getString(KEY_HISTORY, "");
        new AlertDialog.Builder(this).setTitle("Call / SMS History")
            .setMessage(history.isEmpty() ? "No app activity recorded yet. Android call logs are not read by this feature." : history)
            .setPositiveButton("Clear", (d, w) -> getPrefs().edit().remove(KEY_HISTORY).apply())
            .setNegativeButton("Close", null).show();
    }

    private void addHistory(String item) {
        String previous = getPrefs().getString(KEY_HISTORY, "");
        String entry = DateFormat.getDateTimeInstance().format(new Date()) + " — " + item;
        String next = entry + (previous.isEmpty() ? "" : "\n" + previous);
        String[] lines = next.split("\\n");
        StringBuilder limited = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 100); i++) {
            if (i > 0) limited.append('\n');
            limited.append(lines[i]);
        }
        getPrefs().edit().putString(KEY_HISTORY, limited.toString()).apply();
    }

    private void backupDialog() {
        String json;
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put(KEY_TEMPLATES, getPrefs().getString(KEY_TEMPLATES, ""));
            root.put(KEY_HISTORY, getPrefs().getString(KEY_HISTORY, ""));
            json = root.toString(2);
        } catch (JSONException e) { toast("Could not create backup"); return; }
        EditText input = new EditText(this);
        input.setText(json);
        input.setMinLines(8);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        new AlertDialog.Builder(this).setTitle("Backup / Restore")
            .setMessage("Copy this JSON to keep a backup. To restore, paste a previously exported JSON here and tap Restore.")
            .setView(input)
            .setPositiveButton("Copy backup", (d, w) -> {
                ClipboardManager clipboard = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Utility App backup", input.getText().toString()));
                toast("Backup JSON copied");
            })
            .setNeutralButton("Restore JSON", (d, w) -> restoreJson(input.getText().toString()))
            .setNegativeButton("Close", null).show();
    }

    private void restoreJson(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", -1) != 1 || !root.has(KEY_TEMPLATES) || !root.has(KEY_HISTORY)) {
                toast("Unsupported or incomplete backup; no data changed");
                return;
            }
            getPrefs().edit().putString(KEY_TEMPLATES, root.getString(KEY_TEMPLATES))
                .putString(KEY_HISTORY, root.getString(KEY_HISTORY)).apply();
            toast("Backup restored");
        } catch (Exception e) { toast("Invalid JSON; no data changed"); }
    }

    private void themeDialog() {
        String[] choices = {"System default", "Light", "Dark"};
        new AlertDialog.Builder(this).setTitle("Appearance")
            .setItems(choices, (d, which) -> {
                int mode = which == 1 ? AppCompatDelegate.MODE_NIGHT_NO :
                    which == 2 ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                AppCompatDelegate.setDefaultNightMode(mode);
                getPrefs().edit().putInt("theme_mode", mode).apply();
                toast("Theme preference updated");
            }).show();
    }

    private void directDialDialog() {
        EditText input = new EditText(this);
        input.setHint("Enter phone number");
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(this).setTitle("Direct Dial")
            .setMessage("Enter number to call directly")
            .setView(input)
            .setPositiveButton("Call", (d, w) -> {
                String num = input.getText().toString().trim();
                if (num.isEmpty()) { toast("Enter a number"); return; }
                try {
                    Intent intent = new Intent(Intent.ACTION_CALL);
                    intent.setData(android.net.Uri.parse("tel:" + num.replace(" ", "").replace("-", "")));
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                        toast("Grant CALL_PHONE permission in settings");
                        return;
                    }
                    startActivity(intent);
                    addHistory("Direct call to " + num);
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
                toast("Clipboard is empty");
                return;
            }
            ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
            String text = item == null ? "" : item.getText().toString().trim();
            if (text.isEmpty()) { toast("No text in clipboard"); return; }
            String cleaned = text.replaceAll("[^+0-9]", "");
            if (cleaned.isEmpty() || cleaned.length() < 7) { toast("No valid phone number found in clipboard"); return; }
            new AlertDialog.Builder(this).setTitle("Dial from Clipboard")
                .setMessage("Found: " + text + "\n\nCall this number?")
                .setPositiveButton("Call", (d, w) -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_CALL);
                        intent.setData(android.net.Uri.parse("tel:" + cleaned));
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                            toast("Grant CALL_PHONE permission in settings");
                            return;
                        }
                        startActivity(intent);
                        addHistory("Dialed from clipboard: " + cleaned);
                    } catch (Exception e) { toast("Could not start call"); }
                })
                .setNegativeButton("Cancel", null).show();
        } catch (Exception e) { toast("Could not read clipboard"); }
    }

    private void deviceInfoDialog() {
        String info = "Brand: " + Build.BRAND + "\n" +
                "Model: " + Build.MODEL + "\n" +
                "Device: " + Build.DEVICE + "\n" +
                "Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n" +
                "Manufacturer: " + Build.MANUFACTURER + "\n" +
                "Board: " + Build.BOARD + "\n" +
                "Hardware: " + Build.HARDWARE;
        new AlertDialog.Builder(this).setTitle("Device Info")
            .setMessage(info)
            .setPositiveButton("Copy", (d, w) -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("device_info", info));
                    toast("Copied to clipboard");
                }
            })
            .setNegativeButton("Close", null).show();
    }

    private void clearDataDialog() {
        new AlertDialog.Builder(this).setTitle("Clear All Data")
            .setMessage("This will clear Templates, Activity History and Theme preference. This action cannot be undone.")
            .setPositiveButton("Clear", (d, w) -> {
                getPrefs().edit().clear().apply();
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                addHistory("Cleared all app data");
                toast("All data cleared");
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void callTimerInfo() {
        new AlertDialog.Builder(this).setTitle("Call duration control")
            .setMessage("Android generally does not allow ordinary apps to disconnect an active cellular call reliably. This app cannot guarantee auto-hang-up. Use the phone's in-call screen to end the call; device and Android version restrictions apply.")
            .setPositiveButton("OK", null).show();
    }

    private android.content.SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
