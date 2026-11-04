package com.util.automaticredialer;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.util.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Separate in-app history page; selecting an item returns its number to Automatic Redialer. */
public class CallHistoryActivity extends AppCompatActivity {
    public static final String EXTRA_NUMBER = "selected_history_number";
    public static final String EXTRA_LABEL = "selected_history_label";
    private static final String PREF_CALL_HISTORY = "automatic_call_history";
    private LinearLayout entriesContainer;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("ar_prefs", MODE_PRIVATE);
        buildScreen();
        renderHistory();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        root.setBackgroundColor(Color.rgb(247, 249, 253));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);

        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setPadding(0, 0, 0, 0);
        back.setMinWidth(dp(44));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = new TextView(this);
        title.setText(R.string.ar_history_title);
        title.setTextColor(Color.rgb(20, 32, 50));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(8);
        header.addView(title, titleParams);

        Button clear = new Button(this);
        clear.setText(R.string.ar_clear_history);
        clear.setAllCaps(false);
        clear.setOnClickListener(v -> confirmClear());
        header.addView(clear, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)));
        root.addView(header);

        TextView hint = new TextView(this);
        hint.setText(R.string.ar_history_pick_hint);
        hint.setTextColor(Color.rgb(95, 108, 126));
        hint.setTextSize(13);
        hint.setPadding(dp(4), dp(4), dp(4), dp(12));
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        entriesContainer = new LinearLayout(this);
        entriesContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(entriesContainer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void renderHistory() {
        entriesContainer.removeAllViews();
        JSONArray history = readHistory();
        if (history.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.ar_history_empty);
            empty.setTextColor(Color.rgb(100, 112, 128));
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(50), dp(20), dp(50));
            entriesContainer.addView(empty);
            return;
        }

        SimpleDateFormat dateFormat =
                new SimpleDateFormat("dd MMM yyyy • hh:mm:ss a", Locale.getDefault());
        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) continue;
            String number = item.optString("number", "");
            // USSD sessions are not phone-call history entries and cannot be
            // selected as a dialable phone number here.
            if (number.trim().isEmpty() || number.startsWith("*") || number.startsWith("#")) continue;
            String label = item.optString("label", number);
            String status = item.optString("status", "Unknown");
            long startedAt = item.optLong("startedAt", 0L);
            long duration = item.optLong("durationSeconds", -1L);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            GradientDrawable cardBackground = new GradientDrawable();
            cardBackground.setColor(Color.WHITE);
            cardBackground.setCornerRadius(dp(14));
            cardBackground.setStroke(dp(1), Color.rgb(224, 230, 239));
            card.setBackground(cardBackground);

            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setOrientation(LinearLayout.HORIZONTAL);

            TextView name = new TextView(this);
            name.setText(label);
            name.setTextColor(Color.rgb(24, 38, 58));
            name.setTextSize(15);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            name.setMaxLines(2);
            top.addView(name, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button select = new Button(this);
            select.setText(R.string.ar_history_use_number);
            select.setAllCaps(false);
            select.setTextSize(11);
            select.setOnClickListener(v -> returnSelected(number, label));
            top.addView(select, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));
            card.addView(top);

            TextView phone = new TextView(this);
            phone.setText(number);
            phone.setTextColor(Color.rgb(2, 45, 156));
            phone.setTextSize(14);
            phone.setPadding(0, dp(4), 0, dp(4));
            card.addView(phone);

            String date = startedAt > 0L ? dateFormat.format(new Date(startedAt)) : "";
            String detail = date + (date.isEmpty() ? "" : "   •   ") + status;
            if (duration >= 0L) detail += "   •   " + duration + " sec";
            TextView details = new TextView(this);
            details.setText(detail);
            details.setTextColor(Color.rgb(104, 116, 132));
            details.setTextSize(11);
            card.addView(details);

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(10);
            entriesContainer.addView(card, cardParams);
        }
        if (entriesContainer.getChildCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.ar_history_empty);
            empty.setTextColor(Color.rgb(100, 112, 128));
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(50), dp(20), dp(50));
            entriesContainer.addView(empty);
        }
    }

    private void returnSelected(String number, String label) {
        Intent result = new Intent();
        result.putExtra(EXTRA_NUMBER, number);
        result.putExtra(EXTRA_LABEL, label);
        setResult(RESULT_OK, result);
        finish();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.ar_clear_history)
                .setMessage(R.string.ar_history_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ar_clear_history, (dialog, which) -> {
                    prefs.edit().remove(PREF_CALL_HISTORY).apply();
                    renderHistory();
                })
                .show();
    }

    private JSONArray readHistory() {
        try {
            return new JSONArray(prefs.getString(PREF_CALL_HISTORY, "[]"));
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
