package com.yadaventerprise.phoneserver;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class WebsiteEditorActivity extends AppCompatActivity {

    private EditText htmlInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_website_editor);

        htmlInput = findViewById(R.id.html_input);
        findViewById(R.id.save_button).setOnClickListener(v -> save());

        loadExisting();
    }

    private void loadExisting() {
        File file = ContentStore.getWebsiteFile(this);
        if (file.exists()) {
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                htmlInput.setText(new String(bytes, StandardCharsets.UTF_8));
                return;
            } catch (IOException ignored) {
            }
        }
        htmlInput.setText(ContentStore.DEFAULT_HTML);
    }

    private void save() {
        File file = ContentStore.getWebsiteFile(this);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(htmlInput.getText().toString().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "Saved. Live at /", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Failed to save: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
