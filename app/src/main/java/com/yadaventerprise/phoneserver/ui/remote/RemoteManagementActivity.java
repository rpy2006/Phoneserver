package com.yadaventerprise.phoneserver.ui.remote;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.remote.RemoteDevice;
import com.yadaventerprise.phoneserver.data.remote.RemoteDeviceStore;
import com.yadaventerprise.phoneserver.data.remote.RemotePairingRequest;
import com.yadaventerprise.phoneserver.data.remote.RemotePairingStore;
import com.yadaventerprise.phoneserver.data.remote.RemoteSession;
import com.yadaventerprise.phoneserver.data.remote.RemoteSessionStore;
import com.yadaventerprise.phoneserver.data.remote.RemoteSettings;
import com.yadaventerprise.phoneserver.data.remote.RemoteSettingsStore;
import com.yadaventerprise.phoneserver.data.remote.RemoteUserStore;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfig;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfigStore;
import com.yadaventerprise.phoneserver.service.ServerService;
import java.util.List;

public class RemoteManagementActivity extends AppCompatActivity {

    private TextView dashboardText;
    private TextView warningText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote_management);

        dashboardText = findViewById(R.id.dashboard_text);
        warningText = findViewById(R.id.warning_text);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.refresh_button).setOnClickListener(v -> refreshSummary());
        findViewById(R.id.row_pairing).setOnClickListener(v -> open(DevicePairingActivity.class));
        findViewById(R.id.row_devices).setOnClickListener(v -> open(RegisteredDevicesActivity.class));
        findViewById(R.id.row_users).setOnClickListener(v -> open(UserManagementActivity.class));
        findViewById(R.id.row_roles).setOnClickListener(v -> open(RolesPermissionsActivity.class));
        findViewById(R.id.row_files).setOnClickListener(v -> open(RemoteFileManagerActivity.class));
        findViewById(R.id.row_audit).setOnClickListener(v -> open(ActivityAuditLogsActivity.class));
        findViewById(R.id.row_settings).setOnClickListener(v -> open(RemoteAccessSettingsActivity.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSummary();
    }

    private void open(Class<?> activity) {
        startActivity(new Intent(this, activity));
    }

    private void refreshSummary() {
        new Thread(() -> {
            String summary;
            String warning;
            try {
                RemoteSettings settings = RemoteSettingsStore.get(getApplicationContext()).get();
                HttpsConfig https = HttpsConfigStore.get(getApplicationContext()).get();
                long now = System.currentTimeMillis();

                List<RemoteDevice> devices = RemoteDeviceStore.get(getApplicationContext()).all();
                int online = 0;
                for (RemoteDevice device : devices) {
                    if (device.isOnline(now, settings.offlineThresholdSeconds * 1000L)) online++;
                }

                int pending = 0;
                for (RemotePairingRequest request
                        : RemotePairingStore.get(getApplicationContext()).all()) {
                    if (RemotePairingRequest.STATUS_PENDING.equals(request.status)
                            && request.expiresAt > now) pending++;
                }

                int activeSessions = 0;
                for (RemoteSession session : RemoteSessionStore.get(getApplicationContext()).all()) {
                    if (session.isActive()) activeSessions++;
                }

                int users = RemoteUserStore.get(getApplicationContext()).all().size();
                boolean serverRunning = ServerService.isServerRunning();
                boolean httpsActive = https.enabled && ServerService.isHttpsRunning();
                summary = "Server: " + (serverRunning ? "running" : "stopped")
                        + "\nHTTPS: " + (httpsActive ? "active" : (https.enabled
                        ? "configured, not active" : "disabled"))
                        + "\nDevices online: " + online + " of " + devices.size()
                        + "\nPending pairing: " + pending
                        + "\nUsers: " + users
                        + "\nActive sessions: " + activeSessions
                        + "\nRemote access: " + (settings.enabled ? "enabled" : "disabled")
                        + "\nHTTPS required: " + (settings.requireHttps ? "yes" : "no")
                        + "\nRemote files: " + (settings.allowRemoteFiles ? "allowed" : "blocked")
                        + "\nServer control: " + (settings.allowServerControl ? "allowed" : "blocked");

                if (settings.enabled && (!https.enabled || !settings.requireHttps)) {
                    warning = "WARNING: Remote access is enabled without enforced HTTPS. "
                            + "Enable HTTPS and require HTTPS before using remote management.";
                } else {
                    warning = "Remote access must use HTTPS and explicit pairing approval. "
                            + "Never share a pairing code, and approve only devices you recognize.";
                }
            } catch (Exception e) {
                summary = "Summary unavailable: " + e.getMessage();
                warning = "Remote access must use HTTPS and explicit pairing approval.";
            }
            final String finalSummary = summary;
            final String finalWarning = warning;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                dashboardText.setText(finalSummary);
                warningText.setText(finalWarning);
            });
        }).start();
    }
}
