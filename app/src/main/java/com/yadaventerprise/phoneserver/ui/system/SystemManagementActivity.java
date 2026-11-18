package com.yadaventerprise.phoneserver.ui.system;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.stats.ServerStats;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.data.system.AutomationSettings;
import com.yadaventerprise.phoneserver.data.system.AutomationSettingsStore;
import com.yadaventerprise.phoneserver.data.system.MaintenanceReport;
import com.yadaventerprise.phoneserver.data.system.SystemDiagnostics;
import com.yadaventerprise.phoneserver.data.system.SystemLogCollector;
import com.yadaventerprise.phoneserver.data.system.SystemMaintenance;
import com.yadaventerprise.phoneserver.service.ServerService;
import com.yadaventerprise.phoneserver.service.ServerWatchdog;
import com.yadaventerprise.phoneserver.ui.security.TasksActivity;
import com.yadaventerprise.phoneserver.ui.security.WebhookActivity;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class SystemManagementActivity extends AppCompatActivity {

    private static final String[] LOG_SOURCES = {"all", "system", "access", "security", "webhooks", "tasks"};

    private final Handler handler = new Handler();
    private TextView statusText;
    private TextView checksText;
    private TextView diagnosticsText;
    private TextView maintenanceText;
    private TextView logText;
    private TextView runDiagnosticsButton;
    private Switch autoRestartSwitch;
    private Switch startOnBootSwitch;
    private Switch notifyFailuresSwitch;
    private Switch notifyTasksSwitch;
    private Switch notifyStorageSwitch;
    private EditText watchdogInput;
    private EditText storageWarningInput;
    private EditText logRetentionInput;
    private EditText logSearchInput;
    private Spinner logSourceSpinner;
    private boolean loadingAutomation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_system_management);

        statusText = findViewById(R.id.status_text);
        checksText = findViewById(R.id.checks_text);
        diagnosticsText = findViewById(R.id.diagnostics_text);
        maintenanceText = findViewById(R.id.maintenance_text);
        logText = findViewById(R.id.log_text);
        runDiagnosticsButton = findViewById(R.id.run_diagnostics_button);
        autoRestartSwitch = findViewById(R.id.switch_auto_restart);
        startOnBootSwitch = findViewById(R.id.switch_start_on_boot);
        notifyFailuresSwitch = findViewById(R.id.switch_notify_failures);
        notifyTasksSwitch = findViewById(R.id.switch_notify_tasks);
        notifyStorageSwitch = findViewById(R.id.switch_notify_storage);
        watchdogInput = findViewById(R.id.watchdog_interval_input);
        storageWarningInput = findViewById(R.id.storage_warning_input);
        logRetentionInput = findViewById(R.id.log_retention_input);
        logSearchInput = findViewById(R.id.log_search_input);
        logSourceSpinner = findViewById(R.id.log_source_spinner);

        ArrayAdapter<String> sources = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"All sources", "System events", "Access log", "Security events", "Webhooks", "Tasks"});
        sources.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        logSourceSpinner.setAdapter(sources);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.refresh_button).setOnClickListener(v -> refreshAll());
        findViewById(R.id.start_button).setOnClickListener(v -> sendServiceAction(ServerService.ACTION_START));
        findViewById(R.id.stop_button).setOnClickListener(v -> sendServiceAction(ServerService.ACTION_STOP));
        findViewById(R.id.restart_button).setOnClickListener(v -> sendServiceAction(ServerService.ACTION_RESTART));
        findViewById(R.id.row_tasks).setOnClickListener(v ->
                startActivity(new Intent(this, TasksActivity.class)));
        findViewById(R.id.row_webhooks).setOnClickListener(v ->
                startActivity(new Intent(this, WebhookActivity.class)));
        findViewById(R.id.save_automation_button).setOnClickListener(v -> saveAutomation());
        runDiagnosticsButton.setOnClickListener(v -> runDiagnostics());
        findViewById(R.id.verify_backups_button).setOnClickListener(v -> verifyBackups());
        findViewById(R.id.clean_temp_button).setOnClickListener(v -> confirmCleanTemp());
        findViewById(R.id.refresh_logs_button).setOnClickListener(v -> loadLogs());

        CompoundButton.OnCheckedChangeListener ignored = (button, checked) -> {
        };
        autoRestartSwitch.setOnCheckedChangeListener(ignored);
        startOnBootSwitch.setOnCheckedChangeListener(ignored);
        notifyFailuresSwitch.setOnCheckedChangeListener(ignored);
        notifyTasksSwitch.setOnCheckedChangeListener(ignored);
        notifyStorageSwitch.setOnCheckedChangeListener(ignored);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    private void refreshAll() {
        refreshStatus();
        loadAutomation();
        refreshMaintenance();
        loadLogs();
    }

    private void refreshStatus() {
        new Thread(() -> {
            String status;
            String checks;
            try {
                JSONObject json = SystemDiagnostics.collect(getApplicationContext());
                JSONObject server = json.optJSONObject("server");
                JSONObject resources = json.optJSONObject("resources");
                JSONObject content = json.optJSONObject("content");
                JSONObject security = json.optJSONObject("security");
                String uptime = formatDuration(server == null ? 0 : server.optLong("uptimeMillis"));
                status = "Server: " + (server != null && server.optBoolean("running") ? "Running" : "Stopped")
                        + (server != null && server.optBoolean("httpsRunning") ? " · HTTPS active" : "")
                        + "\nPort: " + (server == null ? ServerService.PORT : server.optInt("port"))
                        + " · Uptime: " + uptime
                        + "\nAddress: " + (server == null || server.isNull("localIp")
                        ? "not detected" : server.optString("localIp"))
                        + "\nRequests: " + (server == null ? 0 : server.optLong("requestCount"))
                        + " · Active clients: " + (server == null ? 0 : server.optInt("activeClients"))
                        + "\nTraffic: " + ServerStats.formatBytes(server == null ? 0 : server.optLong("bytesDown"))
                        + " in, " + ServerStats.formatBytes(server == null ? 0 : server.optLong("bytesUp")) + " out"
                        + "\nApp CPU: " + (resources == null ? 0 : resources.optInt("appCpuPercent")) + "%"
                        + " · RAM: " + (resources == null ? 0 : resources.optInt("ramUsedPercent")) + "%"
                        + "\nStorage: " + ServerStats.formatBytes(resources == null ? 0
                        : resources.optLong("storageFreeBytes")) + " free of "
                        + ServerStats.formatBytes(resources == null ? 0 : resources.optLong("storageTotalBytes"))
                        + "\nContent: " + (content == null ? 0 : content.optInt("projects")) + " projects, "
                        + (content == null ? 0 : content.optInt("files")) + " files, "
                        + (content == null ? 0 : content.optInt("tasks")) + " tasks, "
                        + (content == null ? 0 : content.optInt("webhooks")) + " webhooks"
                        + "\nSecurity: password "
                        + (security != null && security.optBoolean("passwordConfigured") ? "set" : "not set")
                        + " · HTTPS "
                        + (security != null && security.optBoolean("httpsEnabled") ? "enabled" : "disabled")
                        + " · remote access "
                        + (security != null && security.optBoolean("remoteAccessEnabled") ? "enabled" : "disabled");
                JSONArray array = json.optJSONArray("checks");
                StringBuilder builder = new StringBuilder();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject check = array.optJSONObject(i);
                        if (check == null) continue;
                        builder.append(check.optBoolean("ok") ? "PASS  " : "CHECK  ")
                                .append(check.optString("name"))
                                .append(" — ")
                                .append(check.optString("detail"))
                                .append('\n');
                    }
                }
                checks = builder.toString().trim();
            } catch (Exception e) {
                status = "Status unavailable: " + e.getMessage();
                checks = "";
            }
            final String finalStatus = status;
            final String finalChecks = checks;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                statusText.setText(finalStatus);
                checksText.setText(finalChecks);
            });
        }).start();
    }

    private void loadAutomation() {
        new Thread(() -> {
            final AutomationSettings settings = AutomationSettingsStore.get(getApplicationContext()).get();
            final boolean startOnBoot = ContentStore.getStartOnBoot(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing()) return;
                loadingAutomation = true;
                autoRestartSwitch.setChecked(settings.autoRestartEnabled);
                startOnBootSwitch.setChecked(startOnBoot);
                notifyFailuresSwitch.setChecked(settings.notifyServerFailures && settings.notifyServerRecovery);
                notifyTasksSwitch.setChecked(settings.notifyTaskResults);
                notifyStorageSwitch.setChecked(settings.notifyStorageWarnings);
                watchdogInput.setText(String.valueOf(settings.watchdogIntervalMinutes));
                storageWarningInput.setText(String.valueOf(settings.storageWarningMb));
                logRetentionInput.setText(String.valueOf(settings.logRetentionDays));
                loadingAutomation = false;
            });
        }).start();
    }

    private void saveAutomation() {
        if (loadingAutomation) return;
        int watchdog = parsePositive(watchdogInput, 1, 1440, "watchdog interval");
        if (watchdog < 0) return;
        int storageWarning = parsePositive(storageWarningInput, 10, 1024 * 1024, "storage warning");
        if (storageWarning < 0) return;
        int retention = parsePositive(logRetentionInput, 1, 365, "log retention");
        if (retention < 0) return;

        AutomationSettings settings = AutomationSettingsStore.get(getApplicationContext()).get();
        settings.autoRestartEnabled = autoRestartSwitch.isChecked();
        settings.watchdogIntervalMinutes = watchdog;
        settings.notifyServerFailures = notifyFailuresSwitch.isChecked();
        settings.notifyServerRecovery = notifyFailuresSwitch.isChecked();
        settings.notifyTaskResults = notifyTasksSwitch.isChecked();
        settings.notifyStorageWarnings = notifyStorageSwitch.isChecked();
        settings.storageWarningMb = storageWarning;
        settings.logRetentionDays = retention;
        AutomationSettingsStore.get(getApplicationContext()).save(settings);
        ContentStore.setStartOnBoot(getApplicationContext(), startOnBootSwitch.isChecked());
        ServerWatchdog.scheduleNext(getApplicationContext());
        Toast.makeText(this, "Automation settings saved", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private int parsePositive(EditText input, int min, int max, String label) {
        String value = input.getText().toString().trim();
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) return parsed;
        } catch (NumberFormatException ignored) {
        }
        input.setError(label + " must be " + min + "–" + max);
        return -1;
    }

    private void runDiagnostics() {
        runDiagnosticsButton.setEnabled(false);
        runDiagnosticsButton.setText("Running…");
        new Thread(() -> {
            String result;
            try {
                JSONObject json = SystemDiagnostics.collect(getApplicationContext());
                JSONObject server = json.optJSONObject("server");
                JSONObject resources = json.optJSONObject("resources");
                JSONObject content = json.optJSONObject("content");
                JSONObject security = json.optJSONObject("security");
                JSONArray checks = json.optJSONArray("checks");
                int passed = 0;
                if (checks != null) {
                    for (int i = 0; i < checks.length(); i++) {
                        JSONObject check = checks.optJSONObject(i);
                        if (check != null && check.optBoolean("ok")) passed++;
                    }
                }
                result = "Generated " + DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(json.optLong("generatedAt")))
                        + "\nChecks passed: " + passed + "/" + (checks == null ? 0 : checks.length())
                        + "\nServer running: " + (server != null && server.optBoolean("running"))
                        + " · HTTPS: " + (server != null && server.optBoolean("httpsRunning"))
                        + "\nActive clients: " + (server == null ? 0 : server.optInt("activeClients"))
                        + "\nApp CPU: " + (resources == null ? 0 : resources.optInt("appCpuPercent")) + "%"
                        + " · RAM: " + (resources == null ? 0 : resources.optInt("ramUsedPercent")) + "%"
                        + "\nUsable storage: " + ServerStats.formatBytes(resources == null ? 0
                        : resources.optLong("storageFreeBytes"))
                        + "\nProjects: " + (content == null ? 0 : content.optInt("projects"))
                        + " · Databases: " + (content == null ? 0 : content.optInt("databases"))
                        + " · Files: " + (content == null ? 0 : content.optInt("files"))
                        + "\nTasks: " + (content == null ? 0 : content.optInt("tasks"))
                        + " (" + (content == null ? 0 : content.optInt("enabledTasks")) + " enabled, "
                        + (content == null ? 0 : content.optInt("failedTasks")) + " failed)"
                        + "\nWebhooks: " + (content == null ? 0 : content.optInt("webhooks"))
                        + " (" + (content == null ? 0 : content.optInt("enabledWebhooks")) + " enabled)"
                        + "\nPassword configured: " + (security != null
                        && security.optBoolean("passwordConfigured"))
                        + "\nRemote access: " + (security != null
                        && security.optBoolean("remoteAccessEnabled"));
            } catch (Exception e) {
                result = "Diagnostics failed: " + e.getMessage();
            }
            final String finalResult = result;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                diagnosticsText.setText(finalResult);
                runDiagnosticsButton.setEnabled(true);
                runDiagnosticsButton.setText("Run diagnostics");
            });
        }).start();
    }

    private void refreshMaintenance() {
        new Thread(() -> {
            final String report = formatMaintenance(SystemMaintenance.inspect(getApplicationContext()));
            runOnUiThread(() -> {
                if (!isFinishing()) maintenanceText.setText(report);
            });
        }).start();
    }

    private void confirmCleanTemp() {
        new AlertDialog.Builder(this)
                .setTitle("Clean temporary files?")
                .setMessage("Removes only cached preview files created by PhoneServer. Projects, files, backups and logs are not deleted.")
                .setPositiveButton("Clean", (dialog, which) -> cleanTemp())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void cleanTemp() {
        Toast.makeText(this, "Cleaning temporary files…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final MaintenanceReport report = SystemMaintenance.cleanTempFiles(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing()) return;
                maintenanceText.setText(formatMaintenance(report));
                Toast.makeText(this, "Removed " + report.removedFiles + " temporary file(s)",
                        Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void verifyBackups() {
        Toast.makeText(this, "Verifying backups…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final MaintenanceReport report = SystemMaintenance.verifyBackups(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing()) return;
                maintenanceText.setText(formatMaintenance(report));
                Toast.makeText(this, report.invalidBackups == 0
                                ? "All checked backups are valid"
                                : report.invalidBackups + " invalid backup(s) found",
                        Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private String formatMaintenance(MaintenanceReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("Inspected ")
                .append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(new Date(report.generatedAt)))
                .append("\nTemporary files: ")
                .append(report.tempFilesCount)
                .append(" (")
                .append(ServerStats.formatBytes(report.tempBytes))
                .append(")")
                .append("\nJSON configs checked: ")
                .append(report.jsonFilesChecked)
                .append("\nBackups: ")
                .append(report.validBackups)
                .append(" valid, ")
                .append(report.invalidBackups)
                .append(" invalid of ")
                .append(report.backupsChecked)
                .append(" checked")
                .append("\nLog size: ")
                .append(ServerStats.formatBytes(report.logBytes));
        if (report.issueCount() > 0) {
            builder.append("\nIssues:");
            for (int i = 0; i < report.issues.size() && i < 5; i++) {
                builder.append("\n• ").append(report.issues.get(i));
            }
            if (report.issues.size() > 5) {
                builder.append("\n+ ").append(report.issues.size() - 5).append(" more");
            }
        } else {
            builder.append("\nNo issues found");
        }
        return builder.toString();
    }

    private void loadLogs() {
        new Thread(() -> {
            SystemLogCollector.Query query = new SystemLogCollector.Query();
            int position = logSourceSpinner.getSelectedItemPosition();
            if (position > 0 && position < LOG_SOURCES.length) query.source = LOG_SOURCES[position];
            query.search = logSearchInput.getText().toString().trim();
            query.limit = 200;
            List<SystemLogCollector.SystemLogEntry> entries =
                    SystemLogCollector.collect(getApplicationContext(), query);
            StringBuilder builder = new StringBuilder();
            if (entries.isEmpty()) {
                builder.append("No matching log entries.");
            } else {
                DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);
                for (SystemLogCollector.SystemLogEntry entry : entries) {
                    builder.append(format.format(new Date(entry.time)))
                            .append(" [")
                            .append(entry.level.toUpperCase())
                            .append("/")
                            .append(entry.source)
                            .append("] ")
                            .append(entry.message)
                            .append('\n');
                }
            }
            final String finalLog = builder.toString().trim();
            runOnUiThread(() -> {
                if (!isFinishing()) logText.setText(finalLog);
            });
        }).start();
    }

    private String formatDuration(long millis) {
        if (millis <= 0) return "0m";
        long seconds = millis / 1000L;
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, ServerService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        Toast.makeText(this, "Server command sent", Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::refreshStatus, 900);
    }

    public static void open(Context context) {
        context.startActivity(new Intent(context, SystemManagementActivity.class));
    }
}
