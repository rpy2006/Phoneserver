package com.yadaventerprise.phoneserver;

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/** Sets the single shared username/password checked on file-management requests to this device. */
public class AccountSettingsActivity extends AppCompatActivity {

    private EditText usernameInput, passwordInput;
    private ImageView togglePasswordVisibility;
    private boolean passwordVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_settings);

        usernameInput = findViewById(R.id.input_username);
        passwordInput = findViewById(R.id.input_password);
        togglePasswordVisibility = findViewById(R.id.toggle_password_visibility);

        usernameInput.setText(ContentStore.getUsername(this));
        passwordInput.setText(ContentStore.getPassword(this));

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.save_button).setOnClickListener(v -> save());
        togglePasswordVisibility.setOnClickListener(v -> togglePasswordVisibility());
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
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        ContentStore.setUsername(this, username);
        ContentStore.setPassword(this, password);

        Toast.makeText(this, password.isEmpty() ? "Saved - open access on your Wi-Fi" : "Saved", Toast.LENGTH_SHORT).show();
        finish();
    }
}
