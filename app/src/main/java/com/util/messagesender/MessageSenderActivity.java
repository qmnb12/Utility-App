package com.util.messagesender;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
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
import com.util.util.Constants;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageSenderActivity extends AppCompatActivity {
    private Context baseContext;
    private String name;
    private String number;
    private TextView sendMessageButton;
    private TextView currentNumberOfMessage;
    private TextView noOfMessagesTextView;
    private TextView messageGapTextView;
    private Set<String> randomDigitsSet;
    private Set<String> randomStringSet;
    private TextView stopMessagingButton;
    private Timer timer;
    private SmsManager smsManager;
    private String templateMessage;
    private TextView enterMessageTextView;
    private android.widget.EditText manualNumberEditText;
    private SharedPreferences prefs;
    private android.widget.Spinner countryCodeSpinner;
    private final String[] countryCodes = {"+91", "+1", "+44", "+971", "+61", "+65", "+81", "+49", "+33", "+86", "+92", "+880", "+94", "+977", "+966", "+234", "+27", "+60", "+20", "+7"};
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    /** true when the manual field was filled automatically from the clipboard (not typed by the user). */
    private boolean autoFilledFromClipboard = false;
    /** remaining / total counters of the running messaging loop */
    private int remaining = -1;
    private int totalMessages = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myInit();


        noOfMessagesTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!noOfMessagesTextView.getText().toString().isEmpty() && Integer.parseInt(noOfMessagesTextView.getText().toString()) > Integer.parseInt(getString(R.string.ms_max_no_of_message)))
                    noOfMessagesTextView.setText(R.string.ms_max_no_of_message);
            }
        });

        messageGapTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!messageGapTextView.getText().toString().isEmpty() && Integer.parseInt(messageGapTextView.getText().toString()) > Integer.parseInt(getString(R.string.ms_max_message_gap)))
                    messageGapTextView.setText(R.string.ms_max_message_gap);
            }
        });

        // select contact click listener
        Button selectContactButton = findViewById(R.id.ms_select_contact);
        final ActivityResultLauncher<String> requestContactsPermissionLauncher =
                registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                    if (!isGranted) {
                        Snackbar.make(findViewById(R.id.ms_select_contact),
                                "कॉन्टैक्ट पढ़ने की अनुमति ज़रूरी है", Snackbar.LENGTH_LONG).show();
                    }
                });
        selectContactButton.setOnClickListener(new View.OnClickListener() {
            final ActivityResultLauncher<Void> getContact = registerForActivityResult(new ActivityResultContracts.PickContact(), uri -> {
                // Handle the returned Uri
                if (uri != null) {
                    Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        int contactIdIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID);
                        String contactId = cursor.getString(contactIdIndex);
                        Cursor phoneCursor = getContentResolver().query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                null,
                                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                                new String[]{contactId},
                                null
                        );

                        if (phoneCursor != null && phoneCursor.moveToFirst()) {
                            int numberIndex = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                            int nameIndex = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                            if (nameIndex != -1 && numberIndex != -1) {
                                name = phoneCursor.getString(nameIndex);
                                number = phoneCursor.getString(numberIndex);
                                // a picked contact must win over a clipboard-filled number
                                autoFilledFromClipboard = false;
                                if (manualNumberEditText != null) {
                                    manualNumberEditText.setText("");
                                }
                                sendMessageButton.setText(getString(R.string.ms_send_message_text, name));
                                sendMessageButton.setClickable(true);
                                sendMessageButton.setBackgroundColor(MainActivity.COLOR10_DARK);
                                Snackbar.make(findViewById(R.id.ms_select_contact), "Contact selected: " + name, Snackbar.LENGTH_LONG).setAction("Action", null).show();
                            } else {
                                sendMessageButton.setClickable(false);
                                sendMessageButton.setBackgroundColor(MainActivity.COLOR10_LIGHT);
                                Snackbar.make(findViewById(R.id.ms_select_contact), "No contact is selected", Snackbar.LENGTH_LONG).setAction("Action", null).show();
                            }
                            phoneCursor.close();
                        }
                        cursor.close();
                    }
                }
            });

            @Override
            public void onClick(View v) {
                if (ContextCompat.checkSelfPermission(baseContext, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    getContact.launch(null);
                } else {
                    requestContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS);
                }
            }
        });

        sendMessageButton.setOnClickListener(new View.OnClickListener() {
            final ActivityResultLauncher<String> requestMessagingPermissionLauncher =
                    registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                        System.out.println("Is message permission granted: [" + isGranted + "]");
                        if (isGranted) {
                            startMessagingFromUi();
                        }
                    });
            @Override
            public void onClick(View v) {
                // Manual number wins ONLY if the user typed it themselves
                // (clipboard auto-fill must not override a picked contact).
                boolean userTypedNumber = manualNumberEditText != null
                        && manualNumberEditText.getText() != null
                        && !manualNumberEditText.getText().toString().trim().isEmpty()
                        && !autoFilledFromClipboard;
                if (userTypedNumber) {
                    String manualNum = manualNumberEditText.getText().toString().trim();
                    String code = (countryCodeSpinner != null && countryCodeSpinner.getSelectedItem() != null) ? countryCodeSpinner.getSelectedItem().toString() : "+91";
                    if (prefs != null) {
                        prefs.edit().putString("country_code", code).apply();
                    }
                    if (!manualNum.startsWith("+")) {
                        if (manualNum.startsWith("0")) {
                            manualNum = manualNum.substring(1);
                        }
                        number = code + manualNum;
                    } else {
                        number = manualNum;
                    }
                    name = number;
                    sendMessageButton.setText(getString(R.string.ms_send_message_text, name));
                }
                templateMessage = enterMessageTextView.getText().toString().trim();
                if (ContextCompat.checkSelfPermission(baseContext, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                    System.out.println("Already have the permission " + Manifest.permission.SEND_SMS);
                    startMessagingFromUi();
                } else {
                    System.out.println("Asking for permission " + Manifest.permission.SEND_SMS);
                    requestMessagingPermissionLauncher.launch(Manifest.permission.SEND_SMS);
                }
            }
        });

        stopMessagingButton.setOnClickListener(v -> {
            boolean wasRunning = timer != null;
            stopMessagingLoop();
            if (wasRunning) {
                Snackbar.make(v, "Messaging stopped to " + name, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    /** Validates all fields, prepares random placeholders and starts the loop. */
    private void startMessagingFromUi() {
        if (number == null || number.trim().isEmpty()) {
            Snackbar.make(findViewById(R.id.ms_button_message), "पहले नंबर डालें या कॉन्टैक्ट चुनें", Snackbar.LENGTH_LONG).show();
            return;
        }
        templateMessage = enterMessageTextView == null ? "" : enterMessageTextView.getText().toString().trim();
        if (templateMessage.isEmpty()) {
            Snackbar.make(findViewById(R.id.ms_button_message), "मैसेज लिखें", Snackbar.LENGTH_LONG).show();
            return;
        }
        String noTxt = noOfMessagesTextView.getText().toString().trim();
        String gapTxt = messageGapTextView.getText().toString().trim();
        if (noTxt.isEmpty() || gapTxt.isEmpty()) {
            Snackbar.make(findViewById(R.id.ms_button_message), "मैसेज की संख्या और गैप भरें", Snackbar.LENGTH_LONG).show();
            return;
        }

        // always reset, otherwise old placeholders leak into the new run
        randomDigitsSet = null;
        randomStringSet = null;

        if (templateMessage.contains(getString(R.string.ms_random_digit))) {
            Pattern pattern = Pattern.compile(getString(R.string.ms_random_digit) + "(\\d+)#");
            Matcher matcher = pattern.matcher(templateMessage);
            while (matcher.find()) {
                if (randomDigitsSet == null) randomDigitsSet = new HashSet<>();
                String digitStr = matcher.group(1);
                if (digitStr == null || digitStr.isEmpty() || !Pattern.matches("^\\d+$", digitStr)) {
                    Snackbar.make(findViewById(R.id.ms_button_message), "Please provide valid replaceable strings", Snackbar.LENGTH_LONG).show();
                    return;
                }
                randomDigitsSet.add(digitStr);
            }
        }
        if (templateMessage.contains(getString(R.string.ms_random_string))) {
            Pattern pattern = Pattern.compile(getString(R.string.ms_random_string) + "(\\d+)#");
            Matcher matcher = pattern.matcher(templateMessage);
            while (matcher.find()) {
                if (randomStringSet == null) randomStringSet = new HashSet<>();
                String digitStr = matcher.group(1);
                if (digitStr == null || digitStr.isEmpty() || !Pattern.matches("^\\d+$", digitStr)) {
                    Snackbar.make(findViewById(R.id.ms_button_message), "Please provide valid replaceable strings", Snackbar.LENGTH_LONG).show();
                    return;
                }
                randomStringSet.add(digitStr);
            }
        }

        performRepeatedMessages();
        Snackbar.make(findViewById(R.id.ms_button_message), "Sending " + noTxt + " message(s) to " + name + ", after every " + gapTxt + " sec", Snackbar.LENGTH_LONG).show();
    }

    private void performRepeatedMessages() {
        // never let two loops run at once (also resets remaining/totalMessages)
        stopMessagingLoop();
        int parsedMessages = 0;
        try {
            parsedMessages = Integer.parseInt(noOfMessagesTextView.getText().toString().trim());
        } catch (Exception ignored) {
            parsedMessages = 0;
        }
        totalMessages = parsedMessages;
        remaining = totalMessages;
        if (totalMessages <= 0) {
            Snackbar.make(findViewById(R.id.ms_button_message), "मैसेज की संख्या सही डालें", Snackbar.LENGTH_LONG).show();
            return;
        }
        stopMessagingButton.setBackgroundColor(MainActivity.COLOR30_DARK);
        stopMessagingButton.setClickable(true);
        // Save settings
        try {
            if (prefs != null) {
                SharedPreferences.Editor editor = prefs.edit();
                // keys MUST match the ones used when loading in myInit()
                editor.putString("no_of_messages", noOfMessagesTextView.getText().toString());
                editor.putString("message_gap", messageGapTextView.getText().toString());
                if (enterMessageTextView != null && enterMessageTextView.getText() != null) {
                    editor.putString("message_text", enterMessageTextView.getText().toString());
                }
                editor.apply();
            }
        } catch (Exception e) {
        }
        currentNumberOfMessage.setText(getString(R.string.ms_current_no_of_message, 0));
        currentNumberOfMessage.setVisibility(View.VISIBLE);

        // gap must be >= 1 second, otherwise Timer.schedule throws IllegalArgumentException
        long gapSec = 1;
        try {
            gapSec = Long.parseLong(messageGapTextView.getText().toString().trim());
        } catch (Exception ignored) {
        }
        if (gapSec < 1) {
            gapSec = 1;
            messageGapTextView.setText("1");
        }
        try {
            timer = new Timer();
            timer.schedule(new MessageTask(), Constants.AUTOMATIC_MESSAGING_INITIAL_DELAY, gapSec * 1000L);
        } catch (Exception e) {
            e.printStackTrace();
            Snackbar.make(findViewById(R.id.ms_button_message), "Messaging शुरू नहीं हो सकी", Snackbar.LENGTH_LONG).show();
        }
    }

    private void myInit() {
        setContentView(R.layout.activity_message_sender);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        baseContext = getBaseContext();
        prefs = getSharedPreferences("ms_prefs", Context.MODE_PRIVATE);

        // Header
        TextView headerTxt = findViewById(R.id.app_header);
        headerTxt.setText(R.string.message_sender);

        // Main menu button
        Button mainMenuBtn = findViewById(R.id.main_menu);
        mainMenuBtn.setOnClickListener(v -> startActivity(new Intent(baseContext, MainActivity.class)));

        sendMessageButton = findViewById(R.id.ms_button_message);
        sendMessageButton.setText(getString(R.string.ms_send_message_text, ""));
        currentNumberOfMessage = findViewById(R.id.ms_current_message_number);
        currentNumberOfMessage.setText(getString(R.string.ms_current_no_of_message, 0));
        currentNumberOfMessage.setVisibility(View.INVISIBLE);
        noOfMessagesTextView = findViewById(R.id.ms_no_of_message);
        messageGapTextView = findViewById(R.id.ms_message_gap);
        stopMessagingButton = findViewById(R.id.ms_stop_message);
        stopMessagingButton.setClickable(false);
        smsManager = SmsManager.getDefault();
        enterMessageTextView = findViewById(R.id.ms_enter_message);
        manualNumberEditText = findViewById(R.id.ms_manual_number);
        countryCodeSpinner = findViewById(R.id.ms_country_code_spinner);

        // Typing a number by hand must win over a clipboard-filled value.
        if (manualNumberEditText != null) {
            manualNumberEditText.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    autoFilledFromClipboard = false;
                }
            });
        }

        prefs = getSharedPreferences("ms_prefs", Context.MODE_PRIVATE);

        if (countryCodeSpinner != null) {
            android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, countryCodes);
            countryCodeSpinner.setAdapter(adapter);
            String savedCode = prefs.getString("country_code", "+91");
            for (int i = 0; i < countryCodes.length; i++) {
                if (countryCodes[i].equals(savedCode)) {
                    countryCodeSpinner.setSelection(i);
                    break;
                }
            }
        }

        String savedNo = prefs.getString("no_of_messages", null);
        String savedGap = prefs.getString("message_gap", null);
        String savedMsg = prefs.getString("message_text", null);
        if (savedNo != null && !savedNo.isEmpty()) {
            noOfMessagesTextView.setText(savedNo);
        }
        if (savedGap != null && !savedGap.isEmpty()) {
            messageGapTextView.setText(savedGap);
        }
        if (savedMsg != null && enterMessageTextView != null) {
            enterMessageTextView.setText(savedMsg);
        }

        // Load saved settings
        // keep old keys compatibility not needed; new keys loaded above

        // Clipboard auto fill
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clip = clipboard.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence text = clip.getItemAt(0).getText();
                        if (text != null) {
                            String clipStr = text.toString().trim().replaceAll("[^0-9+]", "");
                            if (!clipStr.isEmpty() && clipStr.length() >= 7 && manualNumberEditText != null && manualNumberEditText.getText().toString().trim().isEmpty()) {
                                if (clipStr.startsWith("+")) {
                                    for (int i = 0; i < countryCodes.length; i++) {
                                        if (clipStr.startsWith(countryCodes[i])) {
                                            if (countryCodeSpinner != null) {
                                                countryCodeSpinner.setSelection(i);
                                            }
                                            clipStr = clipStr.substring(countryCodes[i].length());
                                            break;
                                        }
                                    }
                                }
                                manualNumberEditText.setText(clipStr);
                                // set AFTER setText: the TextWatcher resets this flag on every change
                                autoFilledFromClipboard = true;
                            }
                        }
                    }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    class MessageTask extends TimerTask {
        @Override
        public void run() {
            // TimerTask runs on a background thread: hop to the main thread before touching UI
            handler.post(() -> doSendStep());
        }

        private void doSendStep() {
            try {
                if (remaining < 0) {
                    String s = noOfMessagesTextView.getText().toString().trim();
                    try {
                        totalMessages = s.isEmpty() ? 0 : Integer.parseInt(s);
                    } catch (Exception ignored) {
                        totalMessages = 0;
                    }
                    remaining = totalMessages;
                }
                if (remaining > 0 && number != null && !number.isEmpty()) {
                    currentNumberOfMessage.setText(getString(R.string.ms_current_no_of_message,
                            (totalMessages - remaining + 1)));
                    remaining--;

                    String messageToSend = templateMessage == null ? "" : new String(templateMessage);
                    if (randomDigitsSet != null && !randomDigitsSet.isEmpty()) {
                        for (String digitStr : randomDigitsSet)
                            messageToSend = Common.replacePlaceholderWithNumberStr(messageToSend, (getString(R.string.ms_random_digit) + digitStr + "#"), Integer.parseInt(digitStr));
                    }
                    if (randomStringSet != null && !randomStringSet.isEmpty()) {
                        for (String digitStr : randomStringSet)
                            messageToSend = Common.replacePlaceholderWithCharStr(messageToSend, (getString(R.string.ms_random_string) + digitStr + "#"), Integer.parseInt(digitStr));
                    }
                    if (smsManager == null)
                        smsManager = SmsManager.getDefault();
                    try {
                        if (messageToSend.length() > 160) {
                            // long SMS must be split, otherwise sendTextMessage throws / truncates
                            java.util.ArrayList<String> parts = smsManager.divideMessage(messageToSend);
                            smsManager.sendMultipartTextMessage(number, null, parts, null, null);
                        } else {
                            smsManager.sendTextMessage(number, null, messageToSend, null, null);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    stopMessagingButton.setClickable(false);
                    stopMessagingButton.setBackgroundColor(MainActivity.COLOR30_LIGHT);
                    currentNumberOfMessage.setVisibility(View.INVISIBLE);
                    Snackbar.make(findViewById(R.id.ms_stop_message), "Messaging stopped to " + name, Snackbar.LENGTH_LONG).show();
                    if (timer != null) {
                        timer.cancel();
                        timer = null;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the screen stops the messaging loop, so SMS is not sent in the background
        // unless the user explicitly enabled it.
        boolean runInBackground = prefs != null && prefs.getBoolean("ms_run_in_background", false);
        if (!runInBackground && !isChangingConfigurations()) {
            stopMessagingLoop();
        }
    }

    @Override
    protected void onDestroy() {
        stopMessagingLoop();
        super.onDestroy();
    }

    /** Cancels the messaging timer and resets the running-state UI. */
    private void stopMessagingLoop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        try {
            handler.removeCallbacksAndMessages(null);
        } catch (Exception ignored) {
        }
        // reset the counters so the next run starts from the requested number again
        remaining = -1;
        totalMessages = 0;
        try {
            if (stopMessagingButton != null) {
                stopMessagingButton.setClickable(false);
                stopMessagingButton.setBackgroundColor(MainActivity.COLOR30_LIGHT);
            }
            if (currentNumberOfMessage != null) {
                currentNumberOfMessage.setVisibility(View.INVISIBLE);
            }
        } catch (Exception ignored) {
        }
    }
}