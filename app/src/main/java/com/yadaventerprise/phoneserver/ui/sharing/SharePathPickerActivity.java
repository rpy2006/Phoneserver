package com.yadaventerprise.phoneserver.ui.sharing;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.sharing.ShareLink;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.security.PathGuard;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class SharePathPickerActivity extends AppCompatActivity {

    public static final String EXTRA_TYPE = "picker_type";
    public static final String RESULT_PATH = "picker_result_path";
    public static final String RESULT_TYPE = "picker_result_type";

    private final List<Entry> entries = new ArrayList<>();
    private EntryAdapter adapter;
    private TextView title;
    private TextView hint;
    private TextView currentPath;
    private TextView empty;
    private View storageNotice;
    private View grantAccessButton;
    private View upButton;
    private View useFolderButton;
    private String type = ShareLink.TYPE_FILE;
    private String relative = "";
    private String loadedRoot = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share_path_picker);
        type = getIntent().getStringExtra(EXTRA_TYPE);
        if (!ShareLink.TYPE_FOLDER.equals(type)) type = ShareLink.TYPE_FILE;

        title = findViewById(R.id.screen_title);
        hint = findViewById(R.id.picker_hint);
        currentPath = findViewById(R.id.current_path);
        empty = findViewById(R.id.path_empty);
        storageNotice = findViewById(R.id.storage_notice);
        grantAccessButton = findViewById(R.id.grant_access_button);
        upButton = findViewById(R.id.up_button);
        useFolderButton = findViewById(R.id.use_folder_button);
        RecyclerView recycler = findViewById(R.id.path_recycler);
        adapter = new EntryAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        title.setText(ShareLink.TYPE_FOLDER.equals(type) ? "Choose folder" : "Choose file");
        hint.setText(ShareLink.TYPE_FOLDER.equals(type)
                ? "Open a folder, then use the folder itself."
                : "Tap a file to share it. Open folders to look inside.");
        useFolderButton.setVisibility(ShareLink.TYPE_FOLDER.equals(type)
                ? View.VISIBLE : View.GONE);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        upButton.setOnClickListener(v -> navigateUp());
        useFolderButton.setOnClickListener(v -> chooseCurrentFolder());
        grantAccessButton.setOnClickListener(v ->
                ContentStore.requestFullStorageAccess(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load(relative);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 200) load(relative);
    }

    private void load(String newRelative) {
        File root = ContentStore.getPublicFilesDir(this);
        String rootPath = root.getAbsolutePath();
        if (!loadedRoot.isEmpty() && !loadedRoot.equals(rootPath)) {
            newRelative = "";
        }
        loadedRoot = rootPath;

        String normalized = PathGuard.normalizeRelative(newRelative);
        if (normalized == null) normalized = "";
        File dir = PathGuard.safeResolve(root, normalized);
        if (dir == null || !dir.isDirectory()) {
            if (!normalized.isEmpty()) {
                load("");
                return;
            }
            entries.clear();
            relative = "";
            adapter.notifyDataSetChanged();
            currentPath.setText("/");
            empty.setText("This folder is not available.");
            empty.setVisibility(View.VISIBLE);
            updateStorageNotice();
            return;
        }
        relative = normalized;
        currentPath.setText(relative.isEmpty() ? "/" : "/" + relative);
        upButton.setEnabled(!relative.isEmpty());
        upButton.setAlpha(relative.isEmpty() ? 0.4f : 1f);
        useFolderButton.setEnabled(!relative.isEmpty());
        useFolderButton.setAlpha(relative.isEmpty() ? 0.4f : 1f);

        entries.clear();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file == null || PathGuard.isHidden(file.getName())) continue;
                if (file.isDirectory()) {
                    entries.add(new Entry(file, join(relative, file.getName())));
                } else if (ShareLink.TYPE_FILE.equals(type) && file.isFile()) {
                    entries.add(new Entry(file, join(relative, file.getName())));
                }
            }
        }
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.file.isDirectory() != b.file.isDirectory()) {
                    return a.file.isDirectory() ? -1 : 1;
                }
                return a.file.getName().compareToIgnoreCase(b.file.getName());
            }
        });
        adapter.notifyDataSetChanged();
        if (entries.isEmpty()) {
            empty.setText(ContentStore.hasFullStorageAccess(this)
                    ? (ShareLink.TYPE_FOLDER.equals(type)
                    ? "No folders here." : "No files or folders here.")
                    : "No files in the app folder. Grant all-files access to browse Pictures, "
                    + "Download, DCIM and other device folders.");
        }
        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        updateStorageNotice();
    }

    private void updateStorageNotice() {
        storageNotice.setVisibility(ContentStore.hasFullStorageAccess(this)
                ? View.GONE : View.VISIBLE);
    }

    private void navigateUp() {
        if (relative.isEmpty()) return;
        int slash = relative.lastIndexOf('/');
        load(slash < 0 ? "" : relative.substring(0, slash));
    }

    private void open(Entry entry) {
        if (entry.file.isDirectory()) {
            load(entry.relative);
            return;
        }
        choose(entry.relative, ShareLink.TYPE_FILE);
    }

    private void chooseCurrentFolder() {
        if (ShareLink.TYPE_FOLDER.equals(type) && !relative.isEmpty()) {
            choose(relative, ShareLink.TYPE_FOLDER);
        }
    }

    private void choose(String path, String chosenType) {
        Intent result = new Intent();
        result.putExtra(RESULT_PATH, path);
        result.putExtra(RESULT_TYPE, chosenType);
        setResult(RESULT_OK, result);
        finish();
    }

    private String join(String parent, String name) {
        return parent.isEmpty() ? name : parent + "/" + name;
    }

    private class EntryAdapter extends RecyclerView.Adapter<EntryHolder> {

        @NonNull
        @Override
        public EntryHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_share_path, parent, false);
            return new EntryHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull EntryHolder holder, int position) {
            Entry entry = entries.get(position);
            holder.name.setText(entry.file.getName());
            holder.icon.setImageResource(entry.file.isDirectory()
                    ? R.drawable.ic_folder : R.drawable.ic_description);
            holder.chevron.setVisibility(entry.file.isDirectory() ? View.VISIBLE : View.INVISIBLE);
            holder.meta.setText(entry.file.isDirectory()
                    ? folderMeta(entry.file) : fileMeta(entry.file));
            holder.itemView.setOnClickListener(v -> open(entry));
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }

    private static class EntryHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView meta;
        final ImageView icon;
        final ImageView chevron;

        EntryHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.path_name);
            meta = itemView.findViewById(R.id.path_meta);
            icon = itemView.findViewById(R.id.path_icon);
            chevron = itemView.findViewById(R.id.path_chevron);
        }
    }

    private String folderMeta(File folder) {
        File[] children = folder.listFiles();
        int count = children == null ? 0 : children.length;
        return count == 0 ? "Empty folder"
                : count + (count == 1 ? " item" : " items");
    }

    private String fileMeta(File file) {
        return android.text.format.Formatter.formatShortFileSize(this, file.length());
    }

    private static class Entry {
        final File file;
        final String relative;

        Entry(File file, String relative) {
            this.file = file;
            this.relative = relative;
        }
    }
}
