package com.util.messagesender;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.telephony.SmsManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.util.R;
import com.util.main.MainActivity;
import com.util.util.Common;
import com.util.util.PhoneNumberHelper;
import com.util.util.SmsDispatch;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageSenderActivity extends AppCompatActivity {
    private String name;
    private String number;
    private TextView sendMessageButton;
    private TextView currentNumberOfMessage;
    private android.widget.EditText noOfMessagesTextView;
    private android.widget.EditText messageGapTextView;
    private Set<String> randomDigitsSet;
    private Set<String> randomStringSet;
    private TextView stopMessagingButton;
    private SmsManager smsManager;
    private String templateMessage;
    private TextView enterMessageTextView;
    private android.widget.EditText manualNumberEditText;
    private android.content.SharedPreferences prefs;
    private android.widget.Spinner countryCodeSpinner;
    private final String[] countryCodes = {
            "+91", "+1", "+44", "+971", "+61", "+65", "+81", "+49", "+33",
            "+86", "+92", "+880", "+94", "+977", "+966", "+234", "+27", "+60", "+20", "+7"
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isSending;
    private int remaining;
    private int totalMessages;
    private long messageGapMillis;

    private final Runnable sendRunnable = new Runnable() {
        @Override
        public void run() {
            sendNextMessage();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myInit();

        noOfMessagesTextView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                try {
                    if (s.length() > 0 && Integer.parseInt(s.toString()) > 1000) {
                        noOfMessagesTextView.setText("1000");
                        noOfMessagesTextView.setSelection(noOfMessagesTextView.getText().length());
                    }
                } catch (NumberFormatException ignored) { }
            }
        });

        messageGapTextView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                try {
                    if (s.length() > 0 && Integer.parseInt(s.toString()) > 300) {
                        messageGapTextView.setText("300");
                        messageGapTextView.setSelection(messageGapTextView.getText().length());
                    }
                } catch (NumberFormatException ignored) { }
            }
        });

        Button selectContactButton = findViewById(R.id.ms_select_contact);
        ActivityResultLauncher<String> requestContactsPermissionLauncher =
                registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                    if (!isGranted) {
                        Snackbar.make(findViewById(R.id.ms_select_contact),
                                "Contact permission was not granted.", Snackbar.LENGTH_LONG).show();
                    }
                });

        ActivityResultLauncher<String> requestContactsPermission =
                registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                    if (!granted) {
                        Snackbar.make(findViewById(R.id.ms_select_contact),
                                "Contact permission is required to read a selected phone number.",
                                Snackbar.LENGTH_LONG).show();
                    }
                });

        ActivityResultLauncher<Void> getContact =
                registerForActivityResult(new ActivityResultContracts.PickContact(), uri -> {
                    if (uri == null) return;
                    Cursor cursor = null;
                    Cursor phoneCursor = null;
                    try {
                        cursor = getContentResolver().query(uri, null, null, null, null);
                        if (cursor == null || !cursor.moveToFirst()) {
                            showError("No contact details were found.");
                            return;
                        }
                        int idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID);
                        if (idIndex < 0) {
                            showError("The selected contact has no readable ID.");
                            return;
                        }
                        String contactId = cursor.getString(idIndex);
                        phoneCursor = getContentResolver().query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                null,
                                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                                new String[]{contactId},
                                null);
                        if (phoneCursor == null || !phoneCursor.moveToFirst()) {
                            showError("The selected contact has no phone number.");
                            return;
                        }
                        int numberIndex = phoneCursor.getColumnIndex(
                                ContactsContract.CommonDataKinds.Phone.NUMBER);
                        int nameIndex = phoneCursor.getColumnIndex(
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                        if (numberIndex < 0) {
                            showError("The selected contact has no phone number.");
                            return;
                        }
                        String selectedName = nameIndex >= 0
                                ? phoneCursor.getString(nameIndex) : "Contact";
                        String selectedNumber = PhoneNumberHelper.normalizePhoneNumber(
                                phoneCursor.getString(numberIndex), selectedCountryCode());
                        if (selectedNumber == null) {
                            showError("The selected phone number is invalid.");
                            return;
                        }
                        name = selectedName;
                        number = selectedNumber;
                        manualNumberEditText.setText("");
                        sendMessageButton.setText(getString(R.string.ms_send_message_text, name));
                        sendMessageButton.setClickable(true);
                        sendMessageButton.setBackgroundColor(MainActivity.COLOR10_DARK);
                    } catch (Exception e) {
                        showError("Could not read the selected contact.");
                    } finally {
                        if (phoneCursor != null) phoneCursor.close();
                        if (cursor != null) cursor.close();
                    }
                });

        selectContactButton.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                    == PackageManager.PERMISSION_GRANTED) {
                getContact.launch(null);
            } else {
                requestContactsPermission.launch(Manifest.permission.READ_CONTACTS);
            }
        });

        ActivityResultLauncher<String> requestSmsPermissionLauncher =
                registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        Snackbar.make(findViewById(R.id.ms_button_message),
                                "Permission granted. Tap Send again.", Snackbar.LENGTH_LONG).show();
                    } else {
                        showError("SMS permission is required to send messages.");
                    }
                });

        sendMessageButton.setOnClickListener(v -> {
            String typed = manualNumberEditText.getText() == null
                    ? "" : manualNumberEditText.getText().toString().trim();
            if (!typed.isEmpty()) {
                String normalized = PhoneNumberHelper.normalizePhoneNumber(typed, selectedCountryCode());
                if (normalized == null) {
                    showError("Enter a valid phone number.");
                    return;
                }
                number = normalized;
                name = normalized;
                sendMessageButton.setText(getString(R.string.ms_send_message_text, name));
            }
            templateMessage = enterMessageTextView.getText().toString().trim();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                    == PackageManager.PERMISSION_GRANTED) {
                startMessagingFromUi();
            } else {
                requestSmsPermissionLauncher.launch(Manifest.permission.SEND_SMS);
            }
        });

        stopMessagingButton.setOnClickListener(v -> {
            boolean wasRunning = isSending;
            stopMessagingLoop();
            if (wasRunning) {
                Snackbar.make(v, "Messaging stopped.", Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void myInit() {
        setContentView(R.layout.activity_message_sender);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("ms_prefs", Context.MODE_PRIVATE);

        TextView headerTxt = findViewById(R.id.app_header);
        headerTxt.setText(R.string.message_sender);
        Button mainMenuBtn = findViewById(R.id.main_menu);
        mainMenuBtn.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));

        sendMessageButton = findViewById(R.id.ms_button_message);
        sendMessageButton.setText(getString(R.string.ms_send_message_text, ""));
        currentNumberOfMessage = findViewById(R.id.ms_current_message_number);
        currentNumberOfMessage.setText(getString(R.string.ms_current_no_of_message, 0));
        currentNumberOfMessage.setVisibility(View.INVISIBLE);
        noOfMessagesTextView = findViewById(R.id.ms_no_of_message);
        messageGapTextView = findViewById(R.id.ms_message_gap);
        stopMessagingButton = findViewById(R.id.ms_stop_message);
        stopMessagingButton.setClickable(false);
        enterMessageTextView = findViewById(R.id.ms_enter_message);
        manualNumberEditText = findViewById(R.id.ms_manual_number);
        countryCodeSpinner = findViewById(R.id.ms_country_code_spinner);

        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, countryCodes);
        countryCodeSpinner.setAdapter(adapter);
        String savedCode = prefs.getString("country_code", "+91");
        for (int i = 0; i < countryCodes.length; i++) {
            if (countryCodes[i].equals(savedCode)) {
                countryCodeSpinner.setSelection(i);
                break;
            }
        }

        String savedNo = prefs.getString("no_of_messages", null);
        String savedGap = prefs.getString("message_gap", null);
        String savedMsg = prefs.getString("message_text", null);
        if (savedNo != null && !savedNo.isEmpty()) noOfMessagesTextView.setText(savedNo);
        if (savedGap != null && !savedGap.isEmpty()) messageGapTextView.setText(savedGap);
        if (savedMsg != null) enterMessageTextView.setText(savedMsg);

        // Clipboard numbers are valid recipients too; they must not be ignored at send time.
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                    String candidate = clip.getItemAt(0).getText().toString().trim();
                    if (PhoneNumberHelper.normalizePhoneNumber(candidate, selectedCountryCode()) != null
                            && manualNumberEditText.getText().toString().trim().isEmpty()) {
                        manualNumberEditText.setText(candidate);
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    private String selectedCountryCode() {
        Object selected = countryCodeSpinner == null ? null : countryCodeSpinner.getSelectedItem();
        return selected == null ? "+91" : selected.toString();
    }

    private void startMessagingFromUi() {
        if (number == null || number.trim().isEmpty()) {
            showError("Enter a phone number or select a contact first.");
            return;
        }
        templateMessage = enterMessageTextView.getText().toString().trim();
        if (templateMessage.isEmpty()) {
            showError("Enter a message first.");
            return;
        }
        int count;
        int gapSeconds;
        try {
            count = Integer.parseInt(noOfMessagesTextView.getText().toString().trim());
            gapSeconds = Integer.parseInt(messageGapTextView.getText().toString().trim());
        } catch (NumberFormatException e) {
            showError("Enter valid message count and interval values.");
            return;
        }
        if (count < 1 || count > 1000) {
            showError("Message count must be between 1 and 1000.");
            return;
        }
        if (gapSeconds < 1 || gapSeconds > 300) {
            showError("Message interval must be between 1 and 300 seconds.");
            return;
        }
        if (!preparePlaceholders()) return;
        performRepeatedMessages(count, gapSeconds);
    }

    private boolean preparePlaceholders() {
        randomDigitsSet = new HashSet<>();
        randomStringSet = new HashSet<>();
        if (!collectPlaceholderLengths(templateMessage, getString(R.string.ms_random_digit), randomDigitsSet)) {
            showError("Use random digit placeholders like #random_digit4# (1–50).");
            return false;
        }
        if (!collectPlaceholderLengths(templateMessage, getString(R.string.ms_random_string), randomStringSet)) {
            showError("Use random string placeholders like #random_string6# (1–50).");
            return false;
        }
        return true;
    }

    private boolean collectPlaceholderLengths(String source, String prefix, Set<String> output) {
        if (!source.contains(prefix)) return true;
        Matcher matcher = Pattern.compile(Pattern.quote(prefix) + "(\\d+)#").matcher(source);
        while (matcher.find()) {
            try {
                int length = Integer.parseInt(matcher.group(1));
                if (length < 1 || length > 50) return false;
                output.add(matcher.group(1));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        // A prefix with no valid token indicates a malformed placeholder.
        return !output.isEmpty();
    }

    private void performRepeatedMessages(int count, int gapSeconds) {
        stopMessagingLoop();
        totalMessages = count;
        remaining = count;
        messageGapMillis = gapSeconds * 1000L;
        isSending = true;
        smsManager = SmsManager.getDefault();

        prefs.edit()
                .putString("no_of_messages", String.valueOf(count))
                .putString("message_gap", String.valueOf(gapSeconds))
                .putString("country_code", selectedCountryCode())
                .putString("message_text", templateMessage)
                .apply();

        stopMessagingButton.setBackgroundColor(MainActivity.COLOR30_DARK);
        stopMessagingButton.setClickable(true);
        currentNumberOfMessage.setText(getString(R.string.ms_current_no_of_message, 0));
        currentNumberOfMessage.setVisibility(View.VISIBLE);
        handler.postDelayed(sendRunnable, Constants.AUTOMATIC_MESSAGING_INITIAL_DELAY);
        Snackbar.make(findViewById(R.id.ms_button_message),
                "Starting " + count + " message(s) to " + name + ". Keep this screen open.",
                Snackbar.LENGTH_LONG).show();
    }

    private void sendNextMessage() {
        if (!isSending) return;
        if (remaining <= 0) {
            finishMessaging();
            return;
        }
        try {
            String messageToSend = templateMessage;
            if (randomDigitsSet != null) {
                for (String length : randomDigitsSet) {
                    messageToSend = Common.replacePlaceholderWithNumberStr(
                            messageToSend, getString(R.string.ms_random_digit) + length + "#",
                            Integer.parseInt(length));
                }
            }
            if (randomStringSet != null) {
                for (String length : randomStringSet) {
                    messageToSend = Common.replacePlaceholderWithCharStr(
                            messageToSend, getString(R.string.ms_random_string) + length + "#",
                            Integer.parseInt(length));
                }
            }
            if (messageToSend == null || messageToSend.isEmpty()) {
                throw new IllegalArgumentException("Message became empty after placeholder replacement.");
            }
            SmsDispatch.send(this, smsManager, number, messageToSend);
            remaining--;
            int completed = totalMessages - remaining;
            currentNumberOfMessage.setText(getString(R.string.ms_current_no_of_message, completed));
            if (remaining == 0) {
                finishMessaging();
            } else {
                handler.postDelayed(sendRunnable, messageGapMillis);
            }
        } catch (Exception e) {
            stopMessagingLoop();
            showError("SMS could not be submitted: " + e.getMessage());
        }
    }

    private void finishMessaging() {
        isSending = false;
        handler.removeCallbacks(sendRunnable);
        stopMessagingButton.setClickable(false);
        stopMessagingButton.setBackgroundColor(MainActivity.COLOR30_LIGHT);
        Snackbar.make(findViewById(R.id.ms_button_message),
                "All SMS send requests have been submitted. Carrier delivery is not guaranteed.",
                Snackbar.LENGTH_LONG).show();
    }

    private void showError(String message) {
        Snackbar.make(findViewById(R.id.ms_button_message), message, Snackbar.LENGTH_LONG).show();
    }

    @Override
    protected void onStop() {
        // SMS sending is intentionally foreground-only. A background Activity cannot
        // reliably keep this timer alive or guarantee continued execution on Android 10+.
        stopMessagingLoop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopMessagingLoop();
        super.onDestroy();
    }

    private void stopMessagingLoop() {
        isSending = false;
        handler.removeCallbacks(sendRunnable);
        remaining = 0;
        totalMessages = 0;
        if (stopMessagingButton != null) {
            stopMessagingButton.setClickable(false);
            stopMessagingButton.setBackgroundColor(MainActivity.COLOR30_LIGHT);
        }
        if (currentNumberOfMessage != null) {
            currentNumberOfMessage.setVisibility(View.INVISIBLE);
        }
    }
}
