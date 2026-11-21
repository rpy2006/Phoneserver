package com.yadaventerprise.phoneserver.ui.ai;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.widget.SwitchCompat;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.ai.AiConversationStore;
import com.yadaventerprise.phoneserver.data.ai.AiSettings;
import com.yadaventerprise.phoneserver.data.ai.AiSettingsStore;
import androidx.appcompat.app.AppCompatActivity;

public class AiSettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_settings);
        findViewById(R.id.back_button).setOnClickListener(v -> finish());

        SwitchCompat enabled = findViewById(R.id.ai_enabled);
        SwitchCompat local = findViewById(R.id.ai_local);
        EditText provider = findViewById(R.id.ai_provider);
        EditText endpoint = findViewById(R.id.ai_endpoint);
        EditText model = findViewById(R.id.ai_model);
        EditText key = findViewById(R.id.ai_key);
        EditText timeout = findViewById(R.id.ai_timeout);
        SwitchCompat shareLogs = findViewById(R.id.ai_share_logs);
        SwitchCompat shareSecurity = findViewById(R.id.ai_share_security);
        SwitchCompat shareDiagnostics = findViewById(R.id.ai_share_diagnostics);
        SwitchCompat history = findViewById(R.id.ai_history);
        SwitchCompat consent = findViewById(R.id.ai_consent);

        AiSettingsStore store = AiSettingsStore.get(this);
        AiSettings s = store.get();
        enabled.setChecked(s.enabled);
        local.setChecked(s.localInference);
        provider.setText(s.provider);
        endpoint.setText(s.endpoint);
        model.setText(s.model);
        timeout.setText(String.valueOf(s.timeoutSeconds));
        shareLogs.setChecked(s.shareLogs);
        shareSecurity.setChecked(s.shareSecurityEvents);
        shareDiagnostics.setChecked(s.shareDiagnostics);
        history.setChecked(s.saveHistory);
        consent.setChecked(s.consentExternalSharing);

        findViewById(R.id.ai_save).setOnClickListener(v -> {
            AiSettings n = store.get();
            n.enabled = enabled.isChecked();
            n.localInference = local.isChecked();
            n.provider = provider.getText().toString().trim();
            n.endpoint = endpoint.getText().toString().trim();
            n.model = model.getText().toString().trim();
            try { n.timeoutSeconds = Integer.parseInt(timeout.getText().toString().trim()); } catch (Exception ignored) {}
            n.shareLogs = shareLogs.isChecked();
            n.shareSecurityEvents = shareSecurity.isChecked();
            n.shareDiagnostics = shareDiagnostics.isChecked();
            n.saveHistory = history.isChecked();
            n.consentExternalSharing = consent.isChecked();
            String k = key.getText().toString().trim();
            if (!k.isEmpty()) n.apiKey = k;
            store.save(n);
            key.setText("");
            Toast.makeText(this, "AI settings saved", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.ai_clear_key).setOnClickListener(v -> {
            AiSettings n = store.get();
            n.apiKey = "";
            store.save(n);
            Toast.makeText(this, "AI API key cleared", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.ai_clear_history).setOnClickListener(v -> {
            AiConversationStore.get(getApplicationContext()).clear();
            Toast.makeText(this, "AI conversation history cleared", Toast.LENGTH_SHORT).show();
        });
    }
}
