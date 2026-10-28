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
import android.telephony.TelephonyManager;
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
import com.util.databinding.ActivityAutomaticRedialerBinding;
import com.util.main.MainActivity;
import com.util.util.Constants;

import java.lang.reflect.Method;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutomaticRedialerActivity extends AppCompatActivity {

    private Timer timer = null;
    private Context baseContext = null;
    private String name = null;
    private String number = null;
    private TextView noOfCallsTextView = null;
    private Button stopCallingButton = null;
    private TextView currentNoOfCalls = null;
    private TextView callGapTextView = null;
    private android.widget.EditText manualNumberEditText = null;
    private android.widget.EditText disconnectSecEditText = null;
    private SharedPreferences prefs;
    private android.widget.Spinner countryCodeSpinner;
    private android.widget.RadioGroup disconnectModeGroup;
    private final String[] countryCodes = {"+91", "+1", "+44", "+971", "+61", "+65", "+81", "+49", "+33", "+86", "+92", "+880", "+94", "+977", "+966", "+234", "+27", "+60", "+20", "+7"};
    private android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private java.util.ArrayList<android.os.Handler> disconnectHandlers = new java.util.ArrayList<>();
    private java.util.ArrayList<Runnable> disconnectRunnables = new java.util.ArrayList<>();
    private android.content.BroadcastReceiver callStateReceiver = null;
    private boolean isCallActive = false;
    private int pickupDisconnectSec = 0;
    private boolean waitingForPickup = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        myInit();

        noOfCallsTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!noOfCallsTextView.getText().toString().isEmpty() && Integer.parseInt(noOfCallsTextView.getText().toString()) > Integer.parseInt(getString(R.string.ar_max_no_of_calls)))
                    noOfCallsTextView.setText(R.string.ar_max_no_of_calls);
            }
        });

        callGapTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!callGapTextView.getText().toString().isEmpty() && Integer.parseInt(callGapTextView.getText().toString()) > Integer.parseInt(getString(R.string.ar_max_call_gap)))
                    callGapTextView.setText(R.string.ar_max_call_gap);
            }
        });

        currentNoOfCalls.setVisibility(View.INVISIBLE);

        Button selectContactButton = findViewById(R.id.ar_select_contact);
        Button callButton = findViewById(R.id.ar_button_call);
        callButton.setText(getString(R.string.ar_default_call_text, ""));
        callButton.setClickable(false);
        stopCallingButton = findViewById(R.id.ar_stop_call);
        stopCallingButton.setClickable(false);

        // select contact click listener
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
                                callButton.setText(getString(R.string.ar_default_call_text, name));
                                callButton.setClickable(true);
                                callButton.setBackgroundColor(MainActivity.COLOR10_DARK);
                                Snackbar.make(findViewById(R.id.ar_select_contact), "Contact selected: " + name, Snackbar.LENGTH_LONG).setAction("Action", null).show();
                            } else {
                                callButton.setClickable(false);
                                callButton.setBackgroundColor(MainActivity.COLOR10_LIGHT);
                                Snackbar.make(findViewById(R.id.ar_select_contact), "No contact is selected", Snackbar.LENGTH_LONG).setAction("Action", null).show();
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
                    System.out.println("Already have the permission of " + Manifest.permission.READ_CONTACTS);
                    getContact.launch(null);

                } else {
                    System.out.println("Asking for permission " + Manifest.permission.READ_CONTACTS);
                    getContact.launch(null);
                }
            }
        });

        // call button click listener
        callButton.setOnClickListener(new View.OnClickListener() {
            final ActivityResultLauncher<String> requestCallingPermissionLauncher =
                    registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                        System.out.println("Is calling permission granted: [" + isGranted + "]");
                        if (isGranted) {
                            performRepeatedCalls();
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
                if (manualNumberEditText != null && manualNumberEditText.getText() != null) {
                    String manualNum = manualNumberEditText.getText().toString().trim();
                    if (!manualNum.isEmpty()) {
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
                }
                if (ContextCompat.checkSelfPermission(baseContext, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    System.out.println("Already have the permission " + Manifest.permission.CALL_PHONE);
                    performRepeatedCalls();
                } else {
                    System.out.println("Asking for permission " + Manifest.permission.CALL_PHONE);
                    requestCallingPermissionLauncher.launch(Manifest.permission.CALL_PHONE);
                }
            }
        });

        stopCallingButton.setOnClickListener(v -> {
            stopAllDisconnectionInternal();
            if (timer != null) {
                System.out.println("Cancel calling");
                currentNoOfCalls.setVisibility(View.INVISIBLE);
                stopCallingButton.setClickable(false);
                stopCallingButton.setBackgroundColor(MainActivity.COLOR30_LIGHT);
                timer.cancel();
                timer = null;
                Snackbar.make(v, "Calling stopped to " + name, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void myInit() {
        ActivityAutomaticRedialerBinding binding = ActivityAutomaticRedialerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        baseContext = getBaseContext();
        prefs = getSharedPreferences("ar_prefs", Context.MODE_PRIVATE);

        // Header
        TextView headerTxt = findViewById(R.id.app_header);
        headerTxt.setText(R.string.automatic_redialer);

        // Main menu button
        Button mainMenuBtn = findViewById(R.id.main_menu);
        mainMenuBtn.setOnClickListener(v -> startActivity(new Intent(baseContext, MainActivity.class)));

        noOfCallsTextView = findViewById(R.id.ar_no_of_calls);
        callGapTextView = findViewById(R.id.ar_call_gap);
        currentNoOfCalls = findViewById(R.id.ar_current_call_number);
        manualNumberEditText = findViewById(R.id.ar_manual_number);
        disconnectSecEditText = findViewById(R.id.ar_disconnect_sec);
        countryCodeSpinner = findViewById(R.id.ar_country_code_spinner);
        disconnectModeGroup = findViewById(R.id.ar_disconnect_mode_group);

        // Setup country code spinner
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
        if (disconnectModeGroup != null && savedMode != -1) {
            if (disconnectModeGroup.findViewById(savedMode) != null) {
                disconnectModeGroup.check(savedMode);
            }
        }
        if (disconnectModeGroup != null) {
            if (disconnectModeGroup.findViewById(savedMode) != null) {
                disconnectModeGroup.check(savedMode);
            }
        }

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
                            }
                        }
                    }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private void performRepeatedCalls() {
        if (!noOfCallsTextView.getText().toString().isEmpty() && number != null && !number.isEmpty()) {
            stopCallingButton.setBackgroundColor(MainActivity.COLOR30_DARK);
            stopCallingButton.setClickable(true);
        if (timer != null)
            timer.cancel();
        if (callGapTextView.getText().toString().isEmpty())
            callGapTextView.setText(R.string.ar_default_call_gap);
        // Save settings
        try {
            if (prefs != null) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("no_of_calls", noOfCallsTextView.getText().toString());
                editor.putString("call_gap", callGapTextView.getText().toString());
                if (disconnectSecEditText != null && disconnectSecEditText.getText() != null) {
                    editor.putString("disconnect_sec", disconnectSecEditText.getText().toString());
                }
                editor.apply();
            }
        } catch (Exception e) {
        }
        if (prefs != null) {
            SharedPreferences.Editor editor = prefs.edit();
            if (noOfCallsTextView != null) editor.putString("no_of_calls", noOfCallsTextView.getText().toString());
            if (callGapTextView != null) editor.putString("call_gap", callGapTextView.getText().toString());
            if (disconnectSecEditText != null) editor.putString("disconnect_sec", disconnectSecEditText.getText().toString());
            editor.apply();
        }
        currentNoOfCalls.setText(getString(R.string.ar_current_no_of_call, 0));
        currentNoOfCalls.setVisibility(View.VISIBLE);
        timer = new Timer();
        timer.schedule(new AutomaticRedialerActivity.CallTask(), Constants.AUTOMATIC_REDIALER_INITIAL_DELAY, Integer.parseInt(callGapTextView.getText().toString()) * 1000L);
        Snackbar.make(findViewById(R.id.ar_button_call), "Calling to " + name + " " + noOfCallsTextView.getText().toString() + " times after every " + callGapTextView.getText().toString() + " sec", Snackbar.LENGTH_LONG).setAction("Action", null).show();
        }
    }

    private void stopAllDisconnectionInternal() {
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
            if (disconnectHandlers != null) {
                disconnectHandlers.clear();
            }
        } catch (Exception e) {
        }
        try {
            if (callStateReceiver != null) {
                unregisterReceiver(callStateReceiver);
                callStateReceiver = null;
            }
        } catch (Exception e) {
        }
        isCallActive = false;
        pickupDisconnectSec = 0;
        waitingForPickup = false;
    }

    private void stopAllDisconnection() {
        stopAllDisconnectionInternal();
    }

    class CallTask extends TimerTask {
        int currentReverseNoOfCall = Integer.parseInt(noOfCallsTextView.getText().toString());

        public void run() {
            if (currentReverseNoOfCall > 0) {
                currentNoOfCalls.setText(getString(R.string.ar_current_no_of_call, (Integer.parseInt(noOfCallsTextView.getText().toString()) - currentReverseNoOfCall + 1)));
                Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number));
                TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                System.out.println("is Telephony Enabled: " + (telephonyManager != null && telephonyManager.getSimState() == TelephonyManager.SIM_STATE_READY));
                startActivity(intent);
                // Auto disconnect after specified seconds
                try {
                    stopAllDisconnectionInternal();
                    int disconnectSec = 0;
                    if (disconnectSecEditText != null && disconnectSecEditText.getText() != null) {
                        String ds = disconnectSecEditText.getText().toString().trim();
                        if (!ds.isEmpty()) {
                            disconnectSec = Integer.parseInt(ds);
                        }
                    }
                    boolean afterPickup = false;
                    if (disconnectModeGroup != null) {
                        int checked = disconnectModeGroup.getCheckedRadioButtonId();
                        if (checked == R.id.ar_mode_after_pickup) {
                            afterPickup = true;
                        }
                    }
                    if (disconnectSec > 0) {
                        if (afterPickup) {
                            waitingForPickup = true;
                            pickupDisconnectSec = disconnectSec;
                            registerCallStateListener();
                        } else {
                            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                            final Runnable r = new Runnable() {
                                @Override
                                public void run() {
                                    endCallNow();
                                }
                            };
                            disconnectHandlers.add(h);
                            disconnectRunnables.add(r);
                            h.postDelayed(r, disconnectSec * 1000L);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                currentReverseNoOfCall--;
            } else {
                System.out.println("Time's up!");
                stopCallingButton.setClickable(false);
                stopCallingButton.setBackgroundColor(MainActivity.COLOR30_LIGHT);
                currentNoOfCalls.setVisibility(View.INVISIBLE);
                Snackbar.make(findViewById(R.id.ar_stop_call), "Calling stopped to " + name, Snackbar.LENGTH_LONG).show();
                stopAllDisconnectionInternal();
                if (timer != null) { timer.cancel(); timer = null; }
            }
        }
    }

    private void endCallNow() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                android.telecom.TelecomManager telecomManager = (android.telecom.TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (telecomManager != null && ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.endCall();
                    return;
                }
            }
        } catch (Exception e) {
        }
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            Class<?> c = Class.forName(tm.getClass().getName());
            Method m = c.getDeclaredMethod("getITelephony");
            m.setAccessible(true);
            Object telephonyService = m.invoke(tm);
            Class<?> telephonyInterfaceClass = Class.forName(telephonyService.getClass().getName());
            Method endCallMethod = telephonyInterfaceClass.getDeclaredMethod("endCall");
            endCallMethod.invoke(telephonyService);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void registerCallStateListener() {
        try {
            if (callStateReceiver != null) {
                unregisterReceiver(callStateReceiver);
            }
            callStateReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                    if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
                        if (waitingForPickup && pickupDisconnectSec > 0 && !isCallActive) {
                            isCallActive = true;
                            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                            final Runnable r = new Runnable() {
                                @Override
                                public void run() {
                                    endCallNow();
                                }
                            };
                            disconnectHandlers.add(h);
                            disconnectRunnables.add(r);
                            h.postDelayed(r, pickupDisconnectSec * 1000L);
                            waitingForPickup = false;
                        }
                    } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                        stopAllDisconnectionInternal();
                    }
                }
            };
            android.content.IntentFilter filter = new android.content.IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            registerReceiver(callStateReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}