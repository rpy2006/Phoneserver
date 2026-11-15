package com.yadaventerprise.phoneserver.ui.security;

import androidx.appcompat.app.AppCompatActivity;
import android.app.AlertDialog;
import android.os.Bundle;
import java.text.DateFormat;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.storage.BackupSchedule;
import com.yadaventerprise.phoneserver.data.storage.BackupScheduleStore;
import com.yadaventerprise.phoneserver.service.BackupScheduler;

import java.util.Date;

public class BackupScheduleActivity extends AppCompatActivity {
    private static final int[] INTERVALS = {6, 12, 24, 48, 72, 168};

    private Switch enabled;
    private Switch includeFiles;
    private Spinner interval;
    private EditText retain;
    private TextView lastRun;
    private TextView nextRun;
    private TextView lastResult;
    private TextView saveButton;
    private TextView runNowButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_backup_schedule);

        enabled = findViewById(R.id.switch_enabled);
        includeFiles = findViewById(R.id.switch_files);
        interval = findViewById(R.id.interval_spinner);
        retain = findViewById(R.id.retain_input);
        lastRun = findViewById(R.id.last_run);
        nextRun = findViewById(R.id.next_run);
        lastResult = findViewById(R.id.last_result);
        saveButton = findViewById(R.id.save_button);
        runNowButton = findViewById(R.id.run_now_button);

        ArrayAdapter<String> intervals = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Every 6 hours", "Every 12 hours", "Every 24 hours",
                        "Every 2 days", "Every 3 days", "Every 7 days"});
        intervals.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        interval.setAdapter(intervals);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        saveButton.setOnClickListener(v -> save());
        runNowButton.setOnClickListener(v -> confirmRunNow());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        BackupSchedule schedule = BackupScheduleStore.get(this).get();
        enabled.setChecked(schedule.enabled);
        includeFiles.setChecked(schedule.includeFiles);
        retain.setText(String.valueOf(schedule.retainPerProject));
        int position = 2;
        for (int i = 0; i < INTERVALS.length; i++) {
            if (INTERVALS[i] == schedule.intervalHours) {
                position = i;
                break;
            }
        }
        interval.setSelection(position);

        if (schedule.lastRunAt > 0) {
            lastRun.setText("Last run: " + DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(schedule.lastRunAt)));
        } else {
            lastRun.setText("No automatic backup has run yet.");
        }
        if (schedule.enabled && schedule.nextRunAt > 0) {
            nextRun.setText("Next run: " + DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(schedule.nextRunAt)));
        } else {
            nextRun.setText(schedule.enabled ? "Next run: not scheduled" : "Next run: schedule disabled");
        }
        if (schedule.lastResult.isEmpty()) {
            lastResult.setVisibility(View.GONE);
        } else {
            lastResult.setVisibility(View.VISIBLE);
            String text = "Last result: " + schedule.lastResult
                    + " (" + schedule.lastSuccessCount + " ok, "
                    + schedule.lastFailureCount + " failed)";
            if (!schedule.lastError.isEmpty()) text += "\n" + schedule.lastError;
            lastResult.setText(text);
        }
    }

    private void save() {
        int selected = interval.getSelectedItemPosition();
        int hours = selected >= 0 && selected < INTERVALS.length ? INTERVALS[selected] : 24;
        int keep;
        try {
            keep = Integer.parseInt(retain.getText().toString().trim());
        } catch (NumberFormatException e) {
            keep = 10;
        }
        keep = Math.max(1, Math.min(25, keep));

        BackupSchedule schedule = BackupScheduleStore.get(this).get();
        schedule.enabled = enabled.isChecked();
        schedule.includeFiles = includeFiles.isChecked();
        schedule.intervalHours = hours;
        schedule.retainPerProject = keep;
        BackupScheduleStore.get(this).save(schedule);
        BackupScheduler.scheduleNext(this);
        Toast.makeText(this, "Backup schedule saved", Toast.LENGTH_SHORT).show();
        load();
    }

    private void confirmRunNow() {
        new AlertDialog.Builder(this)
                .setTitle("Run backup now?")
                .setMessage("Every project will be backed up using the current schedule settings.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run", (dialog, which) -> runNow())
                .show();
    }

    private void runNow() {
        saveButton.setEnabled(false);
        runNowButton.setEnabled(false);
        runNowButton.setText("Running…");
        new Thread(() -> {
            BackupScheduler.RunResult result;
            try {
                result = BackupScheduler.runNow(getApplicationContext());
            } catch (Exception e) {
                result = new BackupScheduler.RunResult();
                result.failureCount = 1;
                result.errors.add(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final BackupScheduler.RunResult finalResult = result;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                saveButton.setEnabled(true);
                runNowButton.setEnabled(true);
                runNowButton.setText("Run backup now");
                Toast.makeText(this, finalResult.summary(), Toast.LENGTH_LONG).show();
                load();
            });
        }).start();
    }
}
