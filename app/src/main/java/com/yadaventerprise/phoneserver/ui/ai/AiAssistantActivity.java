package com.yadaventerprise.phoneserver.ui.ai;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.ai.AiAssistantService;
import com.yadaventerprise.phoneserver.data.ai.AiSettings;
import com.yadaventerprise.phoneserver.data.ai.AiSettingsStore;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import com.yadaventerprise.phoneserver.data.ai.AiActionCatalog;

public class AiAssistantActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView answer;
    private EditText question;
    private ProgressBar progress;
    private Button executeButton;
    private JSONObject proposedAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_assistant);
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        status = findViewById(R.id.ai_status);
        answer = findViewById(R.id.ai_answer);
        question = findViewById(R.id.ai_question);
        progress = findViewById(R.id.ai_progress);
        executeButton = findViewById(R.id.ai_btn_execute);
        findViewById(R.id.ai_btn_settings).setOnClickListener(v -> startActivity(new Intent(this, AiSettingsActivity.class)));
        findViewById(R.id.ai_btn_overview).setOnClickListener(v -> startActivity(new Intent(this, AiOverviewActivity.class)));
        findViewById(R.id.ai_btn_insights).setOnClickListener(v -> startActivity(new Intent(this, AiInsightsActivity.class)));
        findViewById(R.id.ai_btn_logs).setOnClickListener(v -> startActivity(new Intent(this, AiLogAnalysisActivity.class)));
        findViewById(R.id.ai_btn_security).setOnClickListener(v -> startActivity(new Intent(this, AiSecurityInsightsActivity.class)));
        findViewById(R.id.ai_btn_analytics).setOnClickListener(v -> startActivity(new Intent(this, AiAnalyticsAssistantActivity.class)));
        findViewById(R.id.ai_btn_automation).setOnClickListener(v -> startActivity(new Intent(this, AiAutomationBuilderActivity.class)));
        findViewById(R.id.ai_btn_history).setOnClickListener(v -> startActivity(new Intent(this, AiHistoryActivity.class)));
        findViewById(R.id.ai_btn_audit).setOnClickListener(v -> startActivity(new Intent(this, AiAuditHistoryActivity.class)));
        findViewById(R.id.ai_btn_ask).setOnClickListener(v -> ask());
        executeButton.setOnClickListener(v -> confirmExecute());
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void refreshStatus() {
        AiSettings s = AiSettingsStore.get(this).get();
        String provider = s.localInference || AiSettings.PROVIDER_LOCAL.equals(s.provider) ? "local" : s.provider;
        status.setText("AI: " + (s.enabled ? "enabled" : "disabled") + " | provider: " + provider + " | external consent: " + s.consentExternalSharing + " | data sharing is explicit and redacted");
    }

    private void runPrompt(String q) {
        question.setText(q);
        ask(false);
    }

    private void ask() {
        ask(false);
    }

    private void ask(boolean consentForRequest) {
        final String q = question.getText().toString().trim();
        if (q.isEmpty()) return;
        progress.setVisibility(View.VISIBLE);
        answer.setText("");
        executeButton.setVisibility(View.GONE);
        proposedAction = null;
        executor.execute(() -> {
            try {
                final AiAssistantService.Result result = AiAssistantService.ask(getApplicationContext(), q, consentForRequest);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    if (result.requiresConsent && !consentForRequest) {
                        new AlertDialog.Builder(AiAssistantActivity.this)
                                .setTitle("External AI Consent")
                                .setMessage("This request may send redacted, explicitly allowed diagnostic data to the configured external AI provider. Continue?")
                                .setPositiveButton("Send", (d, w) -> ask(true))
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                    String note = result.externalProvider ? "\n\n[External AI provider used]" : "\n\n[Answered from local/actual records or local AI]";
                    if (result.requiresConsent) note = "\n\n[External provider configured but consent required]";
                    answer.setText(result.answer + note);
                    proposedAction = result.proposedAction;
                    executeButton.setVisibility(proposedAction != null ? View.VISIBLE : View.GONE);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    answer.setText("AI error: " + e.getMessage());
                });
            }
        });
    }

    private void confirmExecute() {
        if (proposedAction == null) return;
        String label = proposedAction.optString("label", proposedAction.optString("type", "action"));
        boolean confirm = proposedAction.optBoolean("confirm", false);
        if (confirm) {
            new AlertDialog.Builder(this)
                    .setTitle("Confirm Action")
                    .setMessage("Execute: " + label + "?")
                    .setPositiveButton("Execute", (d, w) -> execute(true))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            execute(true);
        }
    }

    private void execute(boolean confirmed) {
        if (proposedAction == null) return;
        progress.setVisibility(View.VISIBLE);
        final JSONObject action = proposedAction;
        executor.execute(() -> {
            try {
                final String result = AiAssistantService.executeAction(getApplicationContext(), action, confirmed, "admin-ui");
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    answer.setText(answer.getText().toString() + "\n\nAction result:\n" + result);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    answer.setText(answer.getText().toString() + "\n\nAction failed:\n" + e.getMessage());
                });
            }
        });
    }
}
