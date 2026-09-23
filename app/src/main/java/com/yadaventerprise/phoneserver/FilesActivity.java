package com.yadaventerprise.phoneserver;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class FilesActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView emptyText, pathText;
    private FilesAdapter adapter;
    private final List<File> fileList = new ArrayList<>();
    private String currentPath = "";

    private final ActivityResultLauncher<String[]> pickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) copyIntoPublicFolder(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_files);

        recyclerView = findViewById(R.id.files_recycler);
        emptyText = findViewById(R.id.empty_text);
        pathText = findViewById(R.id.current_path);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new FilesAdapter(fileList, new FilesAdapter.Listener() {
            @Override
            public void onOpen(File file) {
                if (file.isDirectory()) {
                    currentPath = currentPath.isEmpty() ? file.getName() : currentPath + "/" + file.getName();
                    refreshList();
                } else {
                    showFileMenu(file);
                }
            }

            @Override
            public void onDelete(File file) {
                deleteFile(file);
            }
        });
        recyclerView.setAdapter(adapter);

        findViewById(R.id.add_button).setOnClickListener(v -> showAddMenu());
        findViewById(R.id.up_button).setOnClickListener(v -> navigateUp());

        refreshList();
    }

    private void navigateUp() {
        if (currentPath.isEmpty()) {
            finish();
            return;
        }
        int lastSlash = currentPath.lastIndexOf('/');
        currentPath = lastSlash >= 0 ? currentPath.substring(0, lastSlash) : "";
        refreshList();
    }

    private void showAddMenu() {
        PopupMenu menu = new PopupMenu(this, findViewById(R.id.add_button));
        menu.getMenu().add("New Folder");
        menu.getMenu().add("Add File");
        menu.setOnMenuItemClickListener(item -> {
            if ("New Folder".equals(item.getTitle())) {
                showNewFolderDialog();
            } else {
                pickerLauncher.launch(new String[]{"*/*"});
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
                    File parent = ContentStore.resolveSafePath(this, currentPath);
                    if (parent != null) {
                        new File(parent, name).mkdirs();
                    }
                    refreshList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshList() {
        pathText.setText("Served at /files/" + currentPath);
        File dir = ContentStore.resolveSafePath(this, currentPath);
        File[] files = dir != null ? dir.listFiles() : null;
        fileList.clear();
        if (files != null) {
            List<File> sorted = new ArrayList<>(Arrays.asList(files));
            sorted.sort((a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            fileList.addAll(sorted);
        }
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(fileList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void copyIntoPublicFolder(Uri uri) {
        String name = queryFileName(uri);
        if (name == null) name = "file_" + System.currentTimeMillis();

        File dir = ContentStore.resolveSafePath(this, currentPath);
        if (dir == null) {
            Toast.makeText(this, "Invalid folder", Toast.LENGTH_SHORT).show();
            return;
        }
        File dest = new File(dir, name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IOException("Could not open file");
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            Toast.makeText(this, "Added " + name, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Failed to add file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        ContentStore.scanFile(this, dest);
        refreshList();
    }

    private String queryFileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cursor.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void showFileMenu(File file) {
        String[] options = {"View", "Rename", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(file.getName())
                .setItems(options, (dialog, which) -> {
                    switch (options[which]) {
                        case "View":
                            openFile(file);
                            break;
                        case "Rename":
                            showRenameDialog(file);
                            break;
                        case "Delete":
                            deleteFile(file);
                            break;
                    }
                })
                .show();
    }

    /** Opens a file in the right built-in viewer based on its type; falls back to a system chooser otherwise. */
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
                startActivity(new Intent(this, TextFileViewerActivity.class)
                        .putExtra(TextFileViewerActivity.EXTRA_PATH, file.getAbsolutePath())
                        .putExtra(TextFileViewerActivity.EXTRA_EDITABLE, true));
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

    private void showRenameDialog(File file) {
        EditText input = new EditText(this);
        input.setText(file.getName());
        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(input)
                .setPositiveButton("Rename", (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(file.getName())) return;
                    File renamed = new File(file.getParentFile(), newName);
                    boolean ok = file.renameTo(renamed);
                    Toast.makeText(this, ok ? "Renamed" : "Rename failed", Toast.LENGTH_SHORT).show();
                    refreshList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteFile(File file) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + file.getName() + "?")
                .setMessage(file.isDirectory() ? "This deletes the folder and everything inside it." : "This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    boolean ok = deleteRecursive(file);
                    Toast.makeText(this, ok ? "Deleted" : "Could not delete", Toast.LENGTH_SHORT).show();
                    refreshList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) return false;
                }
            }
        }
        return file.delete();
    }
}
