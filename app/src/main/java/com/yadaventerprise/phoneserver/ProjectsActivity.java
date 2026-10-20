package com.yadaventerprise.phoneserver;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yadaventerprise.phoneserver.data.DataException;
import com.yadaventerprise.phoneserver.data.ProjectStore;
import com.yadaventerprise.phoneserver.data.Records;

import java.util.ArrayList;
import java.util.List;

public class ProjectsActivity extends AppCompatActivity {

    private static final long MB = 1024L * 1024L;

    private RecyclerView recyclerView;
    private TextView statsText;
    private TextView emptyText;
    private ProjectsAdapter adapter;
    private final List<Records.ProjectRecord> projects = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_projects);

        recyclerView = findViewById(R.id.projects_recycler);
        statsText = findViewById(R.id.stats_text);
        emptyText = findViewById(R.id.empty_text);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new ProjectsAdapter(projects, new ProjectsAdapter.Listener() {
            @Override
            public void onOpen(Records.ProjectRecord project) {
                openProject(project);
            }

            @Override
            public void onMenu(View anchor, Records.ProjectRecord project) {
                showProjectMenu(anchor, project);
            }
        });
        recyclerView.setAdapter(adapter);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.fab_add).setOnClickListener(v -> showCreateDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProjects();
    }

    private void openProject(Records.ProjectRecord project) {
        Intent intent = new Intent(this, ProjectDetailActivity.class);
        intent.putExtra(ProjectDetailActivity.EXTRA_PROJECT_ID, project.id);
        startActivity(intent);
    }

    private void loadProjects() {
        new Thread(() -> {
            List<Records.ProjectRecord> loaded = ProjectStore.list(this);
            ProjectStore.GlobalStats stats = ProjectStore.globalStats(this);
            handler.post(() -> {
                projects.clear();
                projects.addAll(loaded);
                adapter.notifyDataSetChanged();
                emptyText.setVisibility(projects.isEmpty() ? View.VISIBLE : View.GONE);
                statsText.setText(String.format(java.util.Locale.US,
                        "%d projects · %d databases · %d files · %s used",
                        stats.projectCount, stats.databaseCount, stats.fileCount,
                        ServerStats.formatBytes(stats.fileBytes + stats.databaseBytes)));
            });
        }).start();
    }

    private void showProjectMenu(View anchor, Records.ProjectRecord project) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Open");
        menu.getMenu().add("Rename");
        menu.getMenu().add("Delete");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.equals("Open")) {
                openProject(project);
            } else if (title.equals("Rename")) {
                showRenameDialog(project);
            } else if (title.equals("Delete")) {
                showDeleteDialog(project);
            }
            return true;
        });
        menu.show();
    }

    private void showCreateDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        form.setPadding(pad, pad / 2, pad, 0);

        EditText nameInput = addField(form, "Name (required)", InputType.TYPE_CLASS_TEXT);
        EditText descriptionInput = addField(form, "Description", InputType.TYPE_CLASS_TEXT);
        EditText quotaInput = addField(form, "File quota in MB (0 = unlimited)", InputType.TYPE_CLASS_NUMBER);
        EditText uploadInput = addField(form, "Max upload in MB (0 = default)", InputType.TYPE_CLASS_NUMBER);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New project")
                .setView(form)
                .setPositiveButton("Create", null)
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
            createProject(name, description, quota, maxUpload);
        }));
        dialog.show();
    }

    private void createProject(String name, String description, long quota, long maxUpload) {
        new Thread(() -> {
            String error = null;
            try {
                ProjectStore.create(this, name, description, quota, maxUpload);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final String message = error;
            handler.post(() -> {
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Project created", Toast.LENGTH_SHORT).show();
                    loadProjects();
                }
            });
        }).start();
    }

    private void showRenameDialog(Records.ProjectRecord project) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(project.name);
        input.setSelection(project.name.length());
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        input.setPadding(pad, pad / 2, pad, 0);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Rename project")
                .setView(input)
                .setPositiveButton("Rename", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Name is required", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            renameProject(project, name);
        }));
        dialog.show();
    }

    private void renameProject(Records.ProjectRecord project, String name) {
        new Thread(() -> {
            String error = null;
            try {
                ProjectStore.update(this, project.id, name, null, null, null);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final String message = error;
            handler.post(() -> {
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Project renamed", Toast.LENGTH_SHORT).show();
                    loadProjects();
                }
            });
        }).start();
    }

    private void showDeleteDialog(Records.ProjectRecord project) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(project.name);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        input.setPadding(pad, pad / 2, pad, 0);

        new AlertDialog.Builder(this)
                .setTitle("Delete '" + project.name + "'?")
                .setMessage("This permanently deletes all databases, files and backups. "
                        + "Type the exact project name to confirm.")
                .setView(input)
                .setPositiveButton("Delete", (d, w) -> {
                    String typed = input.getText().toString().trim();
                    if (!typed.equals(project.name)) {
                        Toast.makeText(this, "Name does not match - nothing deleted", Toast.LENGTH_LONG).show();
                        return;
                    }
                    deleteProject(project, typed);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteProject(Records.ProjectRecord project, String confirmName) {
        new Thread(() -> {
            String error = null;
            try {
                ProjectStore.delete(this, project.id, confirmName);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final String message = error;
            handler.post(() -> {
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Project deleted", Toast.LENGTH_SHORT).show();
                    loadProjects();
                }
            });
        }).start();
    }

    private EditText addField(LinearLayout parent, String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setSingleLine(true);
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

    static String formatTime(long millis) {
        if (millis <= 0) return "-";
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(new java.util.Date(millis));
    }

    static Context appContext(Context context) {
        return context.getApplicationContext();
    }
}
