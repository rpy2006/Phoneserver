package com.yadaventerprise.phoneserver;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.yadaventerprise.phoneserver.data.DataException;
import com.yadaventerprise.phoneserver.data.ProjectStore;
import com.yadaventerprise.phoneserver.data.Records;

public class ProjectDetailActivity extends AppCompatActivity {

    public static final String EXTRA_PROJECT_ID = "project_id";

    private static final long MB = 1024L * 1024L;

    private String projectId;
    private Records.ProjectRecord project;

    private TextView titleText, detailName, detailDescription, detailId, detailCreated,
            detailUpdated, detailQuota, detailStats;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_project_detail);

        projectId = getIntent().getStringExtra(EXTRA_PROJECT_ID);

        titleText = findViewById(R.id.title_text);
        detailName = findViewById(R.id.detail_name);
        detailDescription = findViewById(R.id.detail_description);
        detailId = findViewById(R.id.detail_id);
        detailCreated = findViewById(R.id.detail_created);
        detailUpdated = findViewById(R.id.detail_updated);
        detailQuota = findViewById(R.id.detail_quota);
        detailStats = findViewById(R.id.detail_stats);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.card_databases).setOnClickListener(v -> open(SqlWorkspaceActivity.class));
        findViewById(R.id.card_files).setOnClickListener(v -> open(ProjectFilesActivity.class));
        findViewById(R.id.card_backups).setOnClickListener(v -> open(ProjectBackupsActivity.class));
        findViewById(R.id.card_settings).setOnClickListener(v -> showSettingsDialog());
        findViewById(R.id.card_api_docs).setOnClickListener(v ->
                startActivity(new Intent(this, ApiDocsActivity.class)));

        loadProject();
    }

    private void open(Class<?> activity) {
        Intent intent = new Intent(this, activity);
        intent.putExtra(EXTRA_PROJECT_ID, projectId);
        startActivity(intent);
    }

    private void loadProject() {
        if (projectId == null) {
            Toast.makeText(this, "Missing project id", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        new Thread(() -> {
            Records.ProjectRecord loaded = ProjectStore.get(this, projectId);
            ProjectStore.Stats stats = loaded == null ? null : ProjectStore.stats(this, projectId);
            handler.post(() -> bind(loaded, stats));
        }).start();
    }

    private void bind(Records.ProjectRecord loaded, ProjectStore.Stats stats) {
        if (loaded == null) {
            Toast.makeText(this, "Project not found", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        project = loaded;

        titleText.setText(loaded.name);
        detailName.setText(loaded.name);
        detailDescription.setText(loaded.description == null || loaded.description.isEmpty()
                ? "No description" : loaded.description);
        detailId.setText("id: " + loaded.id);
        detailCreated.setText("Created: " + ProjectsActivity.formatTime(loaded.createdAt));
        detailUpdated.setText("Updated: " + ProjectsActivity.formatTime(loaded.updatedAt));

        String quota = loaded.fileQuotaBytes > 0
                ? ServerStats.formatBytes(loaded.fileQuotaBytes) : "unlimited";
        String maxUpload = loaded.maxUploadBytes > 0
                ? ServerStats.formatBytes(loaded.maxUploadBytes) : "default";
        detailQuota.setText("File quota: " + quota + " · Max upload: " + maxUpload);

        if (stats != null) {
            detailStats.setText("Databases: " + stats.databaseCount
                    + "\nFiles: " + stats.fileCount + " in " + stats.folderCount + " folders"
                    + "\nFile storage: " + ServerStats.formatBytes(stats.fileBytes)
                    + "\nDatabase storage: " + ServerStats.formatBytes(stats.databaseBytes)
                    + "\nBackups: " + ServerStats.formatBytes(stats.backupBytes));
        } else {
            detailStats.setText("Usage unavailable");
        }
    }

    private void showSettingsDialog() {
        if (project == null) return;

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        form.setPadding(pad, pad / 2, pad, 0);

        EditText nameInput = addField(form, "Name", project.name, InputType.TYPE_CLASS_TEXT);
        EditText descriptionInput = addField(form, "Description",
                project.description == null ? "" : project.description, InputType.TYPE_CLASS_TEXT);
        EditText quotaInput = addField(form, "File quota in MB (0 = unlimited)",
                String.valueOf(project.fileQuotaBytes / MB), InputType.TYPE_CLASS_NUMBER);
        EditText uploadInput = addField(form, "Max upload in MB (0 = default)",
                String.valueOf(project.maxUploadBytes / MB), InputType.TYPE_CLASS_NUMBER);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Project settings")
                .setView(form)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Name is required", Toast.LENGTH_SHORT).show();
                return;
            }
            String description = descriptionInput.getText().toString().trim();
            long quota = parseMb(quotaInput.getText().toString());
            long maxUpload = parseMb(uploadInput.getText().toString());
            dialog.dismiss();
            saveSettings(name, description, quota, maxUpload);
        }));
        dialog.show();
    }

    private void saveSettings(String name, String description, long quota, long maxUpload) {
        new Thread(() -> {
            String error = null;
            try {
                ProjectStore.update(this, projectId, name, description, quota, maxUpload);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final String message = error;
            handler.post(() -> {
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Project updated", Toast.LENGTH_SHORT).show();
                    loadProject();
                }
            });
        }).start();
    }

    private EditText addField(LinearLayout parent, String hint, String value, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setSingleLine(true);
        field.setText(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = (int) (getResources().getDisplayMetrics().density * 6);
        field.setLayoutParams(params);
        parent.addView(field);
        return field;
    }

    private long parseMb(String value) {
        try {
            long mb = Long.parseLong(value.trim());
            return mb <= 0 ? 0 : mb * MB;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProject();
    }
}
