package com.yadaventerprise.phoneserver.ui.security;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.database.Records;
import com.yadaventerprise.phoneserver.data.storage.ProjectStore;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTask;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTaskRunner;
import com.yadaventerprise.phoneserver.data.tasks.ScheduledTaskStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookConfig;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookStore;
import com.yadaventerprise.phoneserver.service.ScheduledTaskScheduler;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class TaskEditActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "task_id";

    private EditText nameInput;
    private EditText intervalInput;
    private EditText runAtInput;
    private EditText maxAttemptsInput;
    private EditText retryDelayInput;
    private Spinner scheduleSpinner;
    private Spinner actionSpinner;
    private Spinner projectSpinner;
    private Spinner webhookSpinner;
    private View projectSection;
    private View webhookSection;
    private View runAtSection;
    private Switch includeFilesSwitch;
    private Switch enabledSwitch;
    private TextView statusText;
    private final List<String> projectIds = new ArrayList<>();
    private final List<String> webhookIds = new ArrayList<>();
    private String id;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task_edit);
        nameInput = findViewById(R.id.input_name);
        intervalInput = findViewById(R.id.input_interval);
        runAtInput = findViewById(R.id.input_run_at);
        maxAttemptsInput = findViewById(R.id.input_max_attempts);
        retryDelayInput = findViewById(R.id.input_retry_delay);
        scheduleSpinner = findViewById(R.id.schedule_spinner);
        actionSpinner = findViewById(R.id.action_spinner);
        projectSpinner = findViewById(R.id.project_spinner);
        webhookSpinner = findViewById(R.id.webhook_spinner);
        projectSection = findViewById(R.id.project_section);
        webhookSection = findViewById(R.id.webhook_section);
        runAtSection = findViewById(R.id.run_at_section);
        includeFilesSwitch = findViewById(R.id.switch_include_files);
        enabledSwitch = findViewById(R.id.switch_enabled);
        statusText = findViewById(R.id.status_text);

        ArrayAdapter<String> actions = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Project backup", "Webhook delivery"});
        actions.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        actionSpinner.setAdapter(actions);
        actionSpinner.setOnItemSelectedListener(new SimpleItemSelected(this::updateSections));

        ArrayAdapter<String> schedules = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Repeating", "One time"});
        schedules.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        scheduleSpinner.setAdapter(schedules);
        scheduleSpinner.setOnItemSelectedListener(new SimpleItemSelected(this::updateSections));

        loadTargets();
        id = getIntent().getStringExtra(EXTRA_ID);
        boolean editing = id != null;
        ((TextView) findViewById(R.id.screen_title)).setText(editing ? "Edit Task" : "New Task");
        Button delete = findViewById(R.id.delete_button);
        delete.setVisibility(editing ? View.VISIBLE : View.GONE);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.save_button).setOnClickListener(v -> {
            if (persist()) finish();
        });
        findViewById(R.id.run_button).setOnClickListener(v -> runNow());
        delete.setOnClickListener(v -> confirmDelete());

        if (editing) {
            load();
        } else {
            intervalInput.setText("1440");
            maxAttemptsInput.setText("1");
            retryDelayInput.setText("5");
            includeFilesSwitch.setChecked(true);
            enabledSwitch.setChecked(true);
            actionSpinner.setSelection(0);
        }
        updateSections();
    }

    private void loadTargets() {
        List<String> projectNames = new ArrayList<>();
        try {
            for (Records.ProjectRecord project : ProjectStore.list(this)) {
                projectIds.add(project.id);
                projectNames.add(project.name);
            }
        } catch (Exception ignored) {
        }
        if (projectNames.isEmpty()) projectNames.add("No projects");
        ArrayAdapter<String> projects = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, projectNames);
        projects.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        projectSpinner.setAdapter(projects);

        List<String> hookNames = new ArrayList<>();
        for (WebhookConfig hook : WebhookStore.get(this).all()) {
            webhookIds.add(hook.id);
            hookNames.add(hook.name);
        }
        if (hookNames.isEmpty()) hookNames.add("No webhooks");
        ArrayAdapter<String> hooks = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, hookNames);
        hooks.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        webhookSpinner.setAdapter(hooks);
    }

    private void load() {
        ScheduledTask task = ScheduledTaskStore.get(this).get(id);
        if (task == null) {
            finish();
            return;
        }
        nameInput.setText(task.name);
        intervalInput.setText(String.valueOf(task.intervalMinutes));
        scheduleSpinner.setSelection(task.isOneTime() ? 1 : 0);
        if (task.runAt > 0) {
            runAtInput.setText(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(task.runAt)));
        }
        maxAttemptsInput.setText(String.valueOf(task.maxAttempts));
        retryDelayInput.setText(String.valueOf(task.retryDelayMinutes));
        includeFilesSwitch.setChecked(task.includeFiles);
        enabledSwitch.setChecked(task.enabled);
        actionSpinner.setSelection(task.isWebhook() ? 1 : 0);
        int projectIndex = projectIds.indexOf(task.projectId);
        if (projectIndex >= 0) projectSpinner.setSelection(projectIndex);
        int webhookIndex = webhookIds.indexOf(task.webhookId);
        if (webhookIndex >= 0) webhookSpinner.setSelection(webhookIndex);
        if (!task.lastStatus.isEmpty()) {
            statusText.setText("Last run: " + task.lastStatus
                    + (task.lastError.isEmpty() ? "" : " · " + task.lastError));
        }
        updateSections();
    }

    private void updateSections() {
        boolean backup = actionSpinner.getSelectedItemPosition() == 0;
        boolean oneTime = scheduleSpinner.getSelectedItemPosition() == 1;
        projectSection.setVisibility(backup ? View.VISIBLE : View.GONE);
        webhookSection.setVisibility(backup ? View.GONE : View.VISIBLE);
        runAtSection.setVisibility(oneTime ? View.VISIBLE : View.GONE);
        includeFilesSwitch.setEnabled(backup);
    }

    private ScheduledTask buildTask() {
        ScheduledTask task = id == null ? new ScheduledTask() : ScheduledTaskStore.get(this).get(id);
        if (task == null) task = new ScheduledTask();
        if (task.id == null || task.id.isEmpty()) task.id = UUID.randomUUID().toString();
        if (task.createdAt == 0) task.createdAt = System.currentTimeMillis();
        task.name = nameInput.getText().toString().trim();
        task.action = actionSpinner.getSelectedItemPosition() == 0
                ? ScheduledTask.ACTION_BACKUP : ScheduledTask.ACTION_WEBHOOK;
        task.projectId = task.isBackup() && !projectIds.isEmpty()
                ? projectIds.get(Math.max(0, projectSpinner.getSelectedItemPosition())) : "";
        task.webhookId = task.isWebhook() && !webhookIds.isEmpty()
                ? webhookIds.get(Math.max(0, webhookSpinner.getSelectedItemPosition())) : "";
        task.includeFiles = includeFilesSwitch.isChecked();
        task.enabled = enabledSwitch.isChecked();
        task.scheduleType = scheduleSpinner.getSelectedItemPosition() == 1
                ? ScheduledTask.SCHEDULE_ONCE : ScheduledTask.SCHEDULE_RECURRING;
        try {
            task.intervalMinutes = Math.max(1, Integer.parseInt(intervalInput.getText().toString().trim()));
        } catch (Exception e) {
            task.intervalMinutes = 0;
        }
        task.runAt = parseRunAt(runAtInput.getText().toString().trim());
        try {
            task.maxAttempts = Math.max(1, Integer.parseInt(maxAttemptsInput.getText().toString().trim()));
        } catch (Exception e) {
            task.maxAttempts = 0;
        }
        try {
            task.retryDelayMinutes = Math.max(1, Integer.parseInt(retryDelayInput.getText().toString().trim()));
        } catch (Exception e) {
            task.retryDelayMinutes = 0;
        }
        return task;
    }

    private long parseRunAt(String value) {
        if (value.isEmpty()) return 0;
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(value).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean validate(ScheduledTask task) {
        if (task.name.isEmpty()) {
            nameInput.setError("Name is required");
            return false;
        }
        if (!task.isOneTime() && task.intervalMinutes < 1) {
            intervalInput.setError("Enter minutes, minimum 1");
            return false;
        }
        if (task.isOneTime() && task.runAt <= 0) {
            runAtInput.setError("Use yyyy-MM-dd HH:mm");
            return false;
        }
        if (task.maxAttempts < 1) {
            maxAttemptsInput.setError("Enter 1 or more");
            return false;
        }
        if (task.retryDelayMinutes < 1) {
            retryDelayInput.setError("Enter minutes, minimum 1");
            return false;
        }
        if (task.isBackup() && task.projectId.isEmpty()) {
            Toast.makeText(this, "Create a project first", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (task.isWebhook() && task.webhookId.isEmpty()) {
            Toast.makeText(this, "Create a webhook first", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private boolean persist() {
        ScheduledTask task = buildTask();
        if (!validate(task)) return false;
        task.nextRunAt = task.computeNextRunAt(System.currentTimeMillis());
        ScheduledTaskStore.get(this).save(task);
        ScheduledTaskScheduler.scheduleNext(this);
        id = task.id;
        return true;
    }

    private void runNow() {
        ScheduledTask task = buildTask();
        if (!validate(task)) return;
        task.nextRunAt = task.computeNextRunAt(System.currentTimeMillis());
        ScheduledTaskStore.get(this).save(task);
        id = task.id;
        ScheduledTaskScheduler.scheduleNext(this);
        statusText.setText("Running…");
        findViewById(R.id.run_button).setEnabled(false);
        new Thread(() -> {
            ScheduledTaskRunner.TaskResult result = ScheduledTaskRunner.runNow(this, task.id);
            ScheduledTaskScheduler.scheduleNext(this);
            runOnUiThread(() -> {
                findViewById(R.id.run_button).setEnabled(true);
                statusText.setText(result.success ? "Task completed" : "Task failed: " + result.error);
                load();
            });
        }, "PhoneServer-TaskRun").start();
    }

    private void confirmDelete() {
        if (id == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete task?")
                .setMessage("The task will stop running.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    ScheduledTaskStore.get(this).delete(id);
                    ScheduledTaskScheduler.scheduleNext(this);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static class SimpleItemSelected implements android.widget.AdapterView.OnItemSelectedListener {
        private final Runnable callback;

        SimpleItemSelected(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
            callback.run();
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }
}
