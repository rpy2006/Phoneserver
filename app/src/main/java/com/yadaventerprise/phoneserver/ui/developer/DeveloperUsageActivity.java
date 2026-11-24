package com.yadaventerprise.phoneserver.ui.developer;

import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.developer.ApiUsageEntry;
import com.yadaventerprise.phoneserver.data.developer.ApiUsageStore;
import com.yadaventerprise.phoneserver.util.TimeFormat;
import org.json.JSONArray;
import org.json.JSONObject;

public class DeveloperUsageActivity extends AppCompatActivity {
    private static final String STATE_HOURS = "developer_usage_hours";
    private int hours = 24;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_simple);
        ((TextView)findViewById(R.id.title)).setText("API Usage");
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        if (savedInstanceState != null) hours = savedInstanceState.getInt(STATE_HOURS, 24);
        LinearLayout actions = findViewById(R.id.action_bar);
        actions.addView(actionButton("24 hours", 24));
        actions.addView(actionButton("7 days", 168));
        actions.addView(actionButton("30 days", 720));
    }

    @Override protected void onResume() {
        super.onResume();
        load();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_HOURS, hours);
    }

    private Button actionButton(String label, int value) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.bg_button_outline);
        button.setTextColor(getColor(R.color.home_text_primary));
        button.setOnClickListener(v -> {
            hours = value;
            load();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private void load() {
        final int selected = hours;
        new Thread(() -> {
            JSONObject s = ApiUsageStore.get(this).summary(
                    System.currentTimeMillis() - selected * 60L * 60L * 1000L);
            StringBuilder sb = new StringBuilder();
            sb.append("Window: last ").append(labelFor(selected)).append("\n");
            sb.append("Total: ").append(s.optInt("totalRequests",0));
            sb.append("\nSuccess: ").append(s.optInt("successRequests",0));
            sb.append("\nFailed: ").append(s.optInt("failedRequests",0));
            sb.append("\nRate limited: ").append(s.optInt("rateLimitViolations",0));
            sb.append("\nAverage response: ").append(s.optLong("averageResponseMs",0)).append(" ms\n");

            appendRows(sb, "\nUsage by key", "usageByKey", "key", s);
            appendRows(sb, "\nTop endpoints", "topEndpoints", "endpoint", s);
            appendRows(sb, "\nStatus distribution", "statusDistribution", "status", s);

            sb.append("\nRecent requests\n");
            for (ApiUsageEntry e : ApiUsageStore.get(this).list(30)) {
                sb.append(TimeFormat.formatTime(e.time)).append(" ")
                        .append(e.method).append(" ").append(e.path)
                        .append(" -> ").append(e.status)
                        .append(" (").append(e.durationMs).append("ms")
                        .append(", in ").append(e.bytesIn).append(", out ").append(e.bytesOut)
                        .append(e.rateLimited ? ", rate limited" : "")
                        .append(")\n");
                if (e.keyPrefix != null && !e.keyPrefix.isEmpty()) {
                    sb.append("  key ").append(e.keyPrefix);
                    if (e.tokenName != null && !e.tokenName.isEmpty()) sb.append(" · ").append(e.tokenName);
                    if (e.ip != null && !e.ip.isEmpty()) sb.append(" · ").append(e.ip);
                    sb.append('\n');
                }
            }
            runOnUiThread(() -> {
                if (isFinishing() || selected != hours) return;
                ((TextView)findViewById(R.id.body)).setText(sb.toString());
            });
        }).start();
    }

    private static void appendRows(StringBuilder sb, String heading, String arrayName,
                                   String labelField, JSONObject summary) {
        sb.append(heading).append('\n');
        JSONArray rows = summary.optJSONArray(arrayName);
        if (rows == null || rows.length() == 0) {
            sb.append("- none\n");
            return;
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            sb.append("- ").append(row.optString(labelField)).append(": ").append(row.optInt("count")).append('\n');
        }
    }

    private static String labelFor(int selected) {
        if (selected >= 720) return "30 days";
        if (selected >= 168) return "7 days";
        return "24 hours";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
