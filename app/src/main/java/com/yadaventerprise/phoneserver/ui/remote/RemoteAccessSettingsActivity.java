package com.yadaventerprise.phoneserver.ui.remote;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.remote.RemoteSettings;
import com.yadaventerprise.phoneserver.data.remote.RemoteSettingsStore;

public class RemoteAccessSettingsActivity extends AppCompatActivity {

    private Switch enabledSwitch;
    private Switch requireHttpsSwitch;
    private Switch allowPairingSwitch;
    private Switch allowFilesSwitch;
    private Switch allowControlSwitch;
    private EditText sessionInput;
    private EditText deviceSessionInput;
    private EditText pairingInput;
    private EditText offlineInput;
    private EditText failedInput;
    private EditText lockoutInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote_access_settings);

        enabledSwitch = findViewById(R.id.switch_remote_enabled);
        requireHttpsSwitch = findViewById(R.id.switch_require_https);
        allowPairingSwitch = findViewById(R.id.switch_allow_pairing);
        allowFilesSwitch = findViewById(R.id.switch_allow_remote_files);
        allowControlSwitch = findViewById(R.id.switch_allow_server_control);
        sessionInput = findViewById(R.id.input_session_ttl);
        deviceSessionInput = findViewById(R.id.input_device_session_ttl);
        pairingInput = findViewById(R.id.input_pairing_ttl);
        offlineInput = findViewById(R.id.input_offline_threshold);
        failedInput = findViewById(R.id.input_max_failed_logins);
        lockoutInput = findViewById(R.id.input_lockout_minutes);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.save_button).setOnClickListener(v -> save());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        new Thread(() -> {
            final RemoteSettings settings = RemoteSettingsStore.get(getApplicationContext()).get();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                enabledSwitch.setChecked(settings.enabled);
                requireHttpsSwitch.setChecked(settings.requireHttps);
                allowPairingSwitch.setChecked(settings.allowPairing);
                allowFilesSwitch.setChecked(settings.allowRemoteFiles);
                allowControlSwitch.setChecked(settings.allowServerControl);
                sessionInput.setText(String.valueOf(settings.sessionTtlMinutes));
                deviceSessionInput.setText(String.valueOf(settings.deviceSessionTtlDays));
                pairingInput.setText(String.valueOf(settings.pairingTtlMinutes));
                offlineInput.setText(String.valueOf(settings.offlineThresholdSeconds));
                failedInput.setText(String.valueOf(settings.maxFailedLoginAttempts));
                lockoutInput.setText(String.valueOf(settings.lockoutMinutes));
            });
        }).start();
    }

    private void save() {
        int session = parse(sessionInput, 15, 1440, "Session lifetime");
        if (session < 0) return;
        int deviceSession = parse(deviceSessionInput, 1, 30, "Device session lifetime");
        if (deviceSession < 0) return;
        int pairing = parse(pairingInput, 2, 60, "Pairing code lifetime");
        if (pairing < 0) return;
        int offline = parse(offlineInput, 30, 86400, "Offline threshold");
        if (offline < 0) return;
        int failed = parse(failedInput, 3, 20, "Max failed logins");
        if (failed < 0) return;
        int lockout = parse(lockoutInput, 1, 240, "Lockout duration");
        if (lockout < 0) return;

        RemoteSettings settings = new RemoteSettings();
        settings.enabled = enabledSwitch.isChecked();
        settings.requireHttps = requireHttpsSwitch.isChecked();
        settings.allowPairing = allowPairingSwitch.isChecked();
        settings.allowRemoteFiles = allowFilesSwitch.isChecked();
        settings.allowServerControl = allowControlSwitch.isChecked();
        settings.sessionTtlMinutes = session;
        settings.deviceSessionTtlDays = deviceSession;
        settings.pairingTtlMinutes = pairing;
        settings.offlineThresholdSeconds = offline;
        settings.maxFailedLoginAttempts = failed;
        settings.lockoutMinutes = lockout;

        StringBuilder warning = new StringBuilder();
        if (!settings.requireHttps) {
            warning.append("HTTPS is not required. Credentials and tokens could be sent over plain HTTP.\n");
        }
        if (settings.allowServerControl) {
            warning.append("Paired devices will be able to start, stop and restart this phone's server.\n");
        }
        if (warning.length() > 0) {
            RemoteUi.confirm(this, "Security warning", warning.toString().trim(),
                    "Save anyway", () -> persist(settings));
        } else {
            persist(settings);
        }
    }

    private void persist(RemoteSettings settings) {
        RemoteSettingsStore.get(getApplicationContext()).save(settings);
        Toast.makeText(this, "Remote access settings saved", Toast.LENGTH_SHORT).show();
        load();
    }

    private int parse(EditText input, int min, int max, String label) {
        String value = input.getText().toString().trim();
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) return parsed;
        } catch (NumberFormatException ignored) {
        }
        input.setError(label + " must be " + min + "–" + max);
        return -1;
    }
}
