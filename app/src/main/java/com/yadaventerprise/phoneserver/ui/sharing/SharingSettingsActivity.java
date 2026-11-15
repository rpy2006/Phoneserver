package com.yadaventerprise.phoneserver.ui.sharing;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.sharing.ShareLink;
import com.yadaventerprise.phoneserver.data.sharing.ShareSettings;
import com.yadaventerprise.phoneserver.data.sharing.ShareSettingsStore;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfig;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfigStore;
import com.yadaventerprise.phoneserver.service.ServerService;

public class SharingSettingsActivity extends AppCompatActivity {

    private static final String[] PERMISSION_LABELS = {"View only", "View and download"};
    private static final String[] PERMISSION_VALUES = {
            ShareLink.PERMISSION_VIEW, ShareLink.PERMISSION_DOWNLOAD};

    private EditText expiryInput;
    private EditText downloadsInput;
    private Spinner permissionSpinner;
    private Switch passwordSwitch;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sharing_settings);

        expiryInput = findViewById(R.id.input_expiry);
        downloadsInput = findViewById(R.id.input_downloads);
        permissionSpinner = findViewById(R.id.permission_spinner);
        passwordSwitch = findViewById(R.id.switch_password);
        statusText = findViewById(R.id.status_text);
        TextView securityBanner = findViewById(R.id.settings_security_banner);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, PERMISSION_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        permissionSpinner.setAdapter(adapter);

        ShareSettings settings = ShareSettingsStore.get(this).get();
        expiryInput.setText(String.valueOf(settings.defaultExpiryMinutes));
        downloadsInput.setText(String.valueOf(settings.defaultMaxDownloads));
        permissionSpinner.setSelection(indexOf(settings.defaultPermission));
        passwordSwitch.setChecked(settings.requirePasswordByDefault);

        HttpsConfig https = HttpsConfigStore.get(this).get();
        if (https.enabled) {
            securityBanner.setText("HTTPS is enabled, so new share links use https://. "
                    + (https.publicUrl != null && !https.publicUrl.isEmpty()
                    ? "Links use the configured public URL."
                    : "Links use the local address because no public URL is configured.")
                    + (ServerService.isServerRunning() ? "" : " The server is not running."));
            securityBanner.setTextColor(getColor(R.color.home_accent));
        } else {
            securityBanner.setText("HTTPS is off. Share links use plain HTTP and are not encrypted. "
                    + "Enable HTTPS before sharing outside your local network.");
            securityBanner.setTextColor(getColor(R.color.offline_red));
        }

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.save_button).setOnClickListener(v -> save());
    }

    private void save() {
        Integer expiry = parseNonNegative(expiryInput.getText().toString());
        Integer downloads = parseNonNegative(downloadsInput.getText().toString());
        if (expiry == null || downloads == null) {
            Toast.makeText(this, "Enter 0 or a positive whole number", Toast.LENGTH_SHORT).show();
            return;
        }
        ShareSettings settings = new ShareSettings();
        settings.defaultExpiryMinutes = expiry;
        settings.defaultMaxDownloads = downloads;
        settings.defaultPermission = PERMISSION_VALUES[permissionSpinner.getSelectedItemPosition()];
        settings.requirePasswordByDefault = passwordSwitch.isChecked();
        ShareSettingsStore.get(this).save(settings);
        expiryInput.setText(String.valueOf(expiry));
        downloadsInput.setText(String.valueOf(downloads));
        statusText.setText("Settings saved.");
        Toast.makeText(this, "Sharing settings saved", Toast.LENGTH_SHORT).show();
    }

    private Integer parseNonNegative(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed < 0 ? null : parsed;
        } catch (Exception e) {
            return null;
        }
    }

    private int indexOf(String value) {
        for (int i = 0; i < PERMISSION_VALUES.length; i++) {
            if (PERMISSION_VALUES[i].equals(value)) return i;
        }
        return 0;
    }
}
