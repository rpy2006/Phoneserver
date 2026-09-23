package com.yadaventerprise.phoneserver;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;
import java.util.Locale;

public class FilesAdapter extends RecyclerView.Adapter<FilesAdapter.ViewHolder> {

    public interface Listener {
        void onOpen(File file);
        void onDelete(File file);
    }

    private final List<File> files;
    private final Listener listener;

    public FilesAdapter(List<File> files, Listener listener) {
        this.files = files;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File file = files.get(position);
        holder.name.setText(file.getName());
        holder.meta.setText(file.isDirectory() ? "Folder" : formatSize(file.length()));
        holder.itemView.setOnClickListener(v -> listener.onOpen(file));
        holder.delete.setOnClickListener(v -> listener.onDelete(file));
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, meta, delete;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.file_name);
            meta = itemView.findViewById(R.id.file_meta);
            delete = itemView.findViewById(R.id.delete_button);
        }
    }
}
