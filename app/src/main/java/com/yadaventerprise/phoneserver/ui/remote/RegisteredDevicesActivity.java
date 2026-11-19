package com.yadaventerprise.phoneserver.ui.remote;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.remote.RemoteDevice;
import com.yadaventerprise.phoneserver.data.remote.RemoteDeviceStore;
import com.yadaventerprise.phoneserver.data.remote.RemoteSession;
import com.yadaventerprise.phoneserver.data.remote.RemoteSessionStore;
import com.yadaventerprise.phoneserver.data.remote.RemoteSettings;
import com.yadaventerprise.phoneserver.data.remote.RemoteSettingsStore;
import com.yadaventerprise.phoneserver.security.PathGuard;
import java.util.Collections;
import java.util.List;

public class RegisteredDevicesActivity extends AppCompatActivity {

    private TextView summaryText;
    private TextView emptyText;
    private LinearLayout devicesContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_registered_devices);

        summaryText = findViewById(R.id.devices_summary_text);
        emptyText = findViewById(R.id.devices_empty);
        devicesContainer = findViewById(R.id.devices_container);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.refresh_button).setOnClickListener(v -> load());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        new Thread(() -> {
            final RemoteSettings settings = RemoteSettingsStore.get(getApplicationContext()).get();
            final List<RemoteDevice> devices = RemoteDeviceStore.get(getApplicationContext()).all();
            Collections.sort(devices, (a, b) -> Long.compare(b.lastSeenAt, a.lastSeenAt));
            runOnUiThread(() -> {
                if (isFinishing()) return;
                long now = System.currentTimeMillis();
                long threshold = settings.offlineThresholdSeconds * 1000L;
                int online = 0;
                for (RemoteDevice device : devices) {
                    if (device.isOnline(now, threshold)) online++;
                }
                summaryText.setText("Total: " + devices.size()
                        + " · Online: " + online
                        + " · Offline criteria: no heartbeat for "
                        + settings.offlineThresholdSeconds + " seconds");
                emptyText.setVisibility(devices.isEmpty()
                        ? android.view.View.VISIBLE : android.view.View.GONE);
                devicesContainer.removeAllViews();
                for (RemoteDevice device : devices) {
                    addDeviceCard(device, now, threshold);
                }
            });
        }).start();
    }

    private void addDeviceCard(RemoteDevice device, long now, long threshold) {
        LinearLayout card = RemoteUi.card(this);
        boolean online = device.isOnline(now, threshold);
        String status;
        if (device.revoked) status = "revoked";
        else if (device.disconnected) status = "disconnected";
        else status = online ? "online" : "offline";

        String deviceInfo = "";
        if (!device.manufacturer.isEmpty() || !device.model.isEmpty()) {
            deviceInfo = (device.manufacturer + " " + device.model).trim();
        }
        if (!device.osVersion.isEmpty()) {
            deviceInfo += deviceInfo.isEmpty()
                    ? "Android " + device.osVersion : " · Android " + device.osVersion;
        }
        if (!device.appVersion.isEmpty()) {
            deviceInfo += deviceInfo.isEmpty()
                    ? "App " + device.appVersion : " · App " + device.appVersion;
        }
        if (deviceInfo.isEmpty()) deviceInfo = "No hardware details reported";

        card.addView(RemoteUi.row(this, R.drawable.ic_device_admin,
                device.name.isEmpty() ? "Unnamed device" : device.name,
                "Status: " + status + " · " + deviceInfo));
        card.addView(RemoteUi.detail(this, "Last seen: " + RemoteUi.time(device.lastSeenAt)));
        card.addView(RemoteUi.detail(this, "Registered: " + RemoteUi.time(device.registeredAt)));

        String report;
        if (!device.hasReport()) report = "No status report received";
        else if (device.isReportStale(now, threshold)) {
            report = "Report stale (received " + RemoteUi.time(device.lastReportAt) + ")";
        } else {
            report = "Report fresh (received " + RemoteUi.time(device.lastReportAt) + ")";
        }
        card.addView(RemoteUi.detail(this, report));
        if (!device.healthStatus.isEmpty()) {
            card.addView(RemoteUi.detail(this, "Reported health: " + device.healthStatus));
        }
        if (device.lastUptimeMillis >= 0) {
            card.addView(RemoteUi.detail(this, "Reported uptime: "
                    + formatDuration(device.lastUptimeMillis)));
        }
        if (device.storageTotalBytes > 0 && device.storageFreeBytes >= 0) {
            card.addView(RemoteUi.detail(this, "Reported storage: "
                    + android.text.format.Formatter.formatShortFileSize(this, device.storageFreeBytes)
                    + " free of "
                    + android.text.format.Formatter.formatShortFileSize(this, device.storageTotalBytes)));
        }
        if (device.activeConnections >= 0) {
            card.addView(RemoteUi.detail(this, "Reported connections: " + device.activeConnections));
        }

        LinearLayout actions = RemoteUi.actions(this);
        TextView rename = RemoteUi.action(this, "Rename");
        rename.setOnClickListener(v -> showRenameDialog(device));
        actions.addView(rename);
        if (!device.disconnected && !device.revoked) {
            TextView disconnect = RemoteUi.action(this, "Mark disconnected");
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(RemoteUi.dp(this, 8));
            disconnect.setLayoutParams(params);
            disconnect.setOnClickListener(v -> RemoteUi.confirm(this, "Mark disconnected?",
                    "The device will be treated as offline until it reports again.",
                    "Disconnect", () -> {
                        RemoteDeviceStore.get(getApplicationContext()).disconnect(device.id);
                        load();
                    }));
            actions.addView(disconnect);
        }
        if (!device.revoked) {
            TextView revoke = RemoteUi.action(this, "Revoke");
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(RemoteUi.dp(this, 8));
            revoke.setLayoutParams(params);
            revoke.setOnClickListener(v -> RemoteUi.confirm(this, "Revoke device?",
                    "This permanently blocks " + device.name + " and revokes its sessions.",
                    "Revoke", () -> {
                        RemoteDeviceStore.get(getApplicationContext()).revoke(device.id);
                        RemoteSessionStore.get(getApplicationContext())
                                .revokeSubject(RemoteSession.SUBJECT_DEVICE, device.id);
                        Toast.makeText(this, "Device revoked", Toast.LENGTH_SHORT).show();
                        load();
                    }));
            actions.addView(revoke);
        }
        card.addView(actions);
        devicesContainer.addView(card);
    }

    private void showRenameDialog(RemoteDevice device) {
        EditText input = RemoteUi.input(this, "New device name", device.name, false);
        new AlertDialog.Builder(this)
                .setTitle("Rename device")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!PathGuard.isValidSegment(name)) {
                        Toast.makeText(this, "Enter a valid device name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    RemoteDeviceStore.get(getApplicationContext()).rename(device.id, name);
                    Toast.makeText(this, "Device renamed", Toast.LENGTH_SHORT).show();
                    load();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String formatDuration(long millis) {
        long minutes = Math.max(0, millis) / 60_000L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        hours %= 24L;
        minutes %= 60L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }
}
