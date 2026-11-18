package com.yadaventerprise.phoneserver.ui.security;

import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookConfig;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookDispatcher;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookHistoryEntry;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookStore;
import java.net.URL;
import java.util.UUID;
import org.json.JSONObject;

public class WebhookEditActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "webhook_id";

    private EditText nameInput;
    private EditText urlInput;
    private EditText methodInput;
    private EditText headersInput;
    private EditText secretInput;
    private Switch enabledSwitch;
    private TextView statusText;
    private CheckBox checkAll;
    private CheckBox[] eventChecks;
    private String[] eventNames;
    private String id;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webhook_edit);
        nameInput = findViewById(R.id.input_name);
        urlInput = findViewById(R.id.input_url);
        methodInput = findViewById(R.id.input_method);
        headersInput = findViewById(R.id.input_headers);
        secretInput = findViewById(R.id.input_secret);
        enabledSwitch = findViewById(R.id.switch_enabled);
        statusText = findViewById(R.id.status_text);
        checkAll = findViewById(R.id.check_all);
        eventChecks = new CheckBox[]{
                findViewById(R.id.check_file_uploaded),
                findViewById(R.id.check_file_downloaded),
                findViewById(R.id.check_share_created),
                findViewById(R.id.check_server_started),
                findViewById(R.id.check_server_stopped),
                findViewById(R.id.check_server_failure),
                findViewById(R.id.check_server_recovered),
                findViewById(R.id.check_ad_state),
                findViewById(R.id.check_auth_failure),
                findViewById(R.id.check_suspicious),
                findViewById(R.id.check_backup),
                findViewById(R.id.check_storage_warning),
                findViewById(R.id.check_maintenance),
                findViewById(R.id.check_task_completed),
                findViewById(R.id.check_task_failed)
        };
        eventNames = WebhookDispatcher.ALL_EVENTS;

        id = getIntent().getStringExtra(EXTRA_ID);
        boolean editing = id != null;
        ((TextView) findViewById(R.id.screen_title)).setText(editing ? "Edit Webhook" : "New Webhook");
        Button delete = findViewById(R.id.delete_button);
        delete.setVisibility(editing ? android.view.View.VISIBLE : android.view.View.GONE);

        checkAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (loading) return;
            for (CheckBox check : eventChecks) check.setChecked(isChecked);
        });

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.save_button).setOnClickListener(v -> save());
        findViewById(R.id.test_button).setOnClickListener(v -> test());
        delete.setOnClickListener(v -> confirmDelete());

        if (editing) {
            load();
        } else {
            loading = true;
            methodInput.setText("POST");
            checkAll.setChecked(true);
            for (CheckBox check : eventChecks) check.setChecked(true);
            enabledSwitch.setChecked(true);
            loading = false;
        }
    }

    private void load() {
        WebhookConfig config = WebhookStore.get(this).get(id);
        if (config == null) {
            finish();
            return;
        }
        loading = true;
        nameInput.setText(config.name);
        urlInput.setText(config.url);
        methodInput.setText(config.method);
        headersInput.setText(config.headersJson);
        secretInput.setText(config.secret);
        enabledSwitch.setChecked(config.enabled);
        boolean all = config.events.contains("*") || config.events.isEmpty();
        checkAll.setChecked(all);
        for (int i = 0; i < eventChecks.length; i++) {
            eventChecks[i].setChecked(all || config.events.contains(eventNames[i]));
        }
        loading = false;
        if (!config.lastStatus.isEmpty()) {
            statusText.setText("Last delivery: " + config.lastStatus
                    + (config.lastError.isEmpty() ? "" : " · " + config.lastError));
        }
    }

    private WebhookConfig collect() {
        WebhookConfig config = id == null ? new WebhookConfig() : WebhookStore.get(this).get(id);
        if (config == null) config = new WebhookConfig();
        if (config.id == null || config.id.isEmpty()) config.id = UUID.randomUUID().toString();
        if (config.createdAt == 0) config.createdAt = System.currentTimeMillis();
        config.name = nameInput.getText().toString().trim();
        config.url = urlInput.getText().toString().trim();
        config.method = methodInput.getText().toString().trim().toUpperCase(java.util.Locale.US);
        if (config.method.isEmpty()) config.method = "POST";
        config.headersJson = headersInput.getText().toString().trim();
        config.secret = secretInput.getText().toString().trim();
        config.enabled = enabledSwitch.isChecked();
        config.events.clear();
        if (checkAll.isChecked()) {
            config.events.add("*");
        } else {
            for (int i = 0; i < eventChecks.length; i++) {
                if (eventChecks[i].isChecked()) config.events.add(eventNames[i]);
            }
        }
        return config;
    }

    private boolean validate(WebhookConfig config) {
        if (config.name.isEmpty()) {
            nameInput.setError("Name is required");
            return false;
        }
        try {
            URL url = new URL(config.url);
            String protocol = url.getProtocol();
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                urlInput.setError("Use an http:// or https:// URL");
                return false;
            }
        } catch (Exception e) {
            urlInput.setError("Invalid URL");
            return false;
        }
        if (!config.method.equals("GET") && !config.method.equals("POST")
                && !config.method.equals("PUT") && !config.method.equals("PATCH")
                && !config.method.equals("DELETE")) {
            methodInput.setError("Use GET, POST, PUT, PATCH or DELETE");
            return false;
        }
        if (!config.headersJson.isEmpty()) {
            try {
                new org.json.JSONObject(config.headersJson);
            } catch (Exception e) {
                headersInput.setError("Enter a valid JSON object");
                return false;
            }
        }
        if (config.events.isEmpty()) {
            Toast.makeText(this, "Choose at least one event", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void save() {
        WebhookConfig config = collect();
        if (!validate(config)) return;
        WebhookStore.get(this).save(config);
        id = config.id;
        Toast.makeText(this, "Webhook saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void test() {
        WebhookConfig config = collect();
        if (!validate(config)) return;
        statusText.setText("Sending test event…");
        findViewById(R.id.test_button).setEnabled(false);
        new Thread(() -> {
            JSONObject data = new JSONObject();
            try {
                data.put("message", "PhoneServer webhook test");
                data.put("webhook", config.name);
            } catch (Exception ignored) {
            }
            WebhookHistoryEntry result = WebhookDispatcher.deliverNow(this, config, "test", data);
            runOnUiThread(() -> {
                findViewById(R.id.test_button).setEnabled(true);
                if (result == null) {
                    statusText.setText("Test failed: no result");
                } else if (result.success) {
                    statusText.setText("Test delivered successfully");
                } else {
                    statusText.setText("Test failed: " + result.error);
                }
            });
        }, "PhoneServer-WebhookTest").start();
    }

    private void confirmDelete() {
        if (id == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete webhook?")
                .setMessage("Delivery history will be kept.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    WebhookStore.get(this).delete(id);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
