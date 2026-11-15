package com.yadaventerprise.phoneserver.ui.servers;

import android.os.Bundle;
import android.text.InputType;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.servers.ServerProfileStore;
import com.yadaventerprise.phoneserver.model.ServerProfile;
import com.yadaventerprise.phoneserver.network.remote.RemoteApiClient;

/** Full-screen add/edit form for a saved remote server, replacing the old inline dialog. */
public class AddServerActivity extends AppCompatActivity {

    public static final String EXTRA_PROFILE_ID = "profile_id";
    public static final String EXTRA_PREFILL_IP = "prefill_ip";
    public static final String EXTRA_PREFILL_PORT = "prefill_port";
    public static final String EXTRA_PREFILL_SCHEME = "prefill_scheme";
    public static final String EXTRA_PREFILL_FINGERPRINT = "prefill_fingerprint";
    public static final String EXTRA_PREFILL_PAIRING_CODE = "prefill_pairing_code";

    private EditText nameInput, ipInput, portInput, usernameInput, passwordInput, descriptionInput, fingerprintInput;
    private Switch httpsSwitch;
    private ImageView togglePasswordVisibility;
    private boolean passwordVisible = false;
    private String editingId = null;
    private String pairingCode = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_server);

        nameInput = findViewById(R.id.input_name);
        ipInput = findViewById(R.id.input_ip);
        portInput = findViewById(R.id.input_port);
        usernameInput = findViewById(R.id.input_username);
        passwordInput = findViewById(R.id.input_password);
        descriptionInput = findViewById(R.id.input_description);
        fingerprintInput = findViewById(R.id.input_fingerprint);
        httpsSwitch = findViewById(R.id.switch_https);
        togglePasswordVisibility = findViewById(R.id.toggle_password_visibility);
        TextView title = findViewById(R.id.screen_title);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.cancel_button).setOnClickListener(v -> finish());
        findViewById(R.id.save_button).setOnClickListener(v -> save());
        togglePasswordVisibility.setOnClickListener(v -> togglePasswordVisibility());

        editingId = getIntent().getStringExtra(EXTRA_PROFILE_ID);
        if (editingId != null) {
            title.setText("Edit Server");
            ServerProfile existing = ServerProfileStore.getById(this, editingId);
            if (existing != null) {
                nameInput.setText(existing.name);
                ipInput.setText(existing.ip);
                portInput.setText(String.valueOf(existing.port));
                usernameInput.setText(existing.username);
                passwordInput.setText(existing.password);
                descriptionInput.setText(existing.description);
                fingerprintInput.setText(existing.fingerprint);
                httpsSwitch.setChecked(existing.usesHttps());
            }
        } else {
            String prefillIp = getIntent().getStringExtra(EXTRA_PREFILL_IP);
            int prefillPort = getIntent().getIntExtra(EXTRA_PREFILL_PORT, 0);
            if (prefillIp != null) ipInput.setText(prefillIp);
            if (prefillPort > 0) portInput.setText(String.valueOf(prefillPort));
            String scheme = getIntent().getStringExtra(EXTRA_PREFILL_SCHEME);
            if (scheme != null) httpsSwitch.setChecked("https".equalsIgnoreCase(scheme));
            String fingerprint = getIntent().getStringExtra(EXTRA_PREFILL_FINGERPRINT);
            if (fingerprint != null) fingerprintInput.setText(fingerprint);
            String code = getIntent().getStringExtra(EXTRA_PREFILL_PAIRING_CODE);
            if (code != null) pairingCode = code;
        }
    }

    private void togglePasswordVisibility() {
        passwordVisible = !passwordVisible;
        int cursorPos = passwordInput.getSelectionStart();
        passwordInput.setInputType(passwordVisible
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        togglePasswordVisibility.setImageResource(passwordVisible ? R.drawable.ic_eye : R.drawable.ic_eye_off);
        if (cursorPos >= 0) passwordInput.setSelection(Math.min(cursorPos, passwordInput.getText().length()));
    }

    private void save() {
        String name = nameInput.getText().toString().trim();
        String ip = ipInput.getText().toString().trim();
        String portText = portInput.getText().toString().trim();
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        String description = descriptionInput.getText().toString().trim();
        String fingerprint = fingerprintInput.getText().toString().trim();

        if (ip.isEmpty()) {
            Toast.makeText(this, "Enter a host address", Toast.LENGTH_SHORT).show();
            return;
        }

        int port = 8080;
        try {
            if (!portText.isEmpty()) port = Integer.parseInt(portText);
        } catch (NumberFormatException ignored) {
        }

        ServerProfile profile = editingId != null ? ServerProfileStore.getById(this, editingId) : new ServerProfile();
        if (profile == null) profile = new ServerProfile();

        profile.name = name.isEmpty() ? ip : name;
        profile.ip = ip;
        profile.port = port;
        profile.username = username;
        profile.password = password;
        profile.description = description;
        profile.scheme = httpsSwitch.isChecked() ? "https" : "http";
        profile.fingerprint = fingerprint;

        if (!pairingCode.isEmpty()) {
            redeemAndSave(profile);
            return;
        }
        ServerProfileStore.save(this, profile);
        setResult(RESULT_OK);
        finish();
    }

    private void redeemAndSave(ServerProfile profile) {
        findViewById(R.id.save_button).setEnabled(false);
        Toast.makeText(this, "Pairing with server…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                RemoteApiClient.PairingResult result = RemoteApiClient.redeemPairing(
                        profile, pairingCode, nameInput.getText().toString().trim());
                profile.token = result.token;
                if ((profile.fingerprint == null || profile.fingerprint.isEmpty())) {
                    profile.fingerprint = result.fingerprint;
                }
                ServerProfileStore.save(this, profile);
                runOnUiThread(() -> {
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    findViewById(R.id.save_button).setEnabled(true);
                    Toast.makeText(this, e.getMessage() == null ? "Pairing failed" : e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "PhoneServer-Pairing").start();
    }
}
