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
import android.os.Handler;
import android.os.Looper;
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
import com.util.util.PhoneNumberHelper;

import java.util.ArrayList;

public class AutomaticRedialerActivity extends AppCompatActivity {
    private static final int DISCONNECT_OFF = R.id.ar_mode_off;
    private static final int DISCONNECT_DIRECT = R.id.ar_mode_direct;
    private static final int DISCONNECT_PICKUP = R.id.ar_mode_pickup;

    private boolean redialSessionRunning;
    private long callGapMillis = 5000L;
    private boolean disconnectCountdownStarted;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Runnable> disconnectRunnables = new ArrayList<>();
    private Context baseContext;
    private String name;
    private String number;
    private SharedPreferences prefs;

    private EditText noOfCallsTextView;
    private Button stopCallingButton;
    private TextView currentNoOfCalls;
    private EditText callGapTextView;
    private EditText manualNumberEditText;
    private EditText disconnectSecEditText;
    private EditText batchNumbersEditText;
    private LinearLayout disconnectTimeLayout;
    private TextView disconnectModeInfo;
    private Spinner countryCodeSpinner;
    private RadioGroup disconnectModeGroup;
    private RadioGroup audioRouteGroup;
    private CheckBox ussdModeCheckBox;
    private CheckBox runInBgCheckBox;
    private Button callButton;
    private Button selectContactButton;

    private final String[] countryCodes = {
            "+91", "+1", "+44", "+971", "+61", "+65", "+81", "+49", "+33",
            "+86", "+92", "+880", "+94", "+977", "+966", "+234", "+27", "+60", "+20", "+7"
    };

    private android.content.BroadcastReceiver callStateReceiver;
    private boolean activityResumed;
    private boolean currentCallLaunched;
    private boolean sawCallState;
    private boolean isCallActive;
    private boolean waitingForPickup;
    private int pickupDisconnectSec;
    private int disconnectMode = DISCONNECT_OFF;
    private int totalCalls;
    private int remaining;
    private ArrayList<String> batchNumbers;
    private int batchIndex;

    private final Runnable callStepRunnable = new Runnable() {
        @Override public void run() { doCallStep(); }
    };

