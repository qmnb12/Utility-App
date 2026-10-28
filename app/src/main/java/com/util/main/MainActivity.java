package com.util.main;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.util.R;
import com.util.automaticredialer.AutomaticRedialerActivity;
import com.util.features.FeatureHubActivity;
import com.util.messagesender.MessageSenderActivity;

public class MainActivity extends AppCompatActivity {
    public static int COLOR10_DARK = 0xFF2E7D32;
    public static int COLOR10_LIGHT = 0xFFA5D6A7;
    public static int COLOR30_DARK = 0xFFC62828;
    public static int COLOR30_LIGHT = 0xFFEF9A9A;
    public static int COLOR60_DARK = 0xFF1565C0;
    public static int COLOR60_LIGHT = 0xFF90CAF9;
    Context baseContext = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        int savedTheme = getSharedPreferences("utility_feature_data", MODE_PRIVATE)
                .getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(savedTheme);
        super.onCreate(savedInstanceState);
        mainInit();

        TextView headerTxt = findViewById(R.id.app_header);
        headerTxt.setText(R.string.app_name);

        Button automaticRedialerBtn = findViewById(R.id.automatic_redialer);
        automaticRedialerBtn.setOnClickListener(v -> startActivity(new Intent(baseContext, AutomaticRedialerActivity.class)));

        Button messageSenderBtn = findViewById(R.id.message_sender);
        messageSenderBtn.setOnClickListener(v -> startActivity(new Intent(baseContext, MessageSenderActivity.class)));

        Button featureHubBtn = findViewById(R.id.feature_hub);
        featureHubBtn.setOnClickListener(v -> startActivity(new Intent(baseContext, FeatureHubActivity.class)));
    }

    private void mainInit() {
        setMainColors();
        com.util.databinding.ActivityMainBinding mainBinding = com.util.databinding.ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(mainBinding.getRoot());
        baseContext = getBaseContext();
    }

    private void setMainColors() {
        try {
            Resources r = getResources();
            COLOR10_DARK = r.getColor(R.color.color10_dark, null);
            COLOR10_LIGHT = r.getColor(R.color.color10_light, null);
            COLOR30_DARK = r.getColor(R.color.color30_dark, null);
            COLOR30_LIGHT = r.getColor(R.color.color30_light, null);
            COLOR60_DARK = r.getColor(R.color.color60_dark, null);
            COLOR60_LIGHT = r.getColor(R.color.color60_light, null);
        } catch (Exception ignored) {
            // keep the hardcoded fallbacks so buttons never become invisible
        }
    }
}