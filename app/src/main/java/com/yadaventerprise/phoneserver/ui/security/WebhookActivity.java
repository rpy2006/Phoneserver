package com.yadaventerprise.phoneserver.ui.security;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookConfig;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookHistoryEntry;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookHistoryStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookStore;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public class WebhookActivity extends AppCompatActivity {

    private LinearLayout hookList;
    private LinearLayout historyList;
    private TextView emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webhooks);
        hookList = findViewById(R.id.hook_list);
        historyList = findViewById(R.id.history_list);
        emptyText = findViewById(R.id.empty_text);
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.add_button).setOnClickListener(v -> openEditor(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        hookList.removeAllViews();
        List<WebhookConfig> hooks = WebhookStore.get(this).all();
        emptyText.setVisibility(hooks.isEmpty() ? View.VISIBLE : View.GONE);
        for (WebhookConfig hook : hooks) {
            TextView row = new TextView(this);
            row.setBackgroundResource(R.drawable.bg_home_card);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setTextColor(getColor(R.color.home_text_primary));
            row.setTextSize(14);
            row.setGravity(Gravity.CENTER_VERTICAL);
            String status = hook.enabled ? "Enabled" : "Disabled";
            if (!hook.lastStatus.isEmpty()) status += " · last " + hook.lastStatus;
            row.setText(hook.name + "\n" + status + "\n" + hook.url);
            row.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(10);
            row.setLayoutParams(params);
            row.setOnClickListener(v -> openEditor(hook.id));
            hookList.addView(row);
        }

        historyList.removeAllViews();
        List<WebhookHistoryEntry> history = WebhookHistoryStore.get(this).list();
        if (history.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No deliveries yet");
            empty.setTextColor(getColor(R.color.home_text_secondary));
            empty.setTextSize(12);
            empty.setPadding(0, dp(4), 0, dp(4));
            historyList.addView(empty);
            return;
        }
        int limit = Math.min(history.size(), 20);
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (int i = 0; i < limit; i++) {
            WebhookHistoryEntry entry = history.get(i);
            TextView row = new TextView(this);
            row.setText((entry.success ? "OK  " : "FAIL  ")
                    + entry.event + " · " + entry.webhookName + "\n"
                    + format.format(new Date(entry.createdAt))
                    + (entry.error.isEmpty() ? "" : " · " + entry.error));
            row.setTextColor(getColor(entry.success ? R.color.home_text_secondary : R.color.offline_red));
            row.setTextSize(11);
            row.setTypeface(Typeface.MONOSPACE);
            row.setPadding(0, dp(4), 0, dp(8));
            historyList.addView(row);
        }
    }

    private void openEditor(String id) {
        Intent intent = new Intent(this, WebhookEditActivity.class);
        if (id != null) intent.putExtra(WebhookEditActivity.EXTRA_ID, id);
        startActivity(intent);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
