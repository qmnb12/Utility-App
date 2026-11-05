package com.util.automaticredialer;

import android.Manifest;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Rational;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.util.R;
import com.util.databinding.ActivityAutomaticRedialerBinding;
import com.util.main.MainActivity;
import com.util.util.Constants;
import com.util.util.PhoneNumberHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class AutomaticRedialerActivity extends AppCompatActivity {
    private static final int DISCONNECT_OFF = R.id.ar_mode_off;
    private static final int DISCONNECT_DIRECT = R.id.ar_mode_direct;
    private static final int DISCONNECT_PICKUP = R.id.ar_mode_pickup;

    private static boolean redialSessionInUse;
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
    private Button lastCallButton;
    private Button historyButton;
    private Button liveLogButton;
    private TextView liveLogText;
    private ScrollView liveLogScroll;
    private AlertDialog liveLogDialog;
    private final StringBuilder liveLogBuffer = new StringBuilder();
    private static final String PREF_LAST_NUMBER = "last_used_number";
    private static final String PREF_LAST_NAME = "last_used_name";
    private static final String PREF_CALL_HISTORY = "automatic_call_history";
    private static final int MAX_CALL_HISTORY = 30;
    private long currentHistoryId = -1L;
    private long currentAttemptStartedAt;
    private String currentAttemptNumber;
    private String currentAttemptLabel;
    private final SimpleDateFormat logTimeFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private boolean waitingForAppVisibleLogged;

    private static final String ACTION_PIP_STOP_SESSION =
            "com.util.automaticredialer.action.PIP_STOP_SESSION";
    private static final String ACTION_PIP_RESTORE_DIALER =
            "com.util.automaticredialer.action.PIP_RESTORE_DIALER";
    private static final int ACTION_BLUE = Color.rgb(25, 118, 210);
    private static final int ACTION_GREEN = Color.rgb(46, 160, 91);
    private static final int ACTION_RED = Color.rgb(211, 47, 47);
    private boolean stopButtonWasPressed;
    private View standardHeaderView;
    private View standardContentView;
    private LinearLayout pipPanel;
    private TextView pipStatusText;
    private TextView pipNumberText;
    private TextView pipLogText;
    private boolean pipFeatureAvailable;
    private boolean pipAutoEnterAllowed;
    private boolean pipUnavailableLogged;
    private LinearLayout sessionFeedPanel;
    private TextView sessionFeedStatusText;
    private TextView sessionFeedNumberText;
    private TextView sessionFeedCountText;
    private TextView sessionFeedLogText;
    private ScrollView sessionFeedScroll;
    private Button sessionFeedStopButton;
    private Button sessionFeedDialerButton;
    private boolean showingSessionFeed;
    private boolean sessionCompleted;
    private Button enableCallOverlayButton;
    private final Runnable sessionHeartbeatRunnable = new Runnable() {
        @Override public void run() {
            if (!redialSessionRunning || prefs == null) return;
            prefs.edit().putLong("redial_session_heartbeat", System.currentTimeMillis()).apply();
            handler.postDelayed(this, 2000L);
        }
    };

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

    private final Runnable callStatePollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!redialSessionRunning || !currentCallLaunched) return;
            try {
                TelephonyManager phone =
                        (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                if (phone != null) {
                    int state = phone.getCallState();
                    if (state == TelephonyManager.CALL_STATE_OFFHOOK && !sawCallState) {
                        sawCallState = true;
                        isCallActive = true;
                        waitingForPickup = false;
                        appendLog("Phone-state polling fallback: OFFHOOK detected (dialing or active; answer not confirmed).");
                        if (disconnectMode == DISCONNECT_PICKUP) {
                            appendLog("OFFHOOK is not proof of answer. No timer started; waiting for a reliable InCallService event.");
                        }
                    } else if (state == TelephonyManager.CALL_STATE_IDLE && sawCallState) {
                        appendLog("Phone-state polling fallback: IDLE detected. The call ended; starting Call Gap.");
                        finishCurrentCall();
                        return;
                    }
                }
            } catch (SecurityException e) {
                appendLog("Phone-state polling unavailable: READ_PHONE_STATE permission was lost.");
            }
            if (currentCallLaunched) handler.postDelayed(this, 1000L);
        }
    };

    private final Runnable backgroundStopRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isChangingConfigurations() || !redialSessionRunning) return;
            if (prefs != null && prefs.getBoolean("run_in_background", false)) return;
            if (isCallFlowActive()) {
                handler.postDelayed(this, 3000);
            } else {
                appendLog("Session stopped: app left the foreground and background mode is disabled.");
                stopCallingLoop();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myInit();
        handlePipActionIntent(getIntent());
        if (savedInstanceState != null) {
            String restoredLog = savedInstanceState.getString("automatic_redialer_live_log");
            if (restoredLog != null && !restoredLog.isEmpty()) {
                liveLogBuffer.setLength(0);
                liveLogBuffer.append(restoredLog);
                refreshLiveLogView();
            }
        }
    }

    private void myInit() {
        ActivityAutomaticRedialerBinding binding =
                ActivityAutomaticRedialerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        LinearLayout activityRoot = binding.getRoot();
        standardHeaderView = activityRoot.getChildAt(0);
        standardContentView = activityRoot.getChildAt(1);
        setupPipPanel(activityRoot);
        setupSessionFeedPanel(activityRoot);
        pipFeatureAvailable = getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_PICTURE_IN_PICTURE);
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
        enableCallOverlayButton = findViewById(R.id.ar_enable_call_overlay);
        if (enableCallOverlayButton != null) {
            enableCallOverlayButton.setOnClickListener(v -> openCallOverlayAccessibilitySettings());
            updateCallOverlayButtonLabel();
        }
        selectContactButton = findViewById(R.id.ar_select_contact);
        liveLogText = null;
        liveLogScroll = null;
        lastCallButton = findViewById(R.id.ar_last_call);
        historyButton = findViewById(R.id.ar_open_history);
        liveLogButton = findViewById(R.id.ar_open_log);
        appendLog("Live log ready. Open it from the quick-action icon.");

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

        number = prefs.getString(PREF_LAST_NUMBER, null);
        name = prefs.getString(PREF_LAST_NAME, number);
        if (number != null && !number.trim().isEmpty()) {
            if (name == null || name.trim().isEmpty()) name = number;
            callButton.setText(getString(R.string.ar_default_call_text, name));
            appendLog("Restored last used number/contact from this device.");
        }

        String savedCalls = prefs.getString("no_of_calls", null);
        String savedGap = prefs.getString("call_gap", null);
        // The previous redial setup used a 3-second gap. Move that old value
        // to the newly requested 10-second post-disconnect gap, but preserve
        // any other custom value the user may have selected.
        if ("3".equals(savedGap)) {
            savedGap = "10";
            prefs.edit().putString("call_gap", savedGap).apply();
            appendLog("Updated the previous 3-second Call Gap to 10 seconds.");
        }
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
        stopCallingButton.setEnabled(false);
        stopCallingButton.setBackgroundTintList(ColorStateList.valueOf(ACTION_BLUE));
        stopCallingButton.setTextColor(Color.WHITE);

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
                        saveLastUsedNumber(number, name);
                        appendLog("Selected contact saved as the last-used contact.");
                        manualNumberEditText.setText("");
                        callButton.setText(getString(R.string.ar_default_call_text, name));
                        refreshCallButtonEnabled();
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

        lastCallButton.setOnClickListener(v -> selectLastCall());

        ActivityResultLauncher<Intent> chooseHistoryCall =
                registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    String selectedNumber = result.getData().getStringExtra(CallHistoryActivity.EXTRA_NUMBER);
                    String selectedLabel = result.getData().getStringExtra(CallHistoryActivity.EXTRA_LABEL);
                    applySelectedNumber(selectedNumber, selectedLabel);
                });
        historyButton.setOnClickListener(v ->
                chooseHistoryCall.launch(new Intent(this, CallHistoryActivity.class)));

        liveLogButton.setOnClickListener(v -> showLiveLogDialog());

        ActivityResultLauncher<String[]> requestCallPermissions =
                registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    boolean callGranted = ContextCompat.checkSelfPermission(
                            this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
                    boolean stateGranted = ContextCompat.checkSelfPermission(
                            this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
                    boolean answerGranted = ContextCompat.checkSelfPermission(
                            this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED;
                    if (!callGranted || !stateGranted) {
                        appendLog("Permission result: required Phone / Phone state permission missing.");
                        showError("Phone and call-state permissions are required for reliable redial.");
                        return;
                    }
                    if (disconnectMode != DISCONNECT_OFF && !answerGranted) {
                        appendLog("Permission result: auto-disconnect permission missing.");
                        showError("Auto-disconnect permission was not granted. Select Manual mode or grant Phone permission.");
                        return;
                    }
                    appendLog("Required phone permissions granted.");
                    performRepeatedCalls();
                });

        callButton.setOnClickListener(v -> {
            appendLog("Call button tapped. Validating settings and permissions...");
            disconnectMode = disconnectModeGroup.getCheckedRadioButtonId();
            if (disconnectMode == -1) disconnectMode = DISCONNECT_OFF;
            if (disconnectMode == DISCONNECT_PICKUP) {
                showError("After answer mode requires Default Phone App / InCallService. Select From call start or Manual until that integration is added.");
                return;
            }

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
                if (!ussd) saveLastUsedNumber(number, name);
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
                appendLog("Requesting missing Android phone permissions...");
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
            stopButtonWasPressed = true;
            if (wasRunning) {
                appendLog("Stop pressed. The redial queue and any scheduled next attempt have been cancelled. The current phone call is not ended automatically.");
            } else {
                appendLog("Stop pressed, but no redial session was running.");
            }
            stopCallingLoop();
            refreshCallButtonEnabled();
            if (wasRunning) {
                Snackbar.make(v, "Redial stopped. The current call, if connected, must be ended in the Phone app.",
                        Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void refreshCallButtonEnabled() {
        boolean manual = manualNumberEditText != null
                && manualNumberEditText.getText() != null
                && manualNumberEditText.getText().toString().trim().length() >= 4;
        boolean batch = batchNumbersEditText != null
                && batchNumbersEditText.getText().toString().trim().length() >= 4;
        boolean enabled = (manual || batch || number != null) && !redialSessionRunning;
        boolean sessionAvailable = !redialSessionInUse;
        callButton.setClickable(enabled && sessionAvailable);
        callButton.setEnabled(enabled && sessionAvailable);
        callButton.setAlpha(redialSessionRunning ? 1f
                : (enabled && sessionAvailable ? 1f : 0.65f));
        callButton.setBackgroundTintList(ColorStateList.valueOf(
                redialSessionRunning ? ACTION_GREEN : ACTION_BLUE));
        callButton.setTextColor(Color.WHITE);
        if (redialSessionRunning) {
            callButton.setText("Redialing...");
        } else if (name != null && !name.trim().isEmpty()) {
            callButton.setText(getString(R.string.ar_default_call_text, name));
        } else {
            callButton.setText(R.string.ar_call);
        }

        if (stopCallingButton != null) {
            stopCallingButton.setClickable(redialSessionRunning);
            stopCallingButton.setEnabled(redialSessionRunning);
            stopCallingButton.setAlpha(redialSessionRunning || stopButtonWasPressed ? 1f : 0.65f);
            stopCallingButton.setBackgroundTintList(ColorStateList.valueOf(
                    stopButtonWasPressed ? ACTION_RED : ACTION_BLUE));
            stopCallingButton.setTextColor(Color.WHITE);
            stopCallingButton.setText(stopButtonWasPressed ? "Stopped" : "Stop Redial");
        }
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
        if (redialSessionInUse) {
            showError("A redial session is already running. Stop it before starting another.");
            return;
        }
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
        if (disconnectMode == DISCONNECT_PICKUP) {
            showError("After answer mode needs the app to be the Default Phone App with InCallService. OFFHOOK cannot confirm an answer, so this mode is paused to prevent early hang-ups.");
            return;
        }

        ArrayList<String> requestedBatchNumbers = batchNumbers;
        stopCallingLoop();
        batchNumbers = requestedBatchNumbers;
        batchIndex = 0;
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

        if (calls > 1 && !runInBgCheckBox.isChecked()) {
            runInBgCheckBox.setChecked(true);
            prefs.edit().putBoolean("run_in_background", true).apply();
            appendLog("Background session enabled automatically so the next call can run while the Phone app is open.");
        }

        stopButtonWasPressed = false;
        sessionCompleted = false;
        stopCallingButton.setClickable(true);
        stopCallingButton.setEnabled(true);
        currentNoOfCalls.setVisibility(View.VISIBLE);
        currentNoOfCalls.setText(getString(R.string.ar_current_no_of_call, 0));
        callGapMillis = gapSeconds * 1000L;
        try {
            redialSessionInUse = true;
            redialSessionRunning = true;
            prefs.edit()
                    .putBoolean("redial_session_active", true)
                    .putBoolean("redial_call_in_progress", false)
                    .putLong("redial_session_heartbeat", System.currentTimeMillis())
                    .apply();
            handler.removeCallbacks(sessionHeartbeatRunnable);
            handler.postDelayed(sessionHeartbeatRunnable, 2000L);
            if (!isCallOverlayAccessibilityEnabled()) {
                appendLog("Connected-call Stop button is disabled. Enable it from the Stop Redial Button setting.");
            }
            waitingForAppVisibleLogged = false;
            refreshCallButtonEnabled();
            RedialSessionService.start(this);
            appendLog("Foreground session service started. The redial queue can stay active while the Phone app is open.");
            callButton.setClickable(false);
            callButton.setAlpha(1f);
            appendLog("Session started: " + calls + " " + (ussdMode ? "USSD attempt(s)." : "call attempt(s)."));
            appendLog("Call Gap configured: " + gapSeconds + " second(s), counted only after phone state becomes IDLE.");
            appendLog("Auto-disconnect mode: " + (disconnectMode == DISCONNECT_OFF
                    ? "Off (manual)." : "From call launch."));
            if (!ussdMode) {
                appendLog("Audio route requested: " + (prefs.getBoolean("call_on_speaker", false)
                        ? "Loudspeaker." : "Earpiece."));
            }
            registerCallStateListener();
            appendLog("First attempt queued after " +
                    (Constants.AUTOMATIC_REDIALER_INITIAL_DELAY / 1000L) + " second(s).");
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
                    boolean firstOffhookSignal = !sawCallState;
                    sawCallState = true;
                    isCallActive = true;
                    waitingForPickup = false;
                    if (firstOffhookSignal) {
                        appendLog("Phone state: OFFHOOK (dialing or active; Android does not confirm that the other person answered).");
                    }
                    if (disconnectMode == DISCONNECT_PICKUP && currentCallLaunched) {
                        waitingForPickup = false;
                        appendLog("OFFHOOK is not proof the other person answered. No after-answer timer started.");
                    }
                } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)
                        && currentCallLaunched) {
                    appendLog("Phone state: IDLE. Call ended (whether you or the other person disconnected); starting the configured Call Gap.");
                    // IDLE is the definitive end of the current call attempt.
                    // Do not require OFFHOOK first: an unanswered/ringing call can
                    // return directly to IDLE, and the call-gap timer must start then.
                    finishCurrentCall();
                }
            }
        };
        try {
            registerReceiver(callStateReceiver,
                    new android.content.IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED));
            appendLog("Phone-state listener registered.");
        } catch (Exception e) {
            callStateReceiver = null;
            throw e;
        }
    }

    private void clearDisconnectScheduling() {
        handler.removeCallbacks(callStatePollRunnable);
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
        // The after-answer mode is intentionally blocked until exact call
        // answer state is provided by an InCallService.
        if (disconnectMode == DISCONNECT_PICKUP) {
            waitingForPickup = false;
            pickupDisconnectSec = getDisconnectDelaySeconds();
            appendLog("After-answer mode selected. OFFHOOK will not start the timer because it cannot confirm that the other person answered.");
        }
    }

    private void scheduleDisconnectAfterCallStarts() {
        // Direct mode counts from the ACTION_CALL launch, not from before the intent
        // is dispatched. Pickup mode is armed in prepareDisconnectBeforeCall().
        if (disconnectMode == DISCONNECT_DIRECT) {
            appendLog("Call intent launched. Starting the from-call-start disconnect countdown.");
            scheduleEndCall(getDisconnectDelaySeconds());
        }
    }

    private void scheduleEndCall(int delaySeconds) {
        if (!currentCallLaunched || disconnectCountdownStarted) return;
        disconnectCountdownStarted = true;
        appendLog("Auto-disconnect timer scheduled for " + delaySeconds + " second(s).");
        Runnable endCallRunnable = () -> {
            if (!currentCallLaunched) return;
            appendLog("Auto-disconnect timer expired. Asking Android Telecom to end the call...");
            boolean hangUpRequested = endCallNow();
            appendLog(hangUpRequested
                    ? "Android Telecom accepted the end-call request. Verifying phone state..."
                    : "Android Telecom did not confirm the end-call request. Verifying phone state...");
            Runnable verifyDisconnect = () -> {
                if (!currentCallLaunched) return;
                if (isPhoneCallIdle()) {
                    appendLog("Disconnect verified: phone state is IDLE.");
                    sawCallState = true;
                    finishCurrentCall();
                } else {
                    appendLog("Disconnect not verified after 1.5 seconds. The call may still be active; Call Gap will not start yet.");
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
        if (hadActiveAttempt) updateCurrentCallHistory("Call ended");
        currentCallLaunched = false;
        if (prefs != null) prefs.edit()
                .putBoolean("redial_call_in_progress", false)
                .remove("redial_call_started_at")
                .apply();
        sawCallState = false;
        clearDisconnectScheduling();
        updatePipAutoEnter(false);
        if (redialSessionRunning && hadActiveAttempt) {
            restoreAutomaticRedialerFromPip();
        }
        if (!redialSessionRunning || !hadActiveAttempt) {
            refreshSessionFeed();
            return;
        }
        appendLog("Call attempt finished. Remaining attempts: " + remaining + ".");
        if (remaining <= 0) {
            appendLog("No attempts remaining. Finishing the redial session.");
            finishCalling();
            return;
        }
        appendLog("Call Gap started: waiting " + (callGapMillis / 1000L)
                + " second(s) before the next attempt.");
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
            // TelecomManager.placeCall() is used for normal calls so the
            // system Telecom service can receive the queued call while the
            // Automatic Redialer Activity is paused behind the Phone UI.
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
                prefs.edit()
                        .putBoolean("redial_call_in_progress", !ussdMode)
                        .putLong("redial_call_started_at", System.currentTimeMillis())
                        .apply();
                sawCallState = false;
                remaining--;
                int completed = totalCalls - remaining;
                currentNoOfCalls.setText(getString(R.string.ar_current_no_of_call, completed));
                currentAttemptNumber = target;
                currentAttemptLabel = (!ussdMode && target.equals(number)
                        && name != null && !name.trim().isEmpty()) ? name : target;
                currentAttemptStartedAt = System.currentTimeMillis();
                beginCallHistory(target, currentAttemptLabel, ussdMode ? "USSD started" : "Calling");
                if (!ussdMode) saveLastUsedNumber(target, currentAttemptLabel);
                appendLog("Attempt " + completed + " of " + totalCalls + ": preparing "
                        + (ussdMode ? "USSD session." : "outgoing call."));

                // Arm pickup state before Telecom is asked to place the call.
                // cannot race the pickup timer initialization.
                prepareDisconnectBeforeCall();
                Uri dialUri = Uri.fromParts("tel", target, null);
                if (ussdMode) {
                    appendLog("Opening the Android phone app for the USSD request.");
                    startActivity(new Intent(Intent.ACTION_CALL, dialUri));
                } else {
                    TelecomManager telecom =
                            (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                    if (telecom == null) {
                        throw new IllegalStateException("Android Telecom service is unavailable.");
                    }
                    preparePipForCurrentCall();
                    Bundle callExtras = new Bundle();
                    // Preserve the user's selected speaker / earpiece preference.
                    callExtras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE,
                            prefs.getBoolean("call_on_speaker", false));
                    appendLog("Requesting Android Telecom to place the outgoing call (without requiring this Activity to be foreground).");
                    telecom.placeCall(dialUri, callExtras);
                }
                appendLog("Call request sent to Android.");
                scheduleDisconnectAfterCallStarts();
                if (!ussdMode) {
                    handler.removeCallbacks(callStatePollRunnable);
                    handler.postDelayed(callStatePollRunnable, 1000L);
                    appendLog("Phone-state polling fallback started (checks once per second).");
                }

                if (batchNumbers != null && !batchNumbers.isEmpty()) batchIndex++;
            } catch (SecurityException e) {
                updateCurrentCallHistory("Call launch failed: permission denied");
                currentCallLaunched = false;
                prefs.edit().putBoolean("redial_call_in_progress", false).remove("redial_call_started_at").apply();
                stopCallingLoop();
                showError("Android denied the call. Check Phone permission and SIM availability.");
            } catch (Exception e) {
                updateCurrentCallHistory("Call launch failed");
                currentCallLaunched = false;
                prefs.edit().putBoolean("redial_call_in_progress", false).remove("redial_call_started_at").apply();
                stopCallingLoop();
                showError("Call could not be started: " + e.getMessage());
            }
    }

    private boolean isCallFlowActive() {
        return currentCallLaunched || isCallActive || waitingForPickup;
    }

    private void finishCalling() {
        sessionCompleted = true;
        appendLog("Redial session completed successfully: all configured attempts processed.");
        stopCallingLoop();
        currentNoOfCalls.setVisibility(View.INVISIBLE);
        Snackbar.make(callButton, "Redial session completed.", Snackbar.LENGTH_LONG).show();
    }

    private void stopCallingLoop() {
        if (currentCallLaunched && currentHistoryId > 0L) {
            updateCurrentCallHistory("Session stopped; call may still be active");
        }
        handler.removeCallbacks(backgroundStopRunnable);
        handler.removeCallbacks(callStepRunnable);
        handler.removeCallbacks(sessionHeartbeatRunnable);
        if (prefs != null) {
            prefs.edit()
                    .putBoolean("redial_session_active", false)
                    .putBoolean("redial_call_in_progress", false)
                    .remove("redial_session_heartbeat")
                    .remove("redial_call_started_at")
                    .apply();
        }
        clearDisconnectScheduling();
        try {
            RedialSessionService.stop(this);
        } catch (Exception ignored) { }
        if (redialSessionRunning) redialSessionInUse = false;
        redialSessionRunning = false;
        updatePipAutoEnter(false);
        if (callButton != null) {
            callButton.setAlpha(1f);
            refreshCallButtonEnabled();
        }
        if (stopCallingButton != null) {
            stopCallingButton.setClickable(false);
            stopCallingButton.setEnabled(false);
        }
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
        refreshCallButtonEnabled();
        refreshPipPanel();
        refreshSessionFeed();
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

    private void saveLastUsedNumber(String value, String displayName) {
        if (value == null || value.trim().isEmpty()) return;
        String safeName = displayName == null || displayName.trim().isEmpty()
                ? value : displayName.trim();
        prefs.edit()
                .putString(PREF_LAST_NUMBER, value)
                .putString(PREF_LAST_NAME, safeName)
                .apply();
    }

    private JSONArray readCallHistory() {
        try {
            return new JSONArray(prefs.getString(PREF_CALL_HISTORY, "[]"));
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private void beginCallHistory(String value, String displayName, String initialStatus) {
        currentHistoryId = System.currentTimeMillis();
        currentAttemptStartedAt = currentHistoryId;
        currentAttemptNumber = value == null ? "" : value;
        currentAttemptLabel = displayName == null ? currentAttemptNumber : displayName;
        JSONObject entry = new JSONObject();
        try {
            entry.put("id", currentHistoryId);
            entry.put("startedAt", currentAttemptStartedAt);
            entry.put("number", currentAttemptNumber);
            entry.put("label", currentAttemptLabel);
            entry.put("status", initialStatus);
        } catch (JSONException ignored) { }

        JSONArray oldHistory = readCallHistory();
        JSONArray newHistory = new JSONArray();
        newHistory.put(entry);
        for (int i = 0; i < oldHistory.length() && newHistory.length() < MAX_CALL_HISTORY; i++) {
            JSONObject oldEntry = oldHistory.optJSONObject(i);
            if (oldEntry != null && oldEntry.optLong("id") != currentHistoryId) {
                newHistory.put(oldEntry);
            }
        }
        prefs.edit().putString(PREF_CALL_HISTORY, newHistory.toString()).apply();
    }

    private void updateCurrentCallHistory(String status) {
        if (currentHistoryId <= 0L) return;
        JSONArray oldHistory = readCallHistory();
        JSONArray updatedHistory = new JSONArray();
        long endedAt = System.currentTimeMillis();
        long durationSeconds = Math.max(0L, (endedAt - currentAttemptStartedAt) / 1000L);
        for (int i = 0; i < oldHistory.length(); i++) {
            JSONObject entry = oldHistory.optJSONObject(i);
            if (entry == null) continue;
            if (entry.optLong("id") == currentHistoryId) {
                try {
                    entry.put("status", status);
                    entry.put("endedAt", endedAt);
                    entry.put("durationSeconds", durationSeconds);
                } catch (JSONException ignored) { }
            }
            updatedHistory.put(entry);
        }
        prefs.edit().putString(PREF_CALL_HISTORY, updatedHistory.toString()).apply();
        currentHistoryId = -1L;
    }

    private void setupPipPanel(LinearLayout root) {
        pipPanel = new LinearLayout(this);
        pipPanel.setOrientation(LinearLayout.VERTICAL);
        pipPanel.setGravity(Gravity.CENTER_VERTICAL);
        int padding = dp(8);
        pipPanel.setPadding(padding, padding, padding, padding);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(13, 21, 37));
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), Color.rgb(56, 112, 190));
        pipPanel.setBackground(background);
        pipPanel.setVisibility(View.GONE);

        TextView title = new TextView(this);
        title.setText("AUTOMATIC REDIALER");
        title.setTextColor(Color.rgb(125, 181, 255));
        title.setTextSize(11);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        pipPanel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pipStatusText = new TextView(this);
        pipStatusText.setTextColor(Color.WHITE);
        pipStatusText.setTextSize(13);
        pipStatusText.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(4);
        pipPanel.addView(pipStatusText, statusParams);

        pipNumberText = new TextView(this);
        pipNumberText.setTextColor(Color.rgb(205, 218, 236));
        pipNumberText.setTextSize(12);
        pipNumberText.setMaxLines(2);
        pipNumberText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams numberParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        numberParams.topMargin = dp(2);
        pipPanel.addView(pipNumberText, numberParams);

        pipLogText = new TextView(this);
        pipLogText.setTextColor(Color.rgb(170, 190, 215));
        pipLogText.setTextSize(11);
        pipLogText.setTypeface(android.graphics.Typeface.MONOSPACE);
        pipLogText.setLineSpacing(dp(2), 1.0f);
        pipLogText.setMaxLines(7);
        pipLogText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        logParams.topMargin = dp(5);
        pipPanel.addView(pipLogText, logParams);

        root.addView(pipPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refreshPipPanel();
    }

    private void setupSessionFeedPanel(LinearLayout root) {
        sessionFeedPanel = new LinearLayout(this);
        sessionFeedPanel.setOrientation(LinearLayout.VERTICAL);
        sessionFeedPanel.setPadding(dp(16), dp(18), dp(16), dp(14));
        sessionFeedPanel.setBackgroundColor(Color.rgb(10, 17, 31));
        sessionFeedPanel.setVisibility(View.GONE);

        TextView title = new TextView(this);
        title.setText("LIVE SESSION FEED");
        title.setTextColor(Color.rgb(125, 181, 255));
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        sessionFeedPanel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sessionFeedStatusText = new TextView(this);
        sessionFeedStatusText.setTextColor(Color.WHITE);
        sessionFeedStatusText.setTextSize(16);
        sessionFeedStatusText.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(12);
        sessionFeedPanel.addView(sessionFeedStatusText, statusParams);

        sessionFeedNumberText = new TextView(this);
        sessionFeedNumberText.setTextColor(Color.rgb(205, 218, 236));
        sessionFeedNumberText.setTextSize(15);
        sessionFeedNumberText.setMaxLines(2);
        sessionFeedNumberText.setPadding(0, dp(4), 0, dp(2));
        sessionFeedPanel.addView(sessionFeedNumberText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sessionFeedCountText = new TextView(this);
        sessionFeedCountText.setTextColor(Color.rgb(125, 181, 255));
        sessionFeedCountText.setTextSize(13);
        sessionFeedPanel.addView(sessionFeedCountText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sessionFeedScroll = new ScrollView(this);
        sessionFeedScroll.setFillViewport(false);
        sessionFeedScroll.setBackgroundColor(Color.rgb(15, 25, 43));
        sessionFeedLogText = new TextView(this);
        sessionFeedLogText.setTextColor(Color.rgb(220, 230, 243));
        sessionFeedLogText.setTextSize(13);
        sessionFeedLogText.setTypeface(android.graphics.Typeface.MONOSPACE);
        sessionFeedLogText.setLineSpacing(dp(3), 1.0f);
        sessionFeedLogText.setTextIsSelectable(true);
        sessionFeedLogText.setPadding(dp(10), dp(10), dp(10), dp(10));
        sessionFeedScroll.addView(sessionFeedLogText, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        logParams.topMargin = dp(10);
        logParams.bottomMargin = dp(12);
        sessionFeedPanel.addView(sessionFeedScroll, logParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        sessionFeedStopButton = new Button(this);
        sessionFeedStopButton.setText("STOP REDIAL");
        sessionFeedStopButton.setTextColor(Color.WHITE);
        sessionFeedStopButton.setAllCaps(false);
        sessionFeedStopButton.setBackgroundTintList(ColorStateList.valueOf(ACTION_RED));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        stopParams.rightMargin = dp(8);
        actions.addView(sessionFeedStopButton, stopParams);

        sessionFeedDialerButton = new Button(this);
        sessionFeedDialerButton.setText("Back to Dialer");
        sessionFeedDialerButton.setTextColor(Color.WHITE);
        sessionFeedDialerButton.setAllCaps(false);
        sessionFeedDialerButton.setBackgroundTintList(ColorStateList.valueOf(ACTION_BLUE));
        actions.addView(sessionFeedDialerButton, new LinearLayout.LayoutParams(0, dp(52), 1f));
        sessionFeedPanel.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sessionFeedStopButton.setOnClickListener(v -> {
            boolean wasRunning = redialSessionRunning;
            stopButtonWasPressed = true;
            if (wasRunning) {
                appendLog("Stop pressed from the full-screen Live Session Feed. Future attempts are cancelled; any active call must be ended in the Phone app.");
            } else {
                appendLog("Stop pressed, but the redial session is not running.");
            }
            stopCallingLoop();
            refreshCallButtonEnabled();
            refreshSessionFeed();
            if (wasRunning) {
                Snackbar.make(sessionFeedPanel,
                        "Redial stopped. End any connected call in the Phone app.",
                        Snackbar.LENGTH_LONG).show();
            }
        });
        sessionFeedDialerButton.setOnClickListener(v -> showDialerScreen());

        root.addView(sessionFeedPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refreshSessionFeed();
    }

    private void showSessionFeed() {
        showingSessionFeed = true;
        if (standardHeaderView != null) standardHeaderView.setVisibility(View.GONE);
        if (standardContentView != null) standardContentView.setVisibility(View.GONE);
        if (pipPanel != null) pipPanel.setVisibility(View.GONE);
        if (sessionFeedPanel != null) sessionFeedPanel.setVisibility(View.VISIBLE);
        refreshSessionFeed();
    }

    private void showDialerScreen() {
        showingSessionFeed = false;
        if (standardHeaderView != null) standardHeaderView.setVisibility(View.VISIBLE);
        if (standardContentView != null) standardContentView.setVisibility(View.VISIBLE);
        if (pipPanel != null) pipPanel.setVisibility(View.GONE);
        if (sessionFeedPanel != null) sessionFeedPanel.setVisibility(View.GONE);
        refreshCallButtonEnabled();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void preparePipForCurrentCall() {
        if (!pipFeatureAvailable) {
            if (!pipUnavailableLogged) {
                appendLog("Picture-in-Picture is not supported by this device; call UI will use the normal Phone app.");
                pipUnavailableLogged = true;
            }
            updatePipAutoEnter(false);
            return;
        }
        updatePipAutoEnter(true);
        // Enter PiP before Telecom brings the system Phone UI to the foreground.
        // This makes the compact Live Log visible above the call screen without
        // requesting SYSTEM_ALERT_WINDOW / Display over other apps permission.
        if (!isInPictureInPictureMode()) {
            try {
                enterPictureInPictureMode(new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9))
                        .setActions(buildPipActions())
                        .build());
                appendLog("Opening compact Live Log before the Phone call screen.");
            } catch (RuntimeException e) {
                appendLog("PiP could not be opened; the Phone app will open normally. " + e.getMessage());
            }
        }
    }

    private void updatePipAutoEnter(boolean enabled) {
        pipAutoEnterAllowed = enabled && pipFeatureAvailable;
        if (android.os.Build.VERSION.SDK_INT < 26 || !pipFeatureAvailable) return;
        try {
            PictureInPictureParams.Builder builder =
                    new PictureInPictureParams.Builder()
                            .setAspectRatio(new Rational(16, 9))
                            .setActions(buildPipActions());
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                builder.setAutoEnterEnabled(pipAutoEnterAllowed);
            }
            setPictureInPictureParams(builder.build());
        } catch (RuntimeException e) {
            appendLog("Could not update Picture-in-Picture settings: " + e.getMessage());
        }
    }

    private java.util.List<RemoteAction> buildPipActions() {
        Intent stopIntent = new Intent(this, AutomaticRedialerActivity.class);
        stopIntent.setAction(ACTION_PIP_STOP_SESSION);
        stopIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent stopPendingIntent = PendingIntent.getActivity(
                this, 4103, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        RemoteAction stopAction = new RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "Stop redial", "Stop the automatic redial session", stopPendingIntent);
        stopAction.setEnabled(redialSessionRunning);

        Intent feedIntent = new Intent(this, AutomaticRedialerActivity.class);
        feedIntent.setAction(ACTION_PIP_RESTORE_DIALER);
        feedIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent feedPendingIntent = PendingIntent.getActivity(
                this, 4104, feedIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        RemoteAction feedAction = new RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_menu_view),
                "Open feed", "Open the full-screen Live Session Feed", feedPendingIntent);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            stopAction.setShouldShowIcon(true);
            feedAction.setShouldShowIcon(true);
        }
        ArrayList<RemoteAction> actions = new ArrayList<>();
        actions.add(stopAction);
        actions.add(feedAction);
        return actions;
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Android 8–11 need an explicit PiP entry request. Android 12+
        // uses setAutoEnterEnabled() for the smoother automatic transition.
        if (android.os.Build.VERSION.SDK_INT >= 26
                && android.os.Build.VERSION.SDK_INT < 31
                && pipAutoEnterAllowed
                && currentCallLaunched
                && !isInPictureInPictureMode()) {
            try {
                enterPictureInPictureMode(new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9))
                        .setActions(buildPipActions())
                        .build());
            } catch (RuntimeException e) {
                appendLog("Could not enter Picture-in-Picture: " + e.getMessage());
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(
            boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        boolean showDialer = !isInPictureInPictureMode && !showingSessionFeed;
        if (standardHeaderView != null) {
            standardHeaderView.setVisibility(showDialer ? View.VISIBLE : View.GONE);
        }
        if (standardContentView != null) {
            standardContentView.setVisibility(showDialer ? View.VISIBLE : View.GONE);
        }
        if (pipPanel != null) {
            pipPanel.setVisibility(isInPictureInPictureMode ? View.VISIBLE : View.GONE);
        }
        if (sessionFeedPanel != null) {
            sessionFeedPanel.setVisibility(
                    !isInPictureInPictureMode && showingSessionFeed ? View.VISIBLE : View.GONE);
        }
        refreshPipPanel();
        appendLog(isInPictureInPictureMode
                ? "Compact Live Log opened in Picture-in-Picture."
                : "Returned from Picture-in-Picture to the full Automatic Redialer screen.");
    }

    private void refreshPipPanel() {
        if (pipPanel == null) return;
        String status;
        if (redialSessionRunning && currentCallLaunched) {
            int completed = Math.max(0, totalCalls - remaining);
            status = "CALL IN PROGRESS  •  " + completed + "/" + totalCalls;
        } else if (redialSessionRunning) {
            status = "WAITING FOR NEXT CALL  •  " + remaining + " LEFT";
        } else if (sessionCompleted) {
            status = "SESSION COMPLETE";
        } else if (stopButtonWasPressed) {
            status = "REDIAL STOPPED";
        } else {
            status = "SESSION NOT RUNNING";
        }
        if (pipStatusText != null) pipStatusText.setText(status);
        String target = currentAttemptLabel;
        if (target == null || target.trim().isEmpty()) target = currentAttemptNumber;
        if (target == null || target.trim().isEmpty()) target = name;
        if (target == null || target.trim().isEmpty()) target = "No active number";
        if (pipNumberText != null) pipNumberText.setText(target);
        if (pipLogText != null) pipLogText.setText(getLatestLogLines(7));
    }

    private void refreshSessionFeed() {
        if (sessionFeedPanel == null) return;

        String status;
        if (redialSessionRunning && currentCallLaunched) {
            int completed = Math.max(0, totalCalls - remaining);
            status = "CALL IN PROGRESS";
            if (sessionFeedCountText != null) {
                sessionFeedCountText.setText("Attempt " + completed + " of " + totalCalls);
            }
        } else if (redialSessionRunning) {
            status = "WAITING FOR NEXT ATTEMPT";
            if (sessionFeedCountText != null) {
                sessionFeedCountText.setText("Next call will start after the configured Call Gap");
            }
        } else if (sessionCompleted) {
            status = "SESSION COMPLETED";
            if (sessionFeedCountText != null) {
                sessionFeedCountText.setText("All configured attempts have been processed");
            }
        } else if (stopButtonWasPressed) {
            status = "REDIAL STOPPED";
            if (sessionFeedCountText != null) {
                sessionFeedCountText.setText("No further attempts will be placed");
            }
        } else {
            status = "SESSION MONITOR";
            if (sessionFeedCountText != null) {
                sessionFeedCountText.setText("Live activity and call events");
            }
        }

        if (sessionFeedStatusText != null) sessionFeedStatusText.setText(status);
        String target = currentAttemptLabel;
        if (target == null || target.trim().isEmpty()) target = currentAttemptNumber;
        if (target == null || target.trim().isEmpty()) target = name;
        if (target == null || target.trim().isEmpty()) target = "No active number";
        if (sessionFeedNumberText != null) sessionFeedNumberText.setText(target);
        if (sessionFeedLogText != null) {
            sessionFeedLogText.setText(liveLogBuffer.toString());
            if (sessionFeedScroll != null) {
                sessionFeedScroll.post(() -> sessionFeedScroll.fullScroll(View.FOCUS_DOWN));
            }
        }
        if (sessionFeedStopButton != null) {
            sessionFeedStopButton.setEnabled(redialSessionRunning);
            sessionFeedStopButton.setAlpha(redialSessionRunning ? 1f : 0.65f);
            sessionFeedStopButton.setText(redialSessionRunning ? "STOP REDIAL" : "STOPPED");
        }
    }

    private String getLatestLogLines(int maxLines) {
        String[] lines = liveLogBuffer.toString().split("\\n");
        StringBuilder latest = new StringBuilder();
        int start = Math.max(0, lines.length - maxLines);
        for (int i = start; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            if (latest.length() > 0) latest.append("\n");
            latest.append(lines[i]);
        }
        return latest.length() == 0 ? getString(R.string.ar_live_log_empty) : latest.toString();
    }

    private void restoreAutomaticRedialerFromPip() {
        if (android.os.Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode()) {
            try {
                Intent restoreIntent = new Intent(this, AutomaticRedialerActivity.class);
                restoreIntent.setAction(ACTION_PIP_RESTORE_DIALER);
                restoreIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                appendLog("Call ended. Opening the full-screen Live Session Feed.");
                startActivity(restoreIntent);
            } catch (RuntimeException e) {
                appendLog("Could not expand the Live Session Feed from PiP: " + e.getMessage());
                showSessionFeed();
            }
        } else {
            appendLog("Call ended. Showing the full-screen Live Session Feed inside Automatic Redialer.");
            showSessionFeed();
        }
    }

    private void handlePipActionIntent(Intent intent) {
        if (intent == null) return;
        if (ACTION_PIP_STOP_SESSION.equals(intent.getAction())) {
            stopButtonWasPressed = true;
            appendLog("Stop selected from the Picture-in-Picture controls.");
            stopCallingLoop();
            refreshCallButtonEnabled();
            Snackbar.make(callButton, "Redial session stopped.", Snackbar.LENGTH_LONG).show();
        } else if (ACTION_PIP_RESTORE_DIALER.equals(intent.getAction())) {
            appendLog("Full-screen Live Session Feed opened.");
            showSessionFeed();
        } else if (CallControlAccessibilityService.ACTION_STOP_REDIAL.equals(intent.getAction())) {
            stopButtonWasPressed = true;
            appendLog("Stop Redial tapped from the connected-call overlay or foreground notification.");
            stopCallingLoop();
            refreshCallButtonEnabled();
            refreshSessionFeed();
            if (callButton != null) {
                Snackbar.make(callButton, "Redial stopped. The connected call is not ended.", Snackbar.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePipActionIntent(intent);
    }

    private boolean isCallOverlayAccessibilityEnabled() {
        String enabledServices = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null || enabledServices.isEmpty()) return false;
        ComponentName expected = new ComponentName(this, CallControlAccessibilityService.class);
        for (String entry : enabledServices.split(":")) {
            ComponentName enabled = ComponentName.unflattenFromString(entry);
            if (expected.equals(enabled)) return true;
        }
        return false;
    }

    private void updateCallOverlayButtonLabel() {
        if (enableCallOverlayButton == null) return;
        enableCallOverlayButton.setText(isCallOverlayAccessibilityEnabled()
                ? R.string.ar_stop_overlay_enabled : R.string.ar_enable_stop_overlay);
    }

    private void openCallOverlayAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Snackbar.make(enableCallOverlayButton,
                    R.string.ar_stop_overlay_settings_hint, Snackbar.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            showError("Could not open Accessibility settings: " + e.getMessage());
        }
    }

    private void showError(String message) {
        appendLog("ERROR: " + message);
        Snackbar.make(callButton, message, Snackbar.LENGTH_LONG).show();
    }

    private void appendLog(String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> appendLog(message));
            return;
        }
        String entry = "[" + logTimeFormat.format(new Date()) + "] " + message + "\n";
        liveLogBuffer.append(entry);
        // Keep the on-screen log bounded so long redial sessions cannot use
        // unbounded memory. The latest entries are always retained.
        while (liveLogBuffer.length() > 18000) {
            int nextLine = liveLogBuffer.indexOf("\n");
            if (nextLine < 0) {
                liveLogBuffer.setLength(0);
                break;
            }
            liveLogBuffer.delete(0, nextLine + 1);
        }
        refreshLiveLogView();
        refreshPipPanel();
        refreshSessionFeed();
    }

    private void refreshLiveLogView() {
        if (liveLogText == null) return;
        liveLogText.setText(liveLogBuffer.toString());
        if (liveLogScroll != null) {
            liveLogScroll.post(() -> liveLogScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void showLiveLogDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(android.graphics.Color.rgb(11, 18, 32));
        TextView logText = new TextView(this);
        logText.setTextColor(android.graphics.Color.rgb(215, 226, 240));
        logText.setTextSize(11);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setPadding(pad, pad, pad, pad);
        scroll.addView(logText, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (360 * getResources().getDisplayMetrics().density)));

        liveLogText = logText;
        liveLogScroll = scroll;
        liveLogDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.ar_live_log_title)
                .setView(panel)
                .setNeutralButton(R.string.ar_clear_log, (dialog, which) -> {
                    liveLogBuffer.setLength(0);
                    appendLog("Log cleared.");
                })
                .setPositiveButton(android.R.string.ok, null)
                .create();
        liveLogDialog.setOnDismissListener(dialog -> {
            liveLogText = null;
            liveLogScroll = null;
            liveLogDialog = null;
        });
        liveLogDialog.show();
        refreshLiveLogView();
    }

    private void selectLastCall() {
        JSONArray history = readCallHistory();
        String previousNumber = null;
        String previousLabel = null;
        for (int i = 0; i < history.length(); i++) {
            JSONObject entry = history.optJSONObject(i);
            if (entry == null) continue;
            String candidate = entry.optString("number", "");
            if (PhoneNumberHelper.normalizePhoneNumber(candidate, selectedCountryCode()) != null) {
                previousNumber = candidate;
                previousLabel = entry.optString("label", candidate);
                break;
            }
        }
        if (previousNumber == null || previousNumber.trim().isEmpty()) {
            previousNumber = prefs.getString(PREF_LAST_NUMBER, null);
            previousLabel = prefs.getString(PREF_LAST_NAME, previousNumber);
        }
        if (previousNumber == null || previousNumber.trim().isEmpty()) {
            showError("No previous number is saved yet.");
            return;
        }
        applySelectedNumber(previousNumber, previousLabel);
        appendLog("Last call selected and filled into the number field.");
    }

    private void applySelectedNumber(String selectedNumber, String selectedLabel) {
        String normalized = PhoneNumberHelper.normalizePhoneNumber(selectedNumber, selectedCountryCode());
        if (normalized == null) {
            showError("The selected history entry does not contain a valid phone number.");
            return;
        }
        number = normalized;
        name = selectedLabel == null || selectedLabel.trim().isEmpty()
                ? normalized : selectedLabel;
        saveLastUsedNumber(number, name);
        manualNumberEditText.setText(number);
        callButton.setText(getString(R.string.ar_default_call_text, name));
        refreshCallButtonEnabled();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (redialSessionRunning) appendLog("Automatic Redialer screen resumed.");
        if (currentCallLaunched) {
            try {
                TelephonyManager phone =
                        (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                if (phone != null && phone.getCallState() == TelephonyManager.CALL_STATE_IDLE) {
                    appendLog("Phone state check on return: IDLE. The previous call attempt has ended.");
                    finishCurrentCall();
                } else if (phone != null && phone.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK) {
                    if (!sawCallState) {
                        appendLog("Phone state check on return: OFFHOOK (dialing or active; answer is not confirmed).");
                    }
                    sawCallState = true;
                    isCallActive = true;
                    if (disconnectMode == DISCONNECT_PICKUP) {
                        appendLog("Returned OFFHOOK state is not an answer confirmation. No after-answer timer started.");
                    }
                }
            } catch (SecurityException ignored) { }
        }
        handler.removeCallbacks(backgroundStopRunnable);
        updateCallOverlayButtonLabel();
        refreshCallButtonEnabled();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        if (redialSessionRunning) appendLog("App screen paused (for example, while the phone app is open).");
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isChangingConfigurations()) return;
        if (isFinishing()) {
            if (redialSessionRunning) appendLog("Automatic Redialer screen is closing; stopping the session.");
            stopCallingLoop();
            return;
        }
        boolean runInBackground = prefs != null && prefs.getBoolean("run_in_background", false);
        if (!runInBackground) handler.postDelayed(backgroundStopRunnable, 3000);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("automatic_redialer_live_log", liveLogBuffer.toString());
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopCallingLoop();
        super.onDestroy();
    }
}
