package com.yadaventerprise.phoneserver;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Views (and, for local files, edits) plain text files: .txt, .md, .json, .csv,
 * .html, .css, .js, .xml, and similar. Files opened from a remote server are
 * shown read-only, since they're a temporary local copy, not the real file.
 */
public class TextFileViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_EDITABLE = "editable";

    private static final long MAX_TEXT_FILE_BYTES = 2 * 1024 * 1024; // 2 MB

    private EditText textInput;
    private File file;
    private boolean editable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_file_viewer);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        editable = getIntent().getBooleanExtra(EXTRA_EDITABLE, false);
        file = path != null ? new File(path) : null;

        TextView title = findViewById(R.id.file_title);
        TextView readOnlyNotice = findViewById(R.id.read_only_notice);
        textInput = findViewById(R.id.text_input);
        Button saveButton = findViewById(R.id.save_button);

        if (file == null || !file.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        title.setText(file.getName());

        if (file.length() > MAX_TEXT_FILE_BYTES) {
            Toast.makeText(this, "File is too large to view as text", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            textInput.setText(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            Toast.makeText(this, "Could not read this file", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (editable) {
            saveButton.setVisibility(View.VISIBLE);
            readOnlyNotice.setVisibility(View.GONE);
            textInput.setEnabled(true);
            saveButton.setOnClickListener(v -> save());
        } else {
            saveButton.setVisibility(View.GONE);
            readOnlyNotice.setVisibility(View.VISIBLE);
            textInput.setEnabled(false);
        }
    }

    private void save() {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(textInput.getText().toString().getBytes(StandardCharsets.UTF_8));
            ContentStore.scanFile(this, file);
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Failed to save: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
