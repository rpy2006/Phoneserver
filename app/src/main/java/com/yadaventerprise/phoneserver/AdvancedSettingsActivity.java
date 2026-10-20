package com.yadaventerprise.phoneserver;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

public class AdvancedSettingsActivity extends AppCompatActivity {

    private TextView cacheSizeText;
    private EditText dataRootInput;
    private EditText allowedOriginsInput;
    private EditText rateLimitInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_advanced_settings);

        cacheSizeText = findViewById(R.id.cache_size_text);
        dataRootInput = findViewById(R.id.data_root_input);
        allowedOriginsInput = findViewById(R.id.allowed_origins_input);
        rateLimitInput = findViewById(R.id.rate_limit_input);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.row_clear_cache).setOnClickListener(v -> confirmClearCache());
        findViewById(R.id.row_reset_endpoints).setOnClickListener(v -> confirmResetEndpoints());
        findViewById(R.id.save_data_root).setOnClickListener(v -> saveDataRoot());
        findViewById(R.id.save_allowed_origins).setOnClickListener(v -> saveAllowedOrigins());
        findViewById(R.id.save_rate_limit).setOnClickListener(v -> saveRateLimit());

        loadDataPlatformSettings();
        refreshCacheSize();
    }

    private void loadDataPlatformSettings() {
        dataRootInput.setText(ContentStore.getDataRoot(this).getAbsolutePath());
        allowedOriginsInput.setText(ContentStore.getAllowedOrigins(this));
        rateLimitInput.setText(String.valueOf(ContentStore.getApiRateLimitPerMinute(this)));
    }

    private void saveDataRoot() {
        String path = dataRootInput.getText().toString().trim();
        ContentStore.setDataRoot(this, path);
        Toast.makeText(this, path.isEmpty()
                ? "Data root reset to default" : "Data root saved", Toast.LENGTH_SHORT).show();
        loadDataPlatformSettings();
    }

    private void saveAllowedOrigins() {
        String origins = allowedOriginsInput.getText().toString().trim();
        ContentStore.setAllowedOrigins(this, origins);
        Toast.makeText(this, "Allowed origins saved", Toast.LENGTH_SHORT).show();
    }

    private void saveRateLimit() {
        int value;
        try {
            value = Integer.parseInt(rateLimitInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Enter a whole number", Toast.LENGTH_SHORT).show();
            return;
        }
        ContentStore.setApiRateLimitPerMinute(this, value);
        Toast.makeText(this, "Rate limit saved", Toast.LENGTH_SHORT).show();
        loadDataPlatformSettings();
    }

    private void refreshCacheSize() {
        long bytes = folderSize(ContentStore.getViewCacheDir(this));
        cacheSizeText.setText(ServerStats.formatBytes(bytes) + " of temporary preview files");
    }

    private long folderSize(File dir) {
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            total += f.isDirectory() ? folderSize(f) : f.length();
        }
        return total;
    }

    private void confirmClearCache() {
        new AlertDialog.Builder(this)
                .setTitle("Clear cache?")
                .setMessage("Removes temporary copies used for the in-app viewers. Nothing on your device storage is affected.")
                .setPositiveButton("Clear", (d, w) -> {
                    deleteRecursive(ContentStore.getViewCacheDir(this));
                    Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show();
                    refreshCacheSize();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmResetEndpoints() {
        new AlertDialog.Builder(this)
                .setTitle("Reset custom API endpoints?")
                .setMessage("This removes every endpoint you've defined in the API Editor. This can't be undone.")
                .setPositiveButton("Reset", (d, w) -> {
                    ApiEndpointStore.clearAll(this);
                    Toast.makeText(this, "Custom endpoints cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteRecursive(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }
}
