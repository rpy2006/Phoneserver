package com.yadaventerprise.phoneserver.ui.security;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import java.text.DateFormat;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.database.Records;
import com.yadaventerprise.phoneserver.data.security.IpBlockStore;
import com.yadaventerprise.phoneserver.data.security.SecurityConfig;
import com.yadaventerprise.phoneserver.data.security.SecurityEventStore;
import com.yadaventerprise.phoneserver.data.security.SecurityReport;
import com.yadaventerprise.phoneserver.data.security.SecurityReportStore;
import com.yadaventerprise.phoneserver.data.security.SecurityScanner;
import com.yadaventerprise.phoneserver.data.security.SecuritySettings;
import com.yadaventerprise.phoneserver.data.security.SecuritySettingsStore;
import com.yadaventerprise.phoneserver.data.storage.BackupSchedule;
import com.yadaventerprise.phoneserver.data.storage.BackupScheduleStore;
import com.yadaventerprise.phoneserver.data.tokens.TokenStore;
import com.yadaventerprise.phoneserver.ui.api.ApiTokensActivity;

import java.util.Date;

public class SecurityCenterActivity extends AppCompatActivity {
    private TextView grade;
    private TextView score;
    private TextView summary;
    private TextView lastScan;
    private TextView eventsSubtitle;
    private TextView blockedSubtitle;
    private TextView tokensSubtitle;
    private TextView backupSubtitle;
    private TextView scanButton;
    private Switch autoBlockSwitch;
    private Switch accessLogSwitch;
    private RecyclerView recycler;
    private SecurityFindingAdapter adapter;
    private boolean updatingSwitches;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_security_center);

        grade = findViewById(R.id.security_grade);
        score = findViewById(R.id.security_score);
        summary = findViewById(R.id.security_summary);
        lastScan = findViewById(R.id.security_last_scan);
        eventsSubtitle = findViewById(R.id.events_subtitle);
        blockedSubtitle = findViewById(R.id.blocked_subtitle);
        tokensSubtitle = findViewById(R.id.tokens_subtitle);
        backupSubtitle = findViewById(R.id.backup_subtitle);
        scanButton = findViewById(R.id.scan_button);
        autoBlockSwitch = findViewById(R.id.switch_auto_block);
        accessLogSwitch = findViewById(R.id.switch_access_log);
        recycler = findViewById(R.id.findings_recycler);

        adapter = new SecurityFindingAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        scanButton.setOnClickListener(v -> runScan());
        findViewById(R.id.row_access_log).setOnClickListener(v ->
                startActivity(new Intent(this, AccessLogActivity.class)));
        findViewById(R.id.row_events).setOnClickListener(v ->
                startActivity(new Intent(this, SecurityEventsActivity.class)));
        findViewById(R.id.row_blocked_ips).setOnClickListener(v ->
                startActivity(new Intent(this, BlockedIpsActivity.class)));
        findViewById(R.id.row_tokens).setOnClickListener(v ->
                startActivity(new Intent(this, ApiTokensActivity.class)));
        findViewById(R.id.row_backups).setOnClickListener(v ->
                startActivity(new Intent(this, BackupScheduleActivity.class)));

        autoBlockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitches) return;
                SecuritySettings settings = SecuritySettingsStore.get(SecurityCenterActivity.this).get();
                settings.autoBlockEnabled = isChecked;
                SecuritySettingsStore.get(SecurityCenterActivity.this).save(settings);
            }
        });
        accessLogSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitches) return;
                SecuritySettings settings = SecuritySettingsStore.get(SecurityCenterActivity.this).get();
                settings.accessLogEnabled = isChecked;
                SecuritySettingsStore.get(SecurityCenterActivity.this).save(settings);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        SecuritySettings settings = SecuritySettingsStore.get(this).get();
        updatingSwitches = true;
        autoBlockSwitch.setChecked(settings.autoBlockEnabled);
        accessLogSwitch.setChecked(settings.accessLogEnabled);
        updatingSwitches = false;

        new Thread(() -> {
            SecurityReport report = SecurityReportStore.get(getApplicationContext()).latest();
            int events = SecurityEventStore.get(getApplicationContext()).countSince(
                    System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L);
            int blocked = IpBlockStore.get(getApplicationContext()).countActive();
            int tokenCount = 0;
            for (Records.ApiTokenRecord token : TokenStore.list(getApplicationContext())) {
                if (!token.revoked) tokenCount++;
            }
            final int tokens = tokenCount;
            BackupSchedule schedule = BackupScheduleStore.get(getApplicationContext()).get();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                renderReport(report);
                eventsSubtitle.setText(events == 1
                        ? "1 event in the last 7 days"
                        : events + " events in the last 7 days");
                blockedSubtitle.setText(blocked == 1
                        ? "1 active block"
                        : blocked + " active blocks");
                tokensSubtitle.setText(tokens == 1
                        ? "1 active token · roles and scopes"
                        : tokens + " active tokens · roles and scopes");
                if (schedule.enabled) {
                    String next = schedule.nextRunAt > 0
                            ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(new Date(schedule.nextRunAt))
                            : "not scheduled yet";
                    backupSubtitle.setText("Every " + schedule.intervalHours + "h · next " + next);
                } else {
                    backupSubtitle.setText("Disabled · automatic backups are off");
                }
            });
        }).start();
    }

    private void renderReport(SecurityReport report) {
        if (report == null) {
            grade.setText("?");
            score.setText("Not scanned yet");
            summary.setText("Run a scan to check the current configuration.");
            lastScan.setText("No scan has been run.");
            adapter.submit(null);
            findViewById(R.id.findings_empty).setVisibility(View.VISIBLE);
            return;
        }
        grade.setText(report.grade);
        score.setText(report.grade + " · " + report.score + "/100");
        summary.setText(report.summary);
        lastScan.setText("Last scan: " + DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(report.generatedAt)));
        adapter.submit(report.findings);
        findViewById(R.id.findings_empty).setVisibility(
                report.findings.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void runScan() {
        scanButton.setEnabled(false);
        scanButton.setText("Scanning…");
        new Thread(() -> {
            SecurityReport report = null;
            Exception error = null;
            try {
                report = SecurityScanner.scan(SecurityConfig.collect(getApplicationContext()));
                SecurityReportStore.get(getApplicationContext()).save(report);
            } catch (Exception e) {
                error = e;
            }
            final SecurityReport result = report;
            final Exception failure = error;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                scanButton.setEnabled(true);
                scanButton.setText("Scan");
                if (failure != null) {
                    Toast.makeText(this, "Scan failed: " + failure.getMessage(),
                            Toast.LENGTH_LONG).show();
                } else {
                    renderReport(result);
                }
                refresh();
            });
        }).start();
    }
}
