package com.yadaventerprise.phoneserver;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yadaventerprise.phoneserver.data.BackupManager;
import com.yadaventerprise.phoneserver.data.DataException;
import com.yadaventerprise.phoneserver.data.Records;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ProjectBackupsActivity extends AppCompatActivity {

    private String projectId;

    private RecyclerView recyclerView;
    private TextView emptyText;
    private EditText noteInput;
    private CheckBox includeFilesCheck;
    private ProjectBackupsAdapter adapter;
    private final List<Records.BackupRecord> backups = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private AlertDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_project_backups);

        projectId = getIntent().getStringExtra(ProjectDetailActivity.EXTRA_PROJECT_ID);

        recyclerView = findViewById(R.id.backups_recycler);
        emptyText = findViewById(R.id.empty_text);
        noteInput = findViewById(R.id.note_input);
        includeFilesCheck = findViewById(R.id.include_files_check);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new ProjectBackupsAdapter(backups, this::showBackupMenu);
        recyclerView.setAdapter(adapter);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.create_backup_button).setOnClickListener(v -> createBackup());

        if (projectId == null) {
            Toast.makeText(this, "Missing project id", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (projectId != null) load();
    }

    private void load() {
        new Thread(() -> {
            String error = null;
            List<Records.BackupRecord> loaded = null;
            try {
                loaded = BackupManager.list(this, projectId);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final List<Records.BackupRecord> result = loaded;
            final String message = error;
            handler.post(() -> {
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    return;
                }
                backups.clear();
                backups.addAll(result);
                adapter.notifyDataSetChanged();
                emptyText.setVisibility(backups.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private void createBackup() {
        boolean includeFiles = includeFilesCheck.isChecked();
        String note = noteInput.getText().toString().trim();
        showProgress("Creating backup...");
        new Thread(() -> {
            String error = null;
            try {
                BackupManager.create(this, projectId, includeFiles, note);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final String message = error;
            handler.post(() -> {
                dismissProgress();
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                } else {
                    noteInput.setText("");
                    Toast.makeText(this, "Backup created", Toast.LENGTH_SHORT).show();
                    load();
                }
            });
        }).start();
    }

    private void showBackupMenu(View anchor, Records.BackupRecord backup) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Validate");
        menu.getMenu().add("Restore");
        menu.getMenu().add("Share");
        menu.getMenu().add("Delete");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.equals("Validate")) {
                validateBackup(backup);
            } else if (title.equals("Restore")) {
                confirmRestore(backup);
            } else if (title.equals("Share")) {
                shareBackup(backup);
            } else if (title.equals("Delete")) {
                confirmDelete(backup);
            }
            return true;
        });
        menu.show();
    }

    private void validateBackup(Records.BackupRecord backup) {
        showProgress("Validating backup...");
        new Thread(() -> {
            String error = null;
            Map<String, String> manifest = null;
            try {
                manifest = BackupManager.validate(this, projectId, backup.id);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final Map<String, String> result = manifest;
            final String message = error;
            handler.post(() -> {
                dismissProgress();
                if (message != null) {
                    new AlertDialog.Builder(this)
                            .setTitle("Validation failed")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show();
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, String> entry : result.entrySet()) {
                        sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Backup is valid")
                            .setMessage(sb.toString().trim())
                            .setPositiveButton("OK", null)
                            .show();
                    load();
                }
            });
        }).start();
    }

    private void confirmRestore(Records.BackupRecord backup) {
        new AlertDialog.Builder(this)
                .setTitle("Restore this backup?")
                .setMessage("This REPLACES the project's current databases"
                        + (backup.includesFiles ? " and files" : "")
                        + " with the contents of '" + backup.fileName + "'. This cannot be undone.")
                .setPositiveButton("Restore", (d, w) -> restoreBackup(backup))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void restoreBackup(Records.BackupRecord backup) {
        showProgress("Restoring backup...");
        new Thread(() -> {
            String error = null;
            try {
                BackupManager.restore(this, projectId, backup.id, true);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final String message = error;
            handler.post(() -> {
                dismissProgress();
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Backup restored", Toast.LENGTH_SHORT).show();
                    load();
                }
            });
        }).start();
    }

    private void shareBackup(Records.BackupRecord backup) {
        new Thread(() -> {
            String error = null;
            File archive = null;
            try {
                archive = BackupManager.archiveFile(this, projectId, backup.id);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final File result = archive;
            final String message = error;
            handler.post(() -> {
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    Uri uri = FileProvider.getUriForFile(this,
                            getPackageName() + ".fileprovider", result);
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("application/zip");
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent, "Share " + backup.fileName));
                } catch (Exception e) {
                    Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void confirmDelete(Records.BackupRecord backup) {
        new AlertDialog.Builder(this)
                .setTitle("Delete '" + backup.fileName + "'?")
                .setMessage("The archive is permanently removed.")
                .setPositiveButton("Delete", (d, w) -> new Thread(() -> {
                    String error = null;
                    try {
                        BackupManager.delete(this, projectId, backup.id);
                    } catch (DataException e) {
                        error = e.getMessage();
                    }
                    final String message = error;
                    handler.post(() -> {
                        if (message != null) {
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "Backup deleted", Toast.LENGTH_SHORT).show();
                            load();
                        }
                    });
                }).start())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showProgress(String message) {
        dismissProgress();
        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView text = new TextView(this);
        text.setText(message);
        layout.addView(text);
        progressDialog = new AlertDialog.Builder(this)
                .setTitle("Please wait")
                .setView(layout)
                .setCancelable(false)
                .create();
        progressDialog.show();
    }

    private void dismissProgress() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        progressDialog = null;
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value);
    }

    @Override
    protected void onDestroy() {
        dismissProgress();
        super.onDestroy();
    }
}
