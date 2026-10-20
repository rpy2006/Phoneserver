package com.yadaventerprise.phoneserver;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yadaventerprise.phoneserver.data.DataException;
import com.yadaventerprise.phoneserver.data.FileStore;
import com.yadaventerprise.phoneserver.data.Records;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class ProjectFilesActivity extends AppCompatActivity {

    public static final String EXTRA_PROJECT_ID = "project_id";

    private String projectId;
    private String currentFolder = "";

    private RecyclerView recyclerView;
    private TextView emptyText;
    private LinearLayout breadcrumbRow;
    private ProjectFilesAdapter adapter;
    private final List<Object> items = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ActivityResultLauncher<String> uploadLauncher;
    private AlertDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_project_files);

        projectId = getIntent().getStringExtra(EXTRA_PROJECT_ID);

        recyclerView = findViewById(R.id.files_recycler);
        emptyText = findViewById(R.id.empty_text);
        breadcrumbRow = findViewById(R.id.breadcrumb_row);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new ProjectFilesAdapter(items, new ProjectFilesAdapter.Listener() {
            @Override
            public void onOpenFolder(String path) {
                currentFolder = path;
                load();
            }

            @Override
            public void onFileMenu(View anchor, Records.FileRecord file) {
                showFileMenu(anchor, file);
            }

            @Override
            public void onFolderMenu(View anchor, String path) {
                showFolderMenu(anchor, path);
            }
        });
        recyclerView.setAdapter(adapter);

        uploadLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) uploadPicked(uri);
                });

        findViewById(R.id.back_button).setOnClickListener(v -> {
            if (currentFolder.isEmpty()) {
                finish();
            } else {
                currentFolder = parentOf(currentFolder);
                load();
            }
        });
        findViewById(R.id.new_folder_button).setOnClickListener(v -> showCreateFolderDialog());
        findViewById(R.id.upload_button).setOnClickListener(v -> uploadLauncher.launch("*/*"));
        findViewById(R.id.fab_upload).setOnClickListener(v -> uploadLauncher.launch("*/*"));

        if (projectId == null) {
            Toast.makeText(this, "Missing project id", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        load();
    }

    private void load() {
        new Thread(() -> {
            String error = null;
            List<Records.FileRecord> files = null;
            List<Records.FolderRecord> folders = null;
            try {
                files = FileStore.list(this, projectId, currentFolder);
                folders = FileStore.listFolders(this, projectId);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final List<Records.FileRecord> fileList = files;
            final List<Records.FolderRecord> folderList = folders;
            final String message = error;
            handler.post(() -> {
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    return;
                }
                render(fileList, folderList);
            });
        }).start();
    }

    private void render(List<Records.FileRecord> files, List<Records.FolderRecord> folders) {
        items.clear();
        String prefix = currentFolder.isEmpty() ? "" : currentFolder + "/";
        List<String> childFolders = new ArrayList<>();
        for (Records.FolderRecord folder : folders) {
            if (!folder.path.startsWith(prefix)) continue;
            String rest = folder.path.substring(prefix.length());
            if (rest.isEmpty()) continue;
            int slash = rest.indexOf('/');
            String child = prefix + (slash < 0 ? rest : rest.substring(0, slash));
            if (!childFolders.contains(child)) childFolders.add(child);
        }
        items.addAll(childFolders);
        items.addAll(files);
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        renderBreadcrumb();
    }

    private void renderBreadcrumb() {
        breadcrumbRow.removeAllViews();
        addCrumb("Root", "");
        if (!currentFolder.isEmpty()) {
            String[] parts = currentFolder.split("/");
            StringBuilder prefix = new StringBuilder();
            for (String part : parts) {
                if (prefix.length() > 0) prefix.append('/');
                prefix.append(part);
                addCrumbSeparator();
                addCrumb(part, prefix.toString());
            }
        }
    }

    private void addCrumb(String label, String path) {
        TextView crumb = new TextView(this);
        crumb.setText(label);
        boolean active = path.equals(currentFolder);
        crumb.setTextColor(getColor(active ? R.color.home_accent : R.color.home_text_secondary));
        crumb.setTextSize(13);
        crumb.setPadding(dp(6), dp(6), dp(6), dp(6));
        crumb.setOnClickListener(v -> {
            if (!path.equals(currentFolder)) {
                currentFolder = path;
                load();
            }
        });
        breadcrumbRow.addView(crumb);
    }

    private void addCrumbSeparator() {
        TextView separator = new TextView(this);
        separator.setText("/");
        separator.setTextColor(getColor(R.color.home_text_secondary));
        separator.setTextSize(13);
        separator.setPadding(dp(2), dp(6), dp(2), dp(6));
        breadcrumbRow.addView(separator);
    }

    private void showFileMenu(View anchor, Records.FileRecord file) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Share");
        menu.getMenu().add("Rename");
        menu.getMenu().add("Move");
        menu.getMenu().add("Delete");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.equals("Share")) {
                shareFile(file);
            } else if (title.equals("Rename")) {
                showRenameDialog(file);
            } else if (title.equals("Move")) {
                showMoveDialog(file);
            } else if (title.equals("Delete")) {
                confirmDeleteFile(file);
            }
            return true;
        });
        menu.show();
    }

    private void showFolderMenu(View anchor, String path) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Open");
        menu.getMenu().add("Delete");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.equals("Open")) {
                currentFolder = path;
                load();
            } else if (title.equals("Delete")) {
                confirmDeleteFolder(path);
            }
            return true;
        });
        menu.show();
    }

    private void shareFile(Records.FileRecord file) {
        new Thread(() -> {
            String error = null;
            File content = null;
            try {
                content = FileStore.contentFile(this, file);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final File result = content;
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
                    intent.setType(file.mimeType);
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent, "Share " + file.originalName));
                } catch (Exception e) {
                    Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void showRenameDialog(Records.FileRecord file) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(file.originalName);
        input.setSelection(file.originalName.length());
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        input.setPadding(pad, pad / 2, pad, 0);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Rename file")
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
            runFileOperation(() -> FileStore.rename(this, projectId, file.id, name), "Renamed");
        }));
        dialog.show();
    }

    private void showMoveDialog(Records.FileRecord file) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("e.g. documents/2024 (empty = root)");
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        input.setPadding(pad, pad / 2, pad, 0);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Move to folder")
                .setView(input)
                .setPositiveButton("Move", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String folder = input.getText().toString().trim();
            dialog.dismiss();
            runFileOperation(() -> FileStore.move(this, projectId, file.id, folder), "Moved");
        }));
        dialog.show();
    }

    private interface Operation {
        void run() throws DataException;
    }

    private void runFileOperation(Operation operation, String successMessage) {
        new Thread(() -> {
            String error = null;
            try {
                operation.run();
            } catch (DataException e) {
                error = e.getMessage();
            }
            final String message = error;
            handler.post(() -> {
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show();
                    load();
                }
            });
        }).start();
    }

    private void confirmDeleteFile(Records.FileRecord file) {
        new AlertDialog.Builder(this)
                .setTitle("Delete '" + file.originalName + "'?")
                .setMessage("This cannot be undone.")
                .setPositiveButton("Delete", (d, w) ->
                        runFileOperation(() -> FileStore.delete(this, projectId, file.id), "File deleted"))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCreateFolderDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("folder name");
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        input.setPadding(pad, pad / 2, pad, 0);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New folder")
                .setView(input)
                .setPositiveButton("Create", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Name is required", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            String path = currentFolder.isEmpty() ? name : currentFolder + "/" + name;
            runFileOperation(() -> FileStore.createFolder(this, projectId, path), "Folder created");
        }));
        dialog.show();
    }

    private void confirmDeleteFolder(String path) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        new AlertDialog.Builder(this)
                .setTitle("Delete folder '" + name + "'?")
                .setMessage("The folder must be empty.")
                .setPositiveButton("Delete", (d, w) ->
                        runFileOperation(() -> FileStore.deleteFolder(this, projectId, path), "Folder deleted"))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void uploadPicked(Uri uri) {
        String displayName = queryDisplayName(uri);
        long size = querySize(uri);
        String mime = getContentResolver().getType(uri);

        showProgress("Uploading " + displayName + "...");

        new Thread(() -> {
            String error = null;
            try {
                InputStream input = getContentResolver().openInputStream(uri);
                if (input == null) throw new DataException("could not open the selected file", 400);
                try {
                    FileStore.upload(this, projectId, currentFolder, displayName, mime, input, size, "", "");
                } finally {
                    input.close();
                }
            } catch (DataException e) {
                error = e.getMessage();
            } catch (Exception e) {
                error = "Upload failed: " + e.getMessage();
            }
            final String message = error;
            handler.post(() -> {
                dismissProgress();
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Upload complete", Toast.LENGTH_SHORT).show();
                    load();
                }
            });
        }).start();
    }

    private String queryDisplayName(Uri uri) {
        String fallback = uri.getLastPathSegment();
        if (fallback == null) fallback = "upload";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private long querySize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
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

    private String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
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
