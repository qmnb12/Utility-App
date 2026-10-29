package com.util.automaticredialer;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.util.R;
import com.util.databinding.ActivityAutomaticRedialerBinding;
import com.util.main.MainActivity;
import com.util.util.Constants;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutomaticRedialerActivity extends AppCompatActivity {

    private Timer timer = null;
    private Context baseContext = null;
    private String name = null;
    private String number = null;

    // Cached view references (avoid repeated findViewById on UI thread)
    private EditText noOfCallsTextView = null;
    private Button stopCallingButton = null;
    private TextView currentNoOfCalls = null;
    private EditText callGapTextView = null;
    private EditText manualNumberEditText = null;
    private EditText disconnectSecEditText = null;
    private EditText batchNumbersEditText = null;
    private Spinner countryCodeSpinner = null;
    private RadioGroup disconnectModeGroup = null;
    private CheckBox ussdModeCheckBox = null;
    private CheckBox runInBgCheckBox = null;
    private Button callButton = null;
    private Button selectContactButton = null;

    private SharedPreferences prefs;

    private final String[] countryCodes = {"+91", "+1", "+44", "+971", "+61", "+65", "+81", "+49", "+33", "+86", "+92", "+880", "+94", "+977", "+966", "+234", "+27", "+60", "+20", "+7"};

    private android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private java.util.ArrayList<Runnable> disconnectRunnables = new java.util.ArrayList<>();
    private android.content.BroadcastReceiver callStateReceiver = null;
    private boolean isCallActive = false;
    private int pickupDisconnectSec = 0;
    private boolean waitingForPickup = false;

    /** true when the manual field was filled automatically from the clipboard (not typed by the user). */
    private boolean autoFilledFromClipboard = false;
    /** total number of calls requested for the running loop */
    private int totalCalls = 0;
    /** remaining calls in current loop */
    private int remaining = -1;
    /** batch number list (parsed from ar_batch_numbers) — empty means single-number mode */
    private ArrayList<String> batchNumbers = null;
    /** current index into batchNumbers */
    private int batchIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myInit();
    }

    private void myInit() {
        ActivityAutomaticRedialerBinding binding = ActivityAutomaticRedialerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        baseContext = getBaseContext();
        prefs = getSharedPreferences("ar_prefs", Context.MODE_PRIVATE);

        // Header
        TextView headerTxt = findViewById(R.id.app_header);
        if (headerTxt != null) {
            headerTxt.setText(R.string.automatic_redialer);
        }

        // Main menu button
        Button mainMenuBtn = findViewById(R.id.main_menu);
        if (mainMenuBtn != null) {
            mainMenuBtn.setOnClickListener(v -> startActivity(new Intent(baseContext, MainActivity.class)));
        }

        // Cache all views once
        noOfCallsTextView = findViewById(R.id.ar_no_of_calls);
        callGapTextView = findViewById(R.id.ar_call_gap);
        manualNumberEditText = findViewById(R.id.ar_manual_number);
        callButton = findViewById(R.id.ar_button_call);
        stopCallingButton = findViewById(R.id.ar_stop_call);
        currentNoOfCalls = findViewById(R.id.ar_current_call_number);
        disconnectSecEditText = findViewById(R.id.ar_disconnect_sec);
        countryCodeSpinner = findViewById(R.id.ar_country_code_spinner);
        disconnectModeGroup = findViewById(R.id.ar_disconnect_mode_group);
        batchNumbersEditText = findViewById(R.id.ar_batch_numbers);
        ussdModeCheckBox = findViewById(R.id.ar_ussd_mode);
        runInBgCheckBox = findViewById(R.id.ar_run_in_background);
        selectContactButton = findViewById(R.id.ar_select_contact);

        // Setup country code spinner
        if (countryCodeSpinner != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, countryCodes);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            countryCodeSpinner.setAdapter(adapter);
            String savedCode = prefs.getString("country_code", "+91");
            for (int i = 0; i < countryCodes.length; i++) {
                if (countryCodes[i].equals(savedCode)) {
                    countryCodeSpinner.setSelection(i);
                    break;
                }
            }
        }

        // Load saved settings
        String savedCalls = prefs.getString("no_of_calls", null);
        String savedGap = prefs.getString("call_gap", null);
        String savedDisconnect = prefs.getString("disconnect_sec", "0");

        if (savedCalls != null && !savedCalls.isEmpty()) {
            noOfCallsTextView.setText(savedCalls);
        }
        if (savedGap != null && !savedGap.isEmpty()) {
            callGapTextView.setText(savedGap);
        }
        if (disconnectSecEditText != null && savedDisconnect != null) {
            disconnectSecEditText.setText(savedDisconnect);
        }
        int savedMode = prefs.getInt("disconnect_mode", -1);
        if (disconnectModeGroup != null && savedMode != -1
                && disconnectModeGroup.findViewById(savedMode) != null) {
            disconnectModeGroup.check(savedMode);
        }

        // USSD Mode checkbox
        if (ussdModeCheckBox != null) {
            ussdModeCheckBox.setChecked(prefs.getBoolean("ussd_mode", false));
            ussdModeCheckBox.setOnCheckedChangeListener((btn, checked) ->
                    prefs.edit().putBoolean("ussd_mode", checked).apply());
        }

        // Run in background checkbox
        if (runInBgCheckBox != null) {
            runInBgCheckBox.setChecked(prefs.getBoolean("run_in_background", false));
            runInBgCheckBox.setOnCheckedChangeListener((btn, checked) ->
                    prefs.edit().putBoolean("run_in_background", checked).apply());
        }

        // Batch Numbers edit text
        if (batchNumbersEditText != null) {
            String savedBatch = prefs.getString("batch_numbers", "");
            if (!savedBatch.isEmpty()) {
                batchNumbersEditText.setText(savedBatch);
            }
        }

        // No-of-calls input validation
        if (noOfCallsTextView != null) {
            noOfCallsTextView.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    try {
                        String val = s.toString().trim();
                        if (!val.isEmpty() && Integer.parseInt(val) > Integer.parseInt(getString(R.string.ar_max_no_of_calls)))
                            noOfCallsTextView.setText(R.string.ar_max_no_of_calls);
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        // Call gap input validation
        if (callGapTextView != null) {
            callGapTextView.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    try {
                        String val = s.toString().trim();
                        if (!val.isEmpty() && Integer.parseInt(val) > Integer.parseInt(getString(R.string.ar_max_call_gap)))
                            callGapTextView.setText(R.string.ar_max_call_gap);
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        if (currentNoOfCalls != null) {
            currentNoOfCalls.setVisibility(View.INVISIBLE);
        }

        // Manual number typed -> enable the call button right away (use cached button)
        if (manualNumberEditText != null && callButton != null) {
            manualNumberEditText.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    String typed = s == null ? "" : s.toString().trim();
                    autoFilledFromClipboard = false;
                    if (typed.length() >= 4) {
                        callButton.setClickable(true);
                        callButton.setBackgroundColor(MainActivity.COLOR10_DARK);
                    } else {
                        callButton.setClickable(false);
                        callButton.setBackgroundColor(MainActivity.COLOR10_LIGHT);
                    }
                }
            });
        }

        // Clipboard auto fill on background thread to avoid UI thread lag
        new Thread(() -> {
            try {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null && clipboard.hasPrimaryClip()) {
                    ClipData clip = clipboard.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence text = clip.getItemAt(0).getText();
                        if (text != null) {
                            String clipStr = text.toString().trim().replaceAll("[^0-9+]", "");
                            if (!clipStr.isEmpty() && clipStr.length() >= 7
                                    && manualNumberEditText != null
                                    && manualNumberEditText.getText().toString().trim().isEmpty()) {
                                runOnUiThread(() -> {
                                    String finalClipStr = clipStr;
                                    if (finalClipStr.startsWith("+")) {
                                        for (int i = 0; i < countryCodes.length; i++) {
                                            if (finalClipStr.startsWith(countryCodes[i])) {
                                                if (countryCodeSpinner != null) {
                                                    countryCodeSpinner.setSelection(i);
                                                }
                                                finalClipStr = finalClipStr.substring(countryCodes[i].length());
                                                break;
                                            }
                                        }
                                    }
                                    manualNumberEditText.setText(finalClipStr);
                                    autoFilledFromClipboard = true;
                                    if (callButton != null) {
                                        callButton.setClickable(true);
                                        callButton.setBackgroundColor(MainActivity.COLOR10_DARK);
                                    }
                                });
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }).start();

        // select contact click listener
        final ActivityResultLauncher<String> requestContactsPermissionLauncher =
                registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                    if (!isGranted) {
                        Snackbar.make(findViewById(R.id.ar_select_contact),
                                "कॉन्टैक्ट पढ़ने की अनुमति ज़रूरी है", Snackbar.LENGTH_LONG).show();
                    }
                });
        if (selectContactButton != null) {
            selectContactButton.setOnClickListener(new View.OnClickListener() {
                final ActivityResultLauncher<Void> getContact = registerForActivityResult(new ActivityResultContracts.PickContact(), uri -> {
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
                                    autoFilledFromClipboard = false;
                                    if (manualNumberEditText != null) {
                                        manualNumberEditText.setText("");
                                    }
                                    if (callButton != null) {
                                        callButton.setText(getString(R.string.ar_default_call_text, name));
                                        callButton.setClickable(true);
                                        callButton.setBackgroundColor(MainActivity.COLOR10_DARK);
                                    }
                                    Snackbar.make(findViewById(R.id.ar_select_contact), "Contact selected: " + name, Snackbar.LENGTH_LONG).show();
                                } else {
                                    if (callButton != null) {
                                        callButton.setClickable(false);
                                        callButton.setBackgroundColor(MainActivity.COLOR10_LIGHT);
                                    }
                                    Snackbar.make(findViewById(R.id.ar_select_contact), "No contact is selected", Snackbar.LENGTH_LONG).show();
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
        }

        // call button click listener
        if (callButton != null) {
            callButton.setOnClickListener(new View.OnClickListener() {
                final ActivityResultLauncher<String[]> requestCallingPermissionLauncher =
                        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                            Boolean granted = result.get(Manifest.permission.CALL_PHONE);
                            Boolean answerGranted = result.get(Manifest.permission.ANSWER_PHONE_CALLS);
                            System.out.println("Is calling permission granted: [" + granted + "]");
                            if (granted != null && granted) {
                                if (answerGranted == null || !answerGranted) {
                                    Snackbar.make(findViewById(R.id.ar_button_call),
                                            "कॉल काटने की अनुमति नहीं मिली — ऑटो डिस्कनेक्ट काम नहीं करेगा",
                                            Snackbar.LENGTH_LONG).show();
                                }
                                performRepeatedCalls();
                            } else {
                                Snackbar.make(findViewById(R.id.ar_button_call),
                                        "कॉल की अनुमति ज़रूरी है", Snackbar.LENGTH_LONG).show();
                            }
                        });

                @Override
                public void onClick(View v) {
                    // Save settings
                    try {
                        if (prefs != null) {
                            SharedPreferences.Editor editor = prefs.edit();
                            if (noOfCallsTextView != null && noOfCallsTextView.getText() != null) {
                                editor.putString("no_of_calls", noOfCallsTextView.getText().toString());
                            }
                            if (callGapTextView != null && callGapTextView.getText() != null) {
                                editor.putString("call_gap", callGapTextView.getText().toString());
                            }
                            if (disconnectSecEditText != null && disconnectSecEditText.getText() != null) {
                                editor.putString("disconnect_sec", disconnectSecEditText.getText().toString());
                            }
                            if (disconnectModeGroup != null) {
                                editor.putInt("disconnect_mode", disconnectModeGroup.getCheckedRadioButtonId());
                            }
                            editor.apply();
                        }
                    } catch (Exception e) {
                    }

                    // Manual number wins ONLY if the user typed it themselves
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
                        callButton.setText(getString(R.string.ar_default_call_text, name));
                    }

                    if (number == null || number.trim().isEmpty()) {
                        Snackbar.make(v, "पहले नंबर डालें या कॉन्टैक्ट चुनें", Snackbar.LENGTH_LONG).show();
                        return;
                    }
                    boolean hasCallPhone = ContextCompat.checkSelfPermission(baseContext, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
                    boolean hasAnswer = ContextCompat.checkSelfPermission(baseContext, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED;
                    boolean hasPhoneState = ContextCompat.checkSelfPermission(baseContext, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
                    if (hasCallPhone && hasAnswer && hasPhoneState) {
                        performRepeatedCalls();
                    } else {
                        requestCallingPermissionLauncher.launch(new String[]{
                                Manifest.permission.CALL_PHONE,
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.ANSWER_PHONE_CALLS
                        });
                    }
                }
            });
        }

        if (stopCallingButton != null) {
            stopCallingButton.setOnClickListener(v -> {
                boolean wasRunning = timer != null;
                stopCallingLoop();
                if (wasRunning) {
                    Snackbar.make(v, "Calling stopped to " + name, Snackbar.LENGTH_LONG).show();
                }
            });
        }
    }

    private void performRepeatedCalls() {
        // Parse batch numbers if provided
        batchNumbers = null;
        batchIndex = 0;
        if (batchNumbersEditText != null) {
            String batchText = batchNumbersEditText.getText().toString().trim();
            if (!batchText.isEmpty()) {
                prefs.edit().putString("batch_numbers", batchText).apply();
                String[] parts = batchText.split("[,\\n]+");
                batchNumbers = new ArrayList<>();
                for (String p : parts) {
                    String trimmed = p.trim();
                    if (!trimmed.isEmpty()) {
                        batchNumbers.add(trimmed);
                    }
                }
                if (batchNumbers.isEmpty()) {
                    batchNumbers = null;
                }
            }
        }

        String callsTxt = noOfCallsTextView.getText().toString().trim();
        if (callsTxt.isEmpty()) {
            noOfCallsTextView.setText(R.string.ar_default_no_of_calls);
            callsTxt = noOfCallsTextView.getText().toString().trim();
        }
        int parsedCalls = 0;
        try {
            parsedCalls = Integer.parseInt(callsTxt);
        } catch (Exception ignored) {
            parsedCalls = 0;
        }
        if (parsedCalls <= 0) {
            Snackbar.make(findViewById(R.id.ar_button_call), "कॉल की संख्या सही डालें", Snackbar.LENGTH_LONG).show();
            return;
        }

        String gapTxt = callGapTextView.getText().toString().trim();
        if (gapTxt.isEmpty()) {
            callGapTextView.setText(R.string.ar_default_call_gap);
            gapTxt = callGapTextView.getText().toString().trim();
        }
        int gapSec = 1;
        try {
            gapSec = Integer.parseInt(gapTxt);
        } catch (Exception ignored) {
            gapSec = 1;
        }
        if (gapSec < 1) {
            gapSec = 1;
        }

        // never let two loops run at once (this also resets remaining/totalCalls)
        stopCallingLoop();
        totalCalls = parsedCalls;
        remaining = totalCalls;
        batchIndex = 0;

        if (stopCallingButton != null) {
            stopCallingButton.setClickable(true);
            stopCallingButton.setBackgroundColor(MainActivity.COLOR30_DARK);
        }
        if (currentNoOfCalls != null) {
            currentNoOfCalls.setVisibility(View.VISIBLE);
        }

        try {
            timer = new Timer();
            timer.schedule(new CallTask(), Constants.AUTOMATIC_REDIALER_INITIAL_DELAY, gapSec * 1000L);
        } catch (Exception e) {
            e.printStackTrace();
            Snackbar.make(findViewById(R.id.ar_button_call), "कॉलिंग शुरू नहीं हो सकी", Snackbar.LENGTH_LONG).show();
            return;
        }

        boolean ussdMode = ussdModeCheckBox != null && ussdModeCheckBox.isChecked();
        String modeStr = ussdMode ? " (USSD)" : "";
        Snackbar.make(findViewById(R.id.ar_button_call), "Calling to " + name + " " + callsTxt + " times after every " + gapSec + " sec" + modeStr, Snackbar.LENGTH_LONG).show();
    }

    /**
     * Clears ONLY the pending end-call timers/receiver of the previous call.
     * Does NOT touch the redial loop (timer) — that must keep running.
     */
    private void clearDisconnectScheduling() {
        try {
            if (disconnectRunnables != null) {
                for (int i = 0; i < disconnectRunnables.size(); i++) {
                    Runnable r = disconnectRunnables.get(i);
                    if (r != null && handler != null) {
                        handler.removeCallbacks(r);
                    }
                }
                disconnectRunnables.clear();
            }
        } catch (Exception ignored) {
        }
        isCallActive = false;
        waitingForPickup = false;
        pickupDisconnectSec = 0;
    }

    class CallTask extends TimerTask {
        @Override
        public void run() {
            handler.post(() -> doCallStep());
        }

        private void doCallStep() {
            try {
                if (remaining < 0) {
                    String s = noOfCallsTextView.getText().toString().trim();
                    try {
                        totalCalls = s.isEmpty() ? 0 : Integer.parseInt(s);
                    } catch (Exception ignored) {
                        totalCalls = 0;
                    }
                    remaining = totalCalls;
                }

                // Determine the number to call for this step
                String numToCall = null;
                if (batchNumbers != null && !batchNumbers.isEmpty()) {
                    numToCall = batchNumbers.get(batchIndex % batchNumbers.size());
                } else {
                    numToCall = number;
                }

                if (remaining > 0 && numToCall != null && !numToCall.isEmpty()) {
                    if (currentNoOfCalls != null) {
                        currentNoOfCalls.setText(getString(R.string.ar_current_no_of_call,
                                (totalCalls - remaining + 1)));
                    }
                    remaining--;

                    clearDisconnectScheduling();

                    try {
                        boolean ussdMode = ussdModeCheckBox != null && ussdModeCheckBox.isChecked();
                        String dialUri = "tel:" + numToCall;
                        Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse(dialUri));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        e.printStackTrace();
                        return;
                    }

                    // Advance batch index for next iteration
                    if (batchNumbers != null && !batchNumbers.isEmpty()) {
                        batchIndex++;
                    }

                    scheduleDisconnect();
                } else {
                    finishCalling();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /** Schedules the end-call action based on the selected disconnect mode. */
    private void scheduleDisconnect() {
        try {
            int sec = 0;
            if (disconnectSecEditText != null) {
                try {
                    sec = Integer.parseInt(disconnectSecEditText.getText().toString().trim());
                } catch (Exception ignored) {
                    sec = 0;
                }
            }
            if (sec < 0) sec = 0;

            int mode = -1;
            if (disconnectModeGroup != null) {
                mode = disconnectModeGroup.getCheckedRadioButtonId();
            }

            if (mode == R.id.ar_mode_direct) {
                // Direct: hang up after `sec` seconds regardless of pickup
                scheduleEndCall(sec);
            } else if (mode == R.id.ar_mode_pickup) {
                // After-pickup: wait for OFFHOOK, then hang up after `sec` seconds
                isCallActive = true;
                waitingForPickup = true;
                pickupDisconnectSec = sec;
                registerCallStateListener();
            }
            // ar_mode_off: do nothing, manual hangup
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void scheduleEndCall(int delaySec) {
        Runnable endCallRunnable = new Runnable() {
            @Override
            public void run() {
                endCallNow();
                // advance to next call step after the call is ended
                handler.postDelayed(() -> {
                    // The next CallTask tick will handle the next call
                }, 1500);
            }
        };
        handler.postDelayed(endCallRunnable, delaySec * 1000L);
        disconnectRunnables.add(endCallRunnable);
    }

    private void endCallNow() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (tm != null) {
                    if (tm.endCall()) {
                        return;
                    }
                }
            }
            // Fallback: ITelephony via reflection
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            Method getITelephonyMethod = TelephonyManager.class.getDeclaredMethod("getITelephony");
            getITelephonyMethod.setAccessible(true);
            Object iTelephony = getITelephonyMethod.invoke(tm);
            Method endCallMethod = iTelephony.getClass().getDeclaredMethod("endCall");
            endCallMethod.setAccessible(true);
            endCallMethod.invoke(iTelephony);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void registerCallStateListener() {
        if (callStateReceiver != null) return;
        try {
            callStateReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                    if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
                        // Call answered — start the pickup countdown
                        waitingForPickup = false;
                        isCallActive = true;
                        if (pickupDisconnectSec > 0) {
                            scheduleEndCall(pickupDisconnectSec);
                        } else {
                            endCallNow();
                        }
                    } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                        // Call ended — reset pickup state only, NOT the redial timer
                        isCallActive = false;
                        waitingForPickup = false;
                        pickupDisconnectSec = 0;
                    }
                }
            };
            registerReceiver(callStateReceiver, new android.content.IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void finishCalling() {
        stopCallingLoop();
        if (currentNoOfCalls != null) {
            currentNoOfCalls.setVisibility(View.INVISIBLE);
        }
        if (callButton != null) {
            callButton.setClickable(false);
            callButton.setBackgroundColor(MainActivity.COLOR10_LIGHT);
        }
        if (stopCallingButton != null) {
            stopCallingButton.setClickable(false);
            stopCallingButton.setBackgroundColor(MainActivity.COLOR30_LIGHT);
        }
        Snackbar.make(findViewById(R.id.ar_button_call), "Calling finished to " + name, Snackbar.LENGTH_LONG).show();
    }

    /** Cancels the redial timer, pending disconnects and the call-state receiver. */
    private void stopCallingLoop() {
        stopAllDisconnectionInternal();
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        try {
            handler.removeCallbacksAndMessages(null);
        } catch (Exception ignored) {
        }
        remaining = -1;
        totalCalls = 0;
        batchIndex = 0;
        batchNumbers = null;
    }

    private void stopAllDisconnectionInternal() {
        try {
            if (callStateReceiver != null) {
                unregisterReceiver(callStateReceiver);
                callStateReceiver = null;
            }
        } catch (Exception ignored) {
        }
        clearDisconnectScheduling();
    }

    /** True while a call we dialed is still ringing/active. */
    private boolean isCallFlowActive() {
        return isCallActive || waitingForPickup;
    }

    private final Runnable backgroundStopRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isChangingConfigurations()) return;
            boolean runInBackground = prefs != null && prefs.getBoolean("run_in_background", false);
            if (runInBackground) return;
            if (timer == null) return;
            if (isCallFlowActive()) {
                handler.postDelayed(this, 3000);
                return;
            }
            stopCallingLoop();
        }
    };

    @Override
    protected void onStop() {
        super.onStop();
        if (isChangingConfigurations()) return;
        if (isFinishing()) {
            stopCallingLoop();
            return;
        }
        boolean runInBackground = prefs != null && prefs.getBoolean("run_in_background", false);
        if (runInBackground) return;
        handler.postDelayed(backgroundStopRunnable, 3000);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopCallingLoop();
        super.onDestroy();
    }
}