    private final Runnable backgroundStopRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isChangingConfigurations() || !redialSessionRunning) return;
            if (prefs != null && prefs.getBoolean("run_in_background", false)) return;
            if (isCallFlowActive()) {
                handler.postDelayed(this, 3000);
            } else {
                stopCallingLoop();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myInit();
    }

    private void myInit() {
        ActivityAutomaticRedialerBinding binding =
                ActivityAutomaticRedialerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        baseContext = getBaseContext();
        prefs = getSharedPreferences("ar_prefs", Context.MODE_PRIVATE);

        TextView headerTxt = findViewById(R.id.app_header);
        if (headerTxt != null) headerTxt.setText(R.string.automatic_redialer);
        Button mainMenuBtn = findViewById(R.id.main_menu);
        if (mainMenuBtn != null) {
            mainMenuBtn.setOnClickListener(v ->
                    startActivity(new Intent(this, MainActivity.class)));
        }

        noOfCallsTextView = findViewById(R.id.ar_no_of_calls);
        callGapTextView = findViewById(R.id.ar_call_gap);
        manualNumberEditText = findViewById(R.id.ar_manual_number);
        callButton = findViewById(R.id.ar_button_call);
        stopCallingButton = findViewById(R.id.ar_stop_call);
        currentNoOfCalls = findViewById(R.id.ar_current_call_number);
        disconnectSecEditText = findViewById(R.id.ar_disconnect_sec);
        disconnectTimeLayout = findViewById(R.id.ar_disconnect_time_layout);
        disconnectModeInfo = findViewById(R.id.ar_disconnect_mode_info);
        countryCodeSpinner = findViewById(R.id.ar_country_code_spinner);
        disconnectModeGroup = findViewById(R.id.ar_disconnect_mode_group);
        audioRouteGroup = findViewById(R.id.ar_audio_route_group);
        batchNumbersEditText = findViewById(R.id.ar_batch_numbers);
        ussdModeCheckBox = findViewById(R.id.ar_ussd_mode);
        runInBgCheckBox = findViewById(R.id.ar_run_in_background);
        selectContactButton = findViewById(R.id.ar_select_contact);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, countryCodes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        countryCodeSpinner.setAdapter(adapter);
        String savedCode = prefs.getString("country_code", "+91");
        for (int i = 0; i < countryCodes.length; i++) {
            if (countryCodes[i].equals(savedCode)) {
                countryCodeSpinner.setSelection(i);
                break;
            }
        }

        String savedCalls = prefs.getString("no_of_calls", null);
        String savedGap = prefs.getString("call_gap", null);
        String savedDisconnect = prefs.getString("disconnect_sec", "5");
        if (savedCalls != null) noOfCallsTextView.setText(savedCalls);
        if (savedGap != null) callGapTextView.setText(savedGap);
        disconnectSecEditText.setText(savedDisconnect);
        int savedMode = prefs.getInt("disconnect_mode", DISCONNECT_OFF);
        if (disconnectModeGroup.findViewById(savedMode) != null) {
            disconnectModeGroup.check(savedMode);
        } else {
            disconnectModeGroup.check(DISCONNECT_OFF);
        }
        disconnectModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int mode = checkedId == -1 ? DISCONNECT_OFF : checkedId;
            prefs.edit().putInt("disconnect_mode", mode).apply();
            updateDisconnectModeInfo(mode);
        });
        updateDisconnectModeInfo(disconnectModeGroup.getCheckedRadioButtonId());

        boolean startOnSpeaker = prefs.getBoolean("call_on_speaker", false);
        audioRouteGroup.check(startOnSpeaker
                ? R.id.ar_audio_route_speaker : R.id.ar_audio_route_earpiece);
        audioRouteGroup.setOnCheckedChangeListener((group, checkedId) ->
                prefs.edit().putBoolean("call_on_speaker",
                        checkedId == R.id.ar_audio_route_speaker).apply());

        ussdModeCheckBox.setChecked(prefs.getBoolean("ussd_mode", false));
        ussdModeCheckBox.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean("ussd_mode", checked).apply());
        runInBgCheckBox.setChecked(prefs.getBoolean("run_in_background", false));
        runInBgCheckBox.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean("run_in_background", checked).apply());

        String savedBatch = prefs.getString("batch_numbers", "");
        if (!savedBatch.isEmpty()) batchNumbersEditText.setText(savedBatch);

        addMaximumWatcher(noOfCallsTextView, 500);
        addMaximumWatcher(callGapTextView, 180);
        addMaximumWatcher(disconnectSecEditText, 600);

        currentNoOfCalls.setVisibility(View.INVISIBLE);
        stopCallingButton.setClickable(false);
        stopCallingButton.setBackgroundColor(MainActivity.COLOR30_LIGHT);

        manualNumberEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                refreshCallButtonEnabled();
            }
        });
        batchNumbersEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                refreshCallButtonEnabled();
            }
        });
        refreshCallButtonEnabled();

        fillNumberFromClipboard();

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
                        int idIndex = cursor.getColumnIndex(android.provider.ContactsContract.Contacts._ID);
                        if (idIndex < 0) {
                            showError("Selected contact has no readable ID.");
                            return;
                        }
                        String contactId = cursor.getString(idIndex);
                        phoneCursor = getContentResolver().query(
                                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                null,
                                android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                                new String[]{contactId}, null);
                        if (phoneCursor == null || !phoneCursor.moveToFirst()) {
                            showError("Selected contact has no phone number.");
                            return;
                        }
                        int numberIndex = phoneCursor.getColumnIndex(
                                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER);
                        int nameIndex = phoneCursor.getColumnIndex(
                                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                        if (numberIndex < 0) {
                            showError("Selected contact has no phone number.");
                            return;
                        }
                        String normalized = PhoneNumberHelper.normalizePhoneNumber(
                                phoneCursor.getString(numberIndex), selectedCountryCode());
                        if (normalized == null) {
                            showError("Selected contact has an invalid phone number.");
                            return;
                        }
                        number = normalized;
                        name = nameIndex >= 0 ? phoneCursor.getString(nameIndex) : normalized;
                        manualNumberEditText.setText("");
                        callButton.setText(getString(R.string.ar_default_call_text, name));
                        callButton.setClickable(true);
                        callButton.setBackgroundColor(MainActivity.COLOR10_DARK);
                    } catch (Exception e) {
                        showError("Could not read the selected contact.");
                    } finally {
                        if (phoneCursor != null) phoneCursor.close();
                        if (cursor != null) cursor.close();
                    }
                });

        ActivityResultLauncher<String> requestContactsPermission =
                registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                    if (!granted) showError("Contact permission was not granted.");
                });
        selectContactButton.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                    == PackageManager.PERMISSION_GRANTED) {
                getContact.launch(null);
            } else {
                requestContactsPermission.launch(Manifest.permission.READ_CONTACTS);
            }
        });

        ActivityResultLauncher<String[]> requestCallPermissions =
                registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    boolean callGranted = ContextCompat.checkSelfPermission(
                            this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
                    boolean stateGranted = ContextCompat.checkSelfPermission(
                            this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
                    boolean answerGranted = ContextCompat.checkSelfPermission(
                            this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED;
                    if (!callGranted || !stateGranted) {
                        showError("Phone and call-state permissions are required for reliable redial.");
                        return;
                    }
                    if (disconnectMode != DISCONNECT_OFF && !answerGranted) {
                        showError("Auto-disconnect permission was not granted. Select Manual mode or grant Phone permission.");
                        return;
                    }
                    performRepeatedCalls();
                });

        callButton.setOnClickListener(v -> {
            disconnectMode = disconnectModeGroup.getCheckedRadioButtonId();
            if (disconnectMode == -1) disconnectMode = DISCONNECT_OFF;

            String typed = manualNumberEditText.getText().toString().trim();
            boolean ussd = ussdModeCheckBox.isChecked();
            if (!typed.isEmpty()) {
                if (ussd) {
                    if (!PhoneNumberHelper.isValidUssdCode(typed)) {
                        showError("Enter a valid USSD code such as *123#.");
                        return;
                    }
                    number = typed;
                } else {
                    number = PhoneNumberHelper.normalizePhoneNumber(typed, selectedCountryCode());
                    if (number == null) {
                        showError("Enter a valid phone number.");
                        return;
                    }
                }
                name = number;
                prefs.edit().putString("country_code", selectedCountryCode()).apply();
                callButton.setText(getString(R.string.ar_default_call_text, name));
            }

            boolean hasBatchNumbers = !batchNumbersEditText.getText().toString().trim().isEmpty();
            if ((number == null || number.trim().isEmpty()) && !hasBatchNumbers) {
                showError(ussd ? "Enter a USSD code first." : "Enter a number, select a contact or enter batch numbers.");
                return;
            }
            if (ussd && !PhoneNumberHelper.isValidUssdCode(number)) {
                showError("USSD mode requires a valid code such as *123#.");
                return;
            }

            boolean callGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
            boolean stateGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
            boolean answerGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED;
            if (callGranted && stateGranted
                    && (disconnectMode == DISCONNECT_OFF || answerGranted)) {
                performRepeatedCalls();
            } else {
                ArrayList<String> required = new ArrayList<>();
                if (!callGranted) required.add(Manifest.permission.CALL_PHONE);
                if (!stateGranted) required.add(Manifest.permission.READ_PHONE_STATE);
                if (disconnectMode != DISCONNECT_OFF && !answerGranted) {
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
                        showError("Supported automatic hang-up requires Android 9 or later.");
                        return;
                    }
                    required.add(Manifest.permission.ANSWER_PHONE_CALLS);
                }
                requestCallPermissions.launch(required.toArray(new String[0]));
            }
        });

        stopCallingButton.setOnClickListener(v -> {
            boolean wasRunning = redialSessionRunning;
            stopCallingLoop();
            if (wasRunning) Snackbar.make(v, "Redial session stopped.", Snackbar.LENGTH_LONG).show();
        });
    }

    private void refreshCallButtonEnabled() {
        boolean manual = manualNumberEditText != null
                && manualNumberEditText.getText() != null
                && manualNumberEditText.getText().toString().trim().length() >= 4;
        boolean batch = batchNumbersEditText != null
                && batchNumbersEditText.getText().toString().trim().length() >= 4;
        boolean enabled = manual || batch || number != null;
        callButton.setClickable(enabled);
        callButton.setBackgroundColor(enabled
                ? MainActivity.COLOR10_DARK : MainActivity.COLOR10_LIGHT);
    }

    private void addMaximumWatcher(EditText field, int maximum) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                try {
                    if (s.length() > 0 && Integer.parseInt(s.toString()) > maximum) {
                        field.setText(String.valueOf(maximum));
                        field.setSelection(field.getText().length());
                    }
                } catch (NumberFormatException ignored) { }
            }
        });
    }

    private void fillNumberFromClipboard() {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return;
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) return;
            String candidate = clip.getItemAt(0).getText().toString().trim();
            boolean valid = ussdModeCheckBox.isChecked()
                    ? PhoneNumberHelper.isValidUssdCode(candidate)
                    : PhoneNumberHelper.normalizePhoneNumber(candidate, selectedCountryCode()) != null;
            if (valid && manualNumberEditText.getText().toString().trim().isEmpty()) {
                manualNumberEditText.setText(candidate);
            }
        } catch (Exception ignored) { }
    }

    private String selectedCountryCode() {
        Object selected = countryCodeSpinner.getSelectedItem();
        return selected == null ? "+91" : selected.toString();
    }

    private void performRepeatedCalls() {
        boolean ussdMode = ussdModeCheckBox.isChecked();
        batchNumbers = null;
        batchIndex = 0;
        String batchText = batchNumbersEditText.getText().toString().trim();
        if (!batchText.isEmpty()) {
            batchNumbers = new ArrayList<>();
            String[] items = batchText.split("[,;\\n\\r]+");
            for (String item : items) {
                String value = item.trim();
                if (value.isEmpty()) continue;
                String normalized = ussdMode ? (PhoneNumberHelper.isValidUssdCode(value) ? value : null)
                        : PhoneNumberHelper.normalizePhoneNumber(value, selectedCountryCode());
                if (normalized == null) {
                    batchNumbers = null;
                    showError("Invalid batch entry: " + value);
                    return;
                }
                batchNumbers.add(normalized);
            }
            if (batchNumbers.isEmpty()) batchNumbers = null;
            if (ussdMode && batchNumbers != null) {
                showError("Batch USSD is not supported. Enter one USSD code at a time.");
                batchNumbers = null;
                return;
            }
            prefs.edit().putString("batch_numbers", batchText).apply();
        }

        int calls;
        int gapSeconds;
        int disconnectSeconds;
        try {
            calls = Integer.parseInt(noOfCallsTextView.getText().toString().trim());
            gapSeconds = Integer.parseInt(callGapTextView.getText().toString().trim());
            disconnectSeconds = Integer.parseInt(disconnectSecEditText.getText().toString().trim());
        } catch (NumberFormatException e) {
            showError("Enter valid call count, call interval and disconnect time.");
            return;
        }
        if (calls < 1 || calls > 500) {
            showError("Call count must be between 1 and 500.");
            return;
        }
        if (gapSeconds < 1 || gapSeconds > 180) {
            showError("Call interval must be between 1 and 180 seconds.");
            return;
        }
        if (disconnectSeconds < 0 || disconnectSeconds > 600) {
            showError("Disconnect time must be between 0 and 600 seconds.");
            return;
        }
        if (disconnectMode != DISCONNECT_OFF && disconnectSeconds < 1) {
            showError("Choose at least 1 second for automatic disconnect.");
            return;
        }
        if (disconnectMode != DISCONNECT_OFF
                && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            showError("Automatic hang-up is supported only on Android 9 and later.");
            return;
        }

        stopCallingLoop();
        totalCalls = calls;
        remaining = calls;
        disconnectMode = disconnectModeGroup.getCheckedRadioButtonId();
        if (disconnectMode == -1) disconnectMode = DISCONNECT_OFF;
        prefs.edit()
                .putString("no_of_calls", String.valueOf(calls))
                .putString("call_gap", String.valueOf(gapSeconds))
                .putString("disconnect_sec", String.valueOf(disconnectSeconds))
                .putInt("disconnect_mode", disconnectMode)
                .putString("country_code", selectedCountryCode())
                .putBoolean("ussd_mode", ussdMode)
                .putBoolean("call_on_speaker",
                        audioRouteGroup.getCheckedRadioButtonId() == R.id.ar_audio_route_speaker)
                .putBoolean("run_in_background", runInBgCheckBox.isChecked())
                .apply();

        stopCallingButton.setClickable(true);
        stopCallingButton.setBackgroundColor(MainActivity.COLOR30_DARK);
        currentNoOfCalls.setVisibility(View.VISIBLE);
        currentNoOfCalls.setText(getString(R.string.ar_current_no_of_call, 0));
        callGapMillis = gapSeconds * 1000L;
        try {
            redialSessionRunning = true;
            registerCallStateListener();
            scheduleNextCall(Constants.AUTOMATIC_REDIALER_INITIAL_DELAY);
        } catch (Exception e) {
            stopCallingLoop();
            showError("Redial session could not start: " + e.getMessage());
            return;
        }

        String mode = ussdMode ? "USSD" : "call";
        String route = ussdMode ? "" : (prefs.getBoolean("call_on_speaker", false)
                ? " Loudspeaker" : " Earpiece");
        Snackbar.make(callButton,
                "Starting " + calls + " " + mode + " attempt(s)." + route
                        + ". Next attempt waits for the previous call to end.",
                Snackbar.LENGTH_LONG).show();
    }

    private void registerCallStateListener() {
        if (callStateReceiver != null) return;
        callStateReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;
                String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
                    sawCallState = true;
                    isCallActive = true;
                    waitingForPickup = false;
                    if (disconnectMode == DISCONNECT_PICKUP && currentCallLaunched) {
                        waitingForPickup = false;
                        scheduleEndCall(pickupDisconnectSec);
                    }
                } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)
                        && currentCallLaunched && (sawCallState || waitingForPickup)) {
                    finishCurrentCall();
                }
            }
        };
        try {
            registerReceiver(callStateReceiver,
                    new android.content.IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED));
        } catch (Exception e) {
            callStateReceiver = null;
            throw e;
        }
    }

    private void clearDisconnectScheduling() {
        for (Runnable runnable : disconnectRunnables) {
            handler.removeCallbacks(runnable);
        }
        disconnectRunnables.clear();
        isCallActive = false;
        waitingForPickup = false;
        pickupDisconnectSec = 0;
        disconnectCountdownStarted = false;
    }

    private int getDisconnectDelaySeconds() {
        try {
            return Math.max(1, Integer.parseInt(
                    disconnectSecEditText.getText().toString().trim()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private void prepareDisconnectBeforeCall() {
        // The pickup countdown must be ready before OFFHOOK can arrive.
        if (disconnectMode == DISCONNECT_PICKUP) {
            waitingForPickup = true;
            pickupDisconnectSec = getDisconnectDelaySeconds();
        }
    }

    private void scheduleDisconnectAfterCallStarts() {
        // Direct mode counts from the ACTION_CALL launch, not from before the intent
        // is dispatched. Pickup mode is armed in prepareDisconnectBeforeCall().
        if (disconnectMode == DISCONNECT_DIRECT) {
            scheduleEndCall(getDisconnectDelaySeconds());
        }
    }

    private void scheduleEndCall(int delaySeconds) {
        if (!currentCallLaunched || disconnectCountdownStarted) return;
        disconnectCountdownStarted = true;
        Runnable endCallRunnable = () -> {
            if (!currentCallLaunched) return;
            boolean hangUpRequested = endCallNow();
            Runnable verifyDisconnect = () -> {
                if (!currentCallLaunched) return;
                if (isPhoneCallIdle()) {
                    sawCallState = true;
                    finishCurrentCall();
                } else {
                    showError(hangUpRequested
                            ? "Hang-up was requested, but Android has not confirmed disconnect. End the call manually if it continues."
                            : "Android or the phone app blocked auto hang-up. End the call manually.");
                }
            };
            handler.postDelayed(verifyDisconnect, 1500L);
            disconnectRunnables.add(verifyDisconnect);
        };
        handler.postDelayed(endCallRunnable, Math.max(1, delaySeconds) * 1000L);
        disconnectRunnables.add(endCallRunnable);
    }

    private boolean isPhoneCallIdle() {
        try {
            TelephonyManager phone = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            return phone != null && phone.getCallState() == TelephonyManager.CALL_STATE_IDLE;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private boolean endCallNow() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return false;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
                != PackageManager.PERMISSION_GRANTED) return false;
        try {
            TelecomManager telecom =
                    (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            return telecom != null && telecom.endCall();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void finishCurrentCall() {
        boolean hadActiveAttempt = currentCallLaunched;
        currentCallLaunched = false;
        sawCallState = false;
        clearDisconnectScheduling();
        if (!redialSessionRunning || !hadActiveAttempt) return;
        if (remaining <= 0) {
            finishCalling();
            return;
        }
        scheduleNextCall(callGapMillis);
    }

    private void scheduleNextCall(long delayMillis) {
        if (!redialSessionRunning) return;
        handler.removeCallbacks(callStepRunnable);
        handler.postDelayed(callStepRunnable, Math.max(0L, delayMillis));
    }

    private void doCallStep() {
            if (!redialSessionRunning) return;
            if (currentCallLaunched || isCallFlowActive()) return;
            if (remaining <= 0) {
                finishCalling();
                return;
            }
            if (!activityResumed) {
                scheduleNextCall(1000L);
                return;
            }

            boolean ussdMode = ussdModeCheckBox.isChecked();
            String target = batchNumbers != null && !batchNumbers.isEmpty()
                    ? batchNumbers.get(batchIndex % batchNumbers.size()) : number;
            if (target == null || target.trim().isEmpty()) {
                stopCallingLoop();
                showError("No valid phone number is available.");
                return;
            }

            if (ussdMode) {
                if (!PhoneNumberHelper.isValidUssdCode(target)) {
                    stopCallingLoop();
                    showError("Invalid USSD code.");
                    return;
                }
            } else {
                target = PhoneNumberHelper.normalizePhoneNumber(target, selectedCountryCode());
                if (target == null) {
                    stopCallingLoop();
                    showError("Invalid phone number.");
                    return;
                }
            }

            try {
                registerCallStateListener();
                currentCallLaunched = true;
                sawCallState = false;
                remaining--;
                int completed = totalCalls - remaining;
                currentNoOfCalls.setText(getString(R.string.ar_current_no_of_call, completed));

                // Arm pickup state before ACTION_CALL so an immediate OFFHOOK broadcast
                // cannot race the pickup timer initialization.
                prepareDisconnectBeforeCall();
                Uri dialUri = Uri.fromParts("tel", target, null);
                Intent callIntent = new Intent(Intent.ACTION_CALL, dialUri);
                if (!ussdMode) {
                    // Android Telecom supports this extra on ACTION_CALL (API 21+).
                    // false requests the normal non-speaker route (earpiece unless
                    // Bluetooth or a wired headset is active).
                    callIntent.putExtra(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE,
                            prefs.getBoolean("call_on_speaker", false));
                }
                startActivity(callIntent);
                scheduleDisconnectAfterCallStarts();

                if (batchNumbers != null && !batchNumbers.isEmpty()) batchIndex++;
            } catch (SecurityException e) {
                currentCallLaunched = false;
                stopCallingLoop();
                showError("Android denied the call. Check Phone permission and SIM availability.");
            } catch (Exception e) {
                currentCallLaunched = false;
                stopCallingLoop();
                showError("Call could not be started: " + e.getMessage());
            }
    }

    private boolean isCallFlowActive() {
        return currentCallLaunched || isCallActive || waitingForPickup;
    }

    private void finishCalling() {
        stopCallingLoop();
        currentNoOfCalls.setVisibility(View.INVISIBLE);
        callButton.setClickable(false);
        callButton.setBackgroundColor(MainActivity.COLOR10_LIGHT);
        stopCallingButton.setClickable(false);
        stopCallingButton.setBackgroundColor(MainActivity.COLOR30_LIGHT);
        Snackbar.make(callButton, "Redial session completed.", Snackbar.LENGTH_LONG).show();
    }

    private void stopCallingLoop() {
        handler.removeCallbacks(backgroundStopRunnable);
        handler.removeCallbacks(callStepRunnable);
        clearDisconnectScheduling();
        redialSessionRunning = false;
        if (callStateReceiver != null) {
            try {
                unregisterReceiver(callStateReceiver);
            } catch (Exception ignored) { }
            callStateReceiver = null;
        }
        currentCallLaunched = false;
        sawCallState = false;
        remaining = 0;
        totalCalls = 0;
        batchIndex = 0;
        batchNumbers = null;
    }

    private void updateDisconnectModeInfo(int mode) {
        if (disconnectTimeLayout == null || disconnectModeInfo == null) return;
        if (mode == -1) mode = DISCONNECT_OFF;
        if (mode == DISCONNECT_OFF) {
            disconnectTimeLayout.setVisibility(View.GONE);
            disconnectModeInfo.setText(R.string.ar_disconnect_info_off);
        } else if (mode == DISCONNECT_DIRECT) {
            disconnectTimeLayout.setVisibility(View.VISIBLE);
            disconnectModeInfo.setText(R.string.ar_disconnect_info_direct);
        } else {
            disconnectTimeLayout.setVisibility(View.VISIBLE);
            disconnectModeInfo.setText(R.string.ar_disconnect_info_pickup);
        }
    }

    private void showError(String message) {
        Snackbar.make(callButton, message, Snackbar.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (currentCallLaunched) {
            try {
                TelephonyManager phone =
                        (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                if (phone != null && phone.getCallState() == TelephonyManager.CALL_STATE_IDLE) {
                    finishCurrentCall();
                } else if (phone != null && phone.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK) {
                    sawCallState = true;
                    isCallActive = true;
                    if (disconnectMode == DISCONNECT_PICKUP && waitingForPickup) {
                        waitingForPickup = false;
                        scheduleEndCall(pickupDisconnectSec);
                    }
                }
            } catch (SecurityException ignored) { }
        }
        handler.removeCallbacks(backgroundStopRunnable);
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isChangingConfigurations()) return;
        if (isFinishing()) {
            stopCallingLoop();
            return;
        }
        boolean runInBackground = prefs != null && prefs.getBoolean("run_in_background", false);
        if (!runInBackground) handler.postDelayed(backgroundStopRunnable, 3000);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopCallingLoop();
        super.onDestroy();
    }
}
