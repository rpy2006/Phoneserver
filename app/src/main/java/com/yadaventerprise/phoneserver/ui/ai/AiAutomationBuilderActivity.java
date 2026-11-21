package com.yadaventerprise.phoneserver.ui.ai;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.ai.AiActionAuditStore;
import com.yadaventerprise.phoneserver.data.ai.AiAssistantService;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTask;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTaskStore;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class AiAutomationBuilderActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_automation_builder);
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        EditText name = findViewById(R.id.ai_auto_name);
        EditText action = findViewById(R.id.ai_auto_action);
        EditText projectId = findViewById(R.id.ai_auto_project);
        EditText webhookId = findViewById(R.id.ai_auto_webhook);
        EditText interval = findViewById(R.id.ai_auto_interval);
        TextView list = findViewById(R.id.ai_auto_list);
        Runnable refresh = () -> Executors.newSingleThreadExecutor().execute(() -> {
            StringBuilder sb = new StringBuilder();
            for (ScheduledTask t : ScheduledTaskStore.get(getApplicationContext()).all()) {
                sb.append("- ").append(t.name).append(" [").append(t.action).append("] enabled=").append(t.enabled)
                        .append(" next=").append(t.nextRunAt).append(" status=").append(t.lastStatus).append("\n");
            }
            if (sb.length() == 0) sb.append("No automation tasks found.");
            final String text = sb.toString();
            runOnUiThread(() -> list.setText(text));
        });
        refresh.run();
        ((Button)findViewById(R.id.ai_auto_create)).setOnClickListener(v -> {
            JSONObject body = new JSONObject();
            try {
                body.put("name", name.getText().toString().trim());
                body.put("action", action.getText().toString().trim().isEmpty() ? "backup" : action.getText().toString().trim());
                body.put("projectId", projectId.getText().toString().trim());
                body.put("webhookId", webhookId.getText().toString().trim());
                body.put("includeFiles", true);
                body.put("enabled", true);
                body.put("scheduleType", "recurring");
                body.put("intervalMinutes", Integer.parseInt(interval.getText().toString().trim().isEmpty() ? "1440" : interval.getText().toString().trim()));
                body.put("confirmed", true);
            } catch (Exception ignored) {}
            new AlertDialog.Builder(this).setTitle("Confirm Automation").setMessage("Create automation task?").setPositiveButton("Create", (d,w) -> {
                Executors.newSingleThreadExecutor().execute(() -> {
                    try {
                        ScheduledTask task = AiAssistantService.createAutomation(getApplicationContext(), body);
                        AiActionAuditStore.get(getApplicationContext()).append("admin-ui", "automation.create", task.id, true, true, task.name);
                        runOnUiThread(() -> { Toast.makeText(this, "Automation created", Toast.LENGTH_SHORT).show(); refresh.run(); });
                    } catch (Exception e) {
                        AiActionAuditStore.get(getApplicationContext()).append("admin-ui", "automation.create", "", true, false, e.getMessage());
                        runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                });
            }).setNegativeButton("Cancel", null).show();
        });
    }
}
