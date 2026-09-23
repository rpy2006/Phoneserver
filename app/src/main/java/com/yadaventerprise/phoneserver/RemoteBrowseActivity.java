package com.yadaventerprise.phoneserver;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RemoteBrowseActivity extends AppCompatActivity {

    private ServerProfile profile;
    private String currentPath = "";

    private TextView titleText, pathText, emptyText, statusText;
    private RecyclerView recyclerView;
    private RemoteEntryAdapter adapter;
    private final List<RemoteEntry> entries = new ArrayList<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> uploadPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) uploadFile(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote_browse);

        String profileId = getIntent().getStringExtra("profile_id");
        currentPath = getIntent().getStringExtra("path");
        if (currentPath == null) currentPath = "";

        profile = findProfile(profileId);
        if (profile == null) {
            Toast.makeText(this, "Server not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        titleText = findViewById(R.id.server_title);
        pathText = findViewById(R.id.current_path);
        emptyText = findViewById(R.id.empty_text);
        statusText = findViewById(R.id.status_text);
        recyclerView = findViewById(R.id.entries_recycler);

        titleText.setText(profile.name);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RemoteEntryAdapter(entries, new RemoteEntryAdapter.Listener() {
            @Override
            public void onOpen(RemoteEntry entry) {
                if (entry.isDir) {
                    navigateInto(entry.name);
                } else {
                    showFileMenu(entry, null);
                }
            }

            @Override
            public void onMenu(RemoteEntry entry, View anchor) {
                showFileMenu(entry, anchor);
            }
        });
        recyclerView.setAdapter(adapter);

        findViewById(R.id.up_button).setOnClickListener(v -> navigateUp());
        findViewById(R.id.add_button).setOnClickListener(v -> showAddMenu());

        loadFolder();
    }

    private ServerProfile findProfile(String id) {
        for (ServerProfile p : ServerProfileStore.getAll(this)) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    private void navigateInto(String folderName) {
        String newPath = currentPath.isEmpty() ? folderName : currentPath + "/" + folderName;
        Intent intent = new Intent(this, RemoteBrowseActivity.class);
        intent.putExtra("profile_id", profile.id);
        intent.putExtra("path", newPath);
        startActivity(intent);
    }

    private void navigateUp() {
        if (currentPath.isEmpty()) {
            finish();
            return;
        }
        int lastSlash = currentPath.lastIndexOf('/');
        currentPath = lastSlash >= 0 ? currentPath.substring(0, lastSlash) : "";
        loadFolder();
    }

    private void loadFolder() {
        pathText.setText("/" + currentPath);
        setStatus("Loading...");
        String pathSnapshot = currentPath;
        new Thread(() -> {
            try {
                List<RemoteEntry> result = RemoteApiClient.browse(profile, pathSnapshot);
                mainHandler.post(() -> {
                    entries.clear();
                    entries.addAll(result);
                    adapter.notifyDataSetChanged();
                    emptyText.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                    setStatus(null);
                });
            } catch (RemoteApiClient.ApiException e) {
                mainHandler.post(() -> setStatus(e.getMessage()));
            }
        }).start();
    }

    private void setStatus(String message) {
        if (message == null) {
            statusText.setVisibility(View.GONE);
        } else {
            statusText.setText(message);
            statusText.setVisibility(View.VISIBLE);
        }
    }

    private void showAddMenu() {
        PopupMenu menu = new PopupMenu(this, findViewById(R.id.add_button));
        menu.getMenu().add("New Folder");
        menu.getMenu().add("Upload File");
        menu.setOnMenuItemClickListener(item -> {
            if ("New Folder".equals(item.getTitle())) {
                showNewFolderDialog();
            } else {
                uploadPicker.launch(new String[]{"*/*"});
            }
            return true;
        });
        menu.show();
    }

    private void showNewFolderDialog() {
        EditText input = new EditText(this);
        input.setHint("Folder name");
        new AlertDialog.Builder(this)
                .setTitle("New Folder")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    createFolder(name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createFolder(String name) {
        setStatus("Creating folder...");
        new Thread(() -> {
            try {
                RemoteApiClient.mkdir(profile, currentPath, name);
                mainHandler.post(this::loadFolder);
            } catch (RemoteApiClient.ApiException e) {
                mainHandler.post(() -> {
                    setStatus(null);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void uploadFile(Uri uri) {
        String filename = queryFileName(uri);
        if (filename == null) filename = "file_" + System.currentTimeMillis();
        String finalFilename = filename;
        setStatus("Uploading " + finalFilename + "...");
        new Thread(() -> {
            try {
                RemoteApiClient.upload(profile, currentPath, getContentResolver(), uri, finalFilename);
                mainHandler.post(this::loadFolder);
            } catch (RemoteApiClient.ApiException e) {
                mainHandler.post(() -> {
                    setStatus(null);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private String queryFileName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cursor.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void showFileMenu(RemoteEntry entry, View anchor) {
        String[] options = entry.isDir
                ? new String[]{"Open", "Rename", "Delete"}
                : new String[]{"View", "Download", "Rename", "Delete"};

        new AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setItems(options, (dialog, which) -> {
                    String choice = options[which];
                    switch (choice) {
                        case "Open":
                            navigateInto(entry.name);
                            break;
                        case "View":
                            viewFile(entry);
                            break;
                        case "Download":
                            downloadFile(entry);
                            break;
                        case "Rename":
                            showRenameDialog(entry);
                            break;
                        case "Delete":
                            confirmDelete(entry);
                            break;
                    }
                })
                .show();
    }

    private String entryPath(RemoteEntry entry) {
        return currentPath.isEmpty() ? entry.name : currentPath + "/" + entry.name;
    }

    private void viewFile(RemoteEntry entry) {
        setStatus("Opening " + entry.name + "...");
        File cacheDir = ContentStore.getViewCacheDir(this);
        File dest = new File(cacheDir, entry.name);
        String path = entryPath(entry);
        new Thread(() -> {
            try {
                RemoteApiClient.download(profile, path, dest);
                mainHandler.post(() -> {
                    setStatus(null);
                    openFile(dest);
                });
            } catch (RemoteApiClient.ApiException e) {
                mainHandler.post(() -> {
                    setStatus(null);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** Opens a downloaded file in the right built-in viewer based on its type; falls back to a system chooser otherwise. */
    private void openFile(File file) {
        FileTypeUtils.Kind kind = FileTypeUtils.classify(file.getName());
        switch (kind) {
            case IMAGE:
                startActivity(new Intent(this, ImageViewerActivity.class)
                        .putExtra(ImageViewerActivity.EXTRA_PATH, file.getAbsolutePath()));
                break;
            case VIDEO:
                startActivity(new Intent(this, VideoPlayerActivity.class)
                        .putExtra(VideoPlayerActivity.EXTRA_PATH, file.getAbsolutePath()));
                break;
            case AUDIO:
                startActivity(new Intent(this, MusicPlayerActivity.class)
                        .putExtra(MusicPlayerActivity.EXTRA_PATH, file.getAbsolutePath()));
                break;
            case TEXT:
                // Read-only: this is a temp copy downloaded from the remote server, not the real file.
                startActivity(new Intent(this, TextFileViewerActivity.class)
                        .putExtra(TextFileViewerActivity.EXTRA_PATH, file.getAbsolutePath())
                        .putExtra(TextFileViewerActivity.EXTRA_EDITABLE, false));
                break;
            case PDF:
                startActivity(new Intent(this, PdfViewerActivity.class)
                        .putExtra(PdfViewerActivity.EXTRA_PATH, file.getAbsolutePath()));
                break;
            default:
                viewWithSystemApp(file);
        }
    }

    private void viewWithSystemApp(File file) {
        try {
            Uri contentUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, FileTypeUtils.guessMime(file.getName()));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open with"));
        } catch (Exception e) {
            Toast.makeText(this, e.getClass().getSimpleName() + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void downloadFile(RemoteEntry entry) {
        setStatus("Downloading " + entry.name + "...");
        File dest = new File(ContentStore.getDownloadsDir(this), entry.name);
        String path = entryPath(entry);
        new Thread(() -> {
            try {
                RemoteApiClient.download(profile, path, dest);
                ContentStore.scanFile(this, dest);
                mainHandler.post(() -> {
                    setStatus(null);
                    Toast.makeText(this, "Saved to Downloads: " + entry.name, Toast.LENGTH_LONG).show();
                });
            } catch (RemoteApiClient.ApiException e) {
                mainHandler.post(() -> {
                    setStatus(null);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void showRenameDialog(RemoteEntry entry) {
        EditText input = new EditText(this);
        input.setText(entry.name);
        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(input)
                .setPositiveButton("Rename", (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(entry.name)) return;
                    renameEntry(entry, newName);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void renameEntry(RemoteEntry entry, String newName) {
        setStatus("Renaming...");
        String path = entryPath(entry);
        new Thread(() -> {
            try {
                RemoteApiClient.rename(profile, path, newName);
                mainHandler.post(this::loadFolder);
            } catch (RemoteApiClient.ApiException e) {
                mainHandler.post(() -> {
                    setStatus(null);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void confirmDelete(RemoteEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + entry.name + "?")
                .setMessage(entry.isDir ? "This deletes the folder and everything inside it." : "This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> deleteEntry(entry))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteEntry(RemoteEntry entry) {
        setStatus("Deleting...");
        String path = entryPath(entry);
        new Thread(() -> {
            try {
                RemoteApiClient.delete(profile, path);
                mainHandler.post(this::loadFolder);
            } catch (RemoteApiClient.ApiException e) {
                mainHandler.post(() -> {
                    setStatus(null);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

}
