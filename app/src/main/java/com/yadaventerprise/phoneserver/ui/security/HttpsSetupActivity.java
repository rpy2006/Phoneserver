package com.yadaventerprise.phoneserver.ui.security;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfig;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfigStore;
import com.yadaventerprise.phoneserver.network.tls.TlsCertificateStore;
import com.yadaventerprise.phoneserver.network.tls.TlsIdentity;
import com.yadaventerprise.phoneserver.service.ServerService;
import java.io.InputStream;

public class HttpsSetupActivity extends AppCompatActivity {

    private static final int REQUEST_PKCS12 = 401;

    private Switch httpsSwitch;
    private Switch localIpSwitch;
    private EditText portInput;
    private EditText hostnameInput;
    private EditText publicUrlInput;
    private TextView certSubject;
    private TextView fingerprintText;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_https_setup);

        httpsSwitch = findViewById(R.id.switch_https);
        localIpSwitch = findViewById(R.id.switch_local_ip);
        portInput = findViewById(R.id.input_port);
        hostnameInput = findViewById(R.id.input_hostname);
        publicUrlInput = findViewById(R.id.input_public_url);
        certSubject = findViewById(R.id.cert_subject);
        fingerprintText = findViewById(R.id.fingerprint_text);
        statusText = findViewById(R.id.status_text);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.save_button).setOnClickListener(v -> save());
        findViewById(R.id.generate_button).setOnClickListener(v -> confirmGenerate());
        findViewById(R.id.import_button).setOnClickListener(v -> pickCertificate());

        load();
    }

    private void load() {
        HttpsConfig config = HttpsConfigStore.get(this).get();
        httpsSwitch.setChecked(config.enabled);
        localIpSwitch.setChecked(config.includeLocalIps);
        portInput.setText(String.valueOf(config.port));
        hostnameInput.setText(config.hostname);
        publicUrlInput.setText(config.publicUrl);
        renderIdentity(TlsCertificateStore.get(this).load());
        statusText.setText(ServerService.isHttpsRunning()
                ? "HTTPS is running on port " + config.port
                : "HTTPS is not running");
    }

    private void renderIdentity(TlsIdentity identity) {
        if (identity == null) {
            certSubject.setText("No certificate yet");
            fingerprintText.setText("");
            return;
        }
        certSubject.setText(identity.subject);
        fingerprintText.setText("SHA-256  " + identity.fingerprint);
    }

    private HttpsConfig readConfig() {
        HttpsConfig config = new HttpsConfig();
        config.enabled = httpsSwitch.isChecked();
        try {
            config.port = Integer.parseInt(portInput.getText().toString().trim());
        } catch (Exception e) {
            config.port = HttpsConfig.DEFAULT_PORT;
        }
        config.port = HttpsConfig.clampPort(config.port);
        config.hostname = HttpsConfig.cleanHostname(hostnameInput.getText().toString());
        config.publicUrl = HttpsConfig.cleanUrl(publicUrlInput.getText().toString());
        config.includeLocalIps = localIpSwitch.isChecked();
        return config;
    }

    private void save() {
        HttpsConfig config = readConfig();
        if (config.enabled && TlsCertificateStore.get(this).load() == null) {
            statusText.setText("Generating certificate…");
            new Thread(() -> {
                try {
                    TlsCertificateStore.get(this).generate(this, config);
                    runOnUiThread(() -> finishSave(config));
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        statusText.setText("Certificate generation failed");
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
            return;
        }
        finishSave(config);
    }

    private void finishSave(HttpsConfig config) {
        HttpsConfigStore.get(this).save(config);
        renderIdentity(TlsCertificateStore.get(this).load());
        restartServer();
        Toast.makeText(this, "HTTPS settings saved", Toast.LENGTH_SHORT).show();
        statusText.setText(config.enabled
                ? "HTTPS is running on port " + config.port
                : "HTTPS is disabled");
    }

    private void confirmGenerate() {
        new AlertDialog.Builder(this)
                .setTitle("Generate new certificate?")
                .setMessage("Existing paired devices pin the old fingerprint and will need to pair again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Generate", (dialog, which) -> generateCertificate())
                .show();
    }

    private void generateCertificate() {
        HttpsConfig config = readConfig();
        statusText.setText("Generating certificate…");
        new Thread(() -> {
            try {
                TlsIdentity identity = TlsCertificateStore.get(this).generate(this, config);
                runOnUiThread(() -> {
                    renderIdentity(identity);
                    HttpsConfigStore.get(this).save(config);
                    restartServer();
                    statusText.setText("Certificate generated");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("Certificate generation failed");
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void pickCertificate() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_PKCS12);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PKCS12 || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        EditText password = new EditText(this);
        password.setHint("PKCS#12 password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("Certificate password")
                .setView(password)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Import", (dialog, which) ->
                        importCertificate(uri, password.getText().toString().toCharArray()))
                .show();
    }

    private void importCertificate(Uri uri, char[] password) {
        statusText.setText("Importing certificate…");
        new Thread(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalArgumentException("Could not open certificate");
                TlsIdentity identity = TlsCertificateStore.get(this).importPkcs12(this, input, password);
                HttpsConfig config = readConfig();
                config.enabled = true;
                runOnUiThread(() -> {
                    renderIdentity(identity);
                    HttpsConfigStore.get(this).save(config);
                    httpsSwitch.setChecked(true);
                    restartServer();
                    statusText.setText("Certificate imported");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("Import failed");
                    Toast.makeText(this, e.getMessage() == null ? "Import failed" : e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void restartServer() {
        if (!ServerService.isServerRunning()) return;
        Intent intent = new Intent(this, ServerService.class);
        intent.setAction(ServerService.ACTION_RESTART);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}
