package com.yadaventerprise.phoneserver;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class ServerSettingsActivity extends AppCompatActivity {

    private EditText passwordInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_settings);

        passwordInput = findViewById(R.id.password_input);
        passwordInput.setText(ContentStore.getPassword(this));

        findViewById(R.id.save_button).setOnClickListener(v -> {
            String password = passwordInput.getText().toString();
            ContentStore.setPassword(this, password);
            Toast.makeText(this, password.isEmpty() ? "Password removed" : "Password saved", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
