package com.yadaventerprise.phoneserver.ui.developer;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookConfig;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookDispatcher;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookHistoryEntry;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookHistoryStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookStore;
import com.yadaventerprise.phoneserver.ui.security.WebhookEditActivity;
import com.yadaventerprise.phoneserver.util.TimeFormat;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public class DeveloperWebhookToolsActivity extends AppCompatActivity {
    private final List<WebhookConfig> hooks = new ArrayList<>();
    private Spinner hookSpinner;
    private String selectedId;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_simple);
        ((TextView)findViewById(R.id.title)).setText("Webhook Tools");
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        if (savedInstanceState != null) selectedId = savedInstanceState.getString("selected_hook");
        hookSpinner = new Spinner(this);
        LinearLayout actions = findViewById(R.id.action_bar);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        hookSpinner.setLayoutParams(spinnerParams);
        actions.addView(hookSpinner);
        actions.addView(actionButton("Add", this::openAdd));
        actions.addView(actionButton("Edit", this::openEdit));
        actions.addView(actionButton("Test", this::testSelected));
        actions.addView(actionButton("Verify", this::verifySelected));
        actions.addView(actionButton("Delete", this::deleteSelected));
        actions.addView(actionButton("Refresh", this::refresh));
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("selected_hook", selectedId);
    }

    private Button actionButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.bg_button_outline);
        button.setTextColor(getColor(R.color.home_text_primary));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(4);
        button.setLayoutParams(params);
        return button;
    }

    private void refresh() {
        new Thread(() -> {
            final List<WebhookConfig> loaded = WebhookStore.get(this).all();
            final List<WebhookHistoryEntry> history = WebhookHistoryStore.get(this).list();
            final String text = render(loaded, history);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                hooks.clear();
                hooks.addAll(loaded);
                List<String> labels = new ArrayList<>();
                int selection = 0;
                for (int i = 0; i < hooks.size(); i++) {
                    WebhookConfig hook = hooks.get(i);
                    labels.add(hook.name + (hook.enabled ? "" : " (disabled)"));
                    if (hook.id.equals(selectedId)) selection = i;
                }
                if (labels.isEmpty()) labels.add("No webhooks configured");
                hookSpinner.setAdapter(new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_dropdown_item, labels));
                hookSpinner.setSelection(selection);
                ((TextView)findViewById(R.id.body)).setText(text);
            });
        }).start();
    }

    private String render(List<WebhookConfig> configs, List<WebhookHistoryEntry> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("Signature scheme\nX-PhoneServer-Timestamp\nX-PhoneServer-Delivery-Id\n");
        sb.append("X-PhoneServer-Signature: sha256=<HMAC_SHA256(timestamp.deliveryId.body, secret)>\n");
        sb.append("Secrets are stored once and are never shown in this screen.\n");
        sb.append("\nSupported events\n");
        for (String event : WebhookDispatcher.ALL_EVENTS) sb.append("- ").append(event).append('\n');
        sb.append("\nConfigured webhooks\n");
        if (configs.isEmpty()) sb.append("- none\n");
        for (WebhookConfig hook : configs) {
            sb.append("- ").append(hook.name).append(" | ").append(hook.enabled ? "enabled" : "disabled")
                    .append(" | ").append(hook.method).append(" | ").append(hook.url).append('\n');
            sb.append("  secret ").append(hook.secret == null || hook.secret.isEmpty() ? "not set" : "configured")
                    .append(" · events ").append(hook.events.isEmpty() ? "*" : hook.events.toString()).append('\n');
            sb.append("  timeout ").append(hook.timeoutSeconds).append("s · attempts ")
                    .append(hook.maxAttempts).append(" · last ").append(hook.lastStatus.isEmpty() ? "none" : hook.lastStatus)
                    .append(" · failures ").append(hook.failureCount).append('\n');
        }
        sb.append("\nRecent deliveries\n");
        if (history.isEmpty()) sb.append("- none\n");
        int limit = Math.min(history.size(), 60);
        for (int i = 0; i < limit; i++) {
            WebhookHistoryEntry entry = history.get(i);
            sb.append(TimeFormat.formatTime(entry.createdAt)).append(" ")
                    .append(entry.success ? "OK" : "FAIL").append(" · ")
                    .append(entry.event).append(" · ").append(entry.webhookName)
                    .append(" · code ").append(entry.responseCode)
                    .append(" · attempts ").append(entry.attempts)
                    .append(" · ").append(entry.durationMs).append("ms\n");
            if (!entry.error.isEmpty()) sb.append("  ").append(entry.error).append('\n');
        }
        return sb.toString();
    }

    private WebhookConfig selectedHook() {
        int position = hookSpinner.getSelectedItemPosition();
        if (position < 0 || position >= hooks.size()) return null;
        selectedId = hooks.get(position).id;
        return hooks.get(position);
    }

    private void openAdd() {
        startActivity(new Intent(this, WebhookEditActivity.class));
    }

    private void openEdit() {
        WebhookConfig hook = selectedHook();
        if (hook == null) {
            Toast.makeText(this, "Select a webhook first", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, WebhookEditActivity.class);
        intent.putExtra(WebhookEditActivity.EXTRA_ID, hook.id);
        startActivity(intent);
    }

    private void testSelected() {
        WebhookConfig hook = selectedHook();
        if (hook == null) {
            Toast.makeText(this, "Select a webhook first", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Sending test delivery", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            JSONObject data = new JSONObject();
            try {
                data.put("source", "developer-ui");
            } catch (Exception ignored) {
            }
            WebhookHistoryEntry result = WebhookDispatcher.deliverNow(this, hook, "developer.test", data);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                Toast.makeText(this, result != null && result.success ? "Test delivered" : "Test failed",
                        Toast.LENGTH_LONG).show();
                refresh();
            });
        }).start();
    }

    private void verifySelected() {
        WebhookConfig hook = selectedHook();
        if (hook == null) {
            Toast.makeText(this, "Select a webhook first", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, pad / 2, pad, 0);
        EditText delivery = new EditText(this);
        delivery.setHint("Delivery id");
        EditText timestamp = new EditText(this);
        timestamp.setHint("Timestamp (epoch millis)");
        timestamp.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        EditText body = new EditText(this);
        body.setHint("Raw body");
        EditText signature = new EditText(this);
        signature.setHint("X-PhoneServer-Signature value");
        form.addView(delivery);
        form.addView(timestamp);
        form.addView(body);
        form.addView(signature);
        new AlertDialog.Builder(this)
                .setTitle("Verify incoming webhook")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Verify", (dialog, which) -> {
                    boolean ok = WebhookDispatcher.verifyIncoming(this, hook.secret,
                            timestamp.getText().toString().trim(), delivery.getText().toString().trim(),
                            body.getText().toString(), signature.getText().toString().trim(),
                            System.currentTimeMillis());
                    Toast.makeText(this, ok ? "Signature verified" : "Invalid, stale, or replayed delivery",
                            Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void deleteSelected() {
        WebhookConfig hook = selectedHook();
        if (hook == null) {
            Toast.makeText(this, "Select a webhook first", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete webhook '" + hook.name + "'?")
                .setMessage("Existing delivery history is kept for audit.")
                .setPositiveButton("Delete", (d, w) -> {
                    WebhookStore.get(this).delete(hook.id);
                    selectedId = null;
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
