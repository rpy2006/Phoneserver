package com.yadaventerprise.phoneserver.ui.remote;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.security.PathGuard;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class RemoteFileManagerActivity extends AppCompatActivity {

    private TextView pathText;
    private TextView emptyText;
    private LinearLayout filesContainer;
    private String relative = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote_file_manager);

        pathText = findViewById(R.id.path_text);
        emptyText = findViewById(R.id.files_empty);
        filesContainer = findViewById(R.id.files_container);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.refresh_button).setOnClickListener(v -> load());
        findViewById(R.id.up_button).setOnClickListener(v -> navigateUp());
        findViewById(R.id.create_folder_button).setOnClickListener(v -> showCreateFolderDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        String normalized = PathGuard.normalizeRelative(relative);
        if (normalized == null) {
            relative = "";
            normalized = "";
            Toast.makeText(this, "Unsafe path refused", Toast.LENGTH_SHORT).show();
        }
        File dir = ContentStore.resolveSafePath(this, normalized);
        if (dir == null || !dir.isDirectory()) {
            relative = "";
            normalized = "";
            dir = ContentStore.getPublicFilesDir(this);
            Toast.makeText(this, "Folder not available", Toast.LENGTH_SHORT).show();
        }
        relative = normalized;
        pathText.setText(relative.isEmpty() ? "/" : "/" + relative);

        List<File> entries = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!ContentStore.getShowHiddenFiles(this) && ContentStore.isHiddenName(child.getName())) {
                    continue;
                }
                entries.add(child);
            }
        }
        Collections.sort(entries, Comparator.comparing((File file) -> file.isDirectory()).reversed()
                .thenComparing(file -> file.getName().toLowerCase()));

        filesContainer.removeAllViews();
        emptyText.setVisibility(entries.isEmpty()
                ? android.view.View.VISIBLE : android.view.View.GONE);
        for (File file : entries) {
            addFileCard(file);
        }
    }

    private void addFileCard(final File file) {
        final String childRelative = join(relative, file.getName());
        LinearLayout card = RemoteUi.card(this);
        boolean folder = file.isDirectory();
        String subtitle = folder ? "Folder" : android.text.format.Formatter.formatShortFileSize(this, file.length());
        subtitle += " · Modified " + RemoteUi.time(file.lastModified());
        card.addView(RemoteUi.row(this, folder ? R.drawable.ic_folder : R.drawable.ic_description,
                file.getName(), subtitle));

        LinearLayout actions = RemoteUi.actions(this);
        if (folder) {
            TextView open = RemoteUi.action(this, "Open");
            open.setOnClickListener(v -> {
                String normalized = PathGuard.normalizeRelative(childRelative);
                if (normalized == null) {
                    Toast.makeText(this, "Unsafe path refused", Toast.LENGTH_SHORT).show();
                    return;
                }
                File target = ContentStore.resolveSafePath(this, normalized);
                if (target == null || !target.isDirectory()) {
                    Toast.makeText(this, "Folder not available", Toast.LENGTH_SHORT).show();
                    return;
                }
                relative = normalized;
                load();
            });
            actions.addView(open);
        }
        TextView rename = RemoteUi.action(this, "Rename");
        addSpaced(actions, rename);
        rename.setOnClickListener(v -> showRenameDialog(file, childRelative));
        TextView delete = RemoteUi.action(this, "Delete");
        addSpaced(actions, delete);
        delete.setOnClickListener(v -> RemoteUi.confirm(this, "Delete " + file.getName() + "?",
                folder ? "The folder and everything inside it will be deleted from this device."
                        : "This file will be deleted from this device.",
                "Delete", () -> {
                    File target = safeChild(childRelative);
                    if (target == null) {
                        Toast.makeText(this, "Unsafe path refused", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!deleteRecursively(target)) {
                        Toast.makeText(this, "Could not delete " + file.getName(), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                    }
                    load();
                }));
        card.addView(actions);
        filesContainer.addView(card);
    }

    private void showCreateFolderDialog() {
        EditText input = RemoteUi.input(this, "Folder name", null, false);
        new AlertDialog.Builder(this)
                .setTitle("Create folder")
                .setView(input)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!PathGuard.isValidSegment(name)) {
                        Toast.makeText(this, "Enter a valid folder name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    File parent = resolveCurrent();
                    String childRelative = join(relative, name);
                    File target = safeChild(childRelative);
                    if (parent == null || target == null || !parent.isDirectory()) {
                        Toast.makeText(this, "Unsafe path refused", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (target.exists() || !target.mkdirs()) {
                        Toast.makeText(this, "Could not create folder", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(this, "Folder created", Toast.LENGTH_SHORT).show();
                    load();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRenameDialog(final File file, final String childRelative) {
        EditText input = RemoteUi.input(this, "New name", file.getName(), false);
        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!PathGuard.isValidSegment(name)) {
                        Toast.makeText(this, "Enter a valid name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    File target = safeChild(childRelative);
                    File renamed = safeChild(join(parentRelative(childRelative), name));
                    if (target == null || renamed == null || !target.exists()
                            || renamed.exists() || !sameParent(target, renamed)) {
                        Toast.makeText(this, "Unsafe path refused", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!target.renameTo(renamed)) {
                        Toast.makeText(this, "Could not rename", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(this, "Renamed", Toast.LENGTH_SHORT).show();
                    load();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private File resolveCurrent() {
        String normalized = PathGuard.normalizeRelative(relative);
        if (normalized == null) return null;
        return ContentStore.resolveSafePath(this, normalized);
    }

    private File safeChild(String path) {
        String normalized = PathGuard.normalizeRelative(path);
        if (normalized == null) return null;
        File file = ContentStore.resolveSafePath(this, normalized);
        if (file == null || !PathGuard.isInside(ContentStore.getPublicFilesDir(this), file)) return null;
        return file;
    }

    private boolean sameParent(File a, File b) {
        try {
            return a.getParentFile().getCanonicalPath().equals(b.getParentFile().getCanonicalPath());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }

    private void navigateUp() {
        if (relative.isEmpty()) return;
        int slash = relative.lastIndexOf('/');
        relative = slash < 0 ? "" : relative.substring(0, slash);
        load();
    }

    private void addSpaced(LinearLayout parent, TextView view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(RemoteUi.dp(this, 8));
        view.setLayoutParams(params);
        parent.addView(view);
    }

    private String join(String parent, String name) {
        return parent.isEmpty() ? name : parent + "/" + name;
    }

    private String parentRelative(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}
