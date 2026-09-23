package com.yadaventerprise.phoneserver;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/** Full-screen add/edit form for a saved remote server, replacing the old inline dialog. */
public class AddServerActivity extends AppCompatActivity {

    public static final String EXTRA_PROFILE_ID = "profile_id";
    public static final String EXTRA_PREFILL_IP = "prefill_ip";
    public static final String EXTRA_PREFILL_PORT = "prefill_port";

    private EditText nameInput, ipInput, portInput, usernameInput, passwordInput, descriptionInput;
    private ImageView togglePasswordVisibility;
    private boolean passwordVisible = false;
    private String editingId = null;

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
            }
        } else {
            String prefillIp = getIntent().getStringExtra(EXTRA_PREFILL_IP);
            int prefillPort = getIntent().getIntExtra(EXTRA_PREFILL_PORT, 0);
            if (prefillIp != null) ipInput.setText(prefillIp);
            if (prefillPort > 0) portInput.setText(String.valueOf(prefillPort));
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

        ServerProfileStore.save(this, profile);
        setResult(RESULT_OK);
        finish();
    }
}
