package com.yadaventerprise.phoneserver;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yadaventerprise.phoneserver.data.Records;

import java.util.List;

public class ProjectFilesAdapter extends RecyclerView.Adapter<ProjectFilesAdapter.ViewHolder> {

    /** Either a folder path {@link String} or a {@link Records.FileRecord}. */
    public interface Listener {
        void onOpenFolder(String path);

        void onFileMenu(View anchor, Records.FileRecord file);

        void onFolderMenu(View anchor, String path);
    }

    private final List<Object> items;
    private final Listener listener;

    public ProjectFilesAdapter(List<Object> items, Listener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_project_file, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Object item = items.get(position);
        if (item instanceof String) {
            String path = (String) item;
            String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            holder.icon.setImageResource(R.drawable.ic_folder);
            holder.name.setText(name);
            holder.meta.setText("Folder");
            holder.itemView.setOnClickListener(v -> listener.onOpenFolder(path));
            holder.menu.setOnClickListener(v -> listener.onFolderMenu(v, path));
        } else {
            Records.FileRecord file = (Records.FileRecord) item;
            holder.icon.setImageResource(R.drawable.ic_description);
            holder.name.setText(file.originalName);
            holder.meta.setText(ServerStats.formatBytes(file.sizeBytes) + " · " + file.mimeType);
            holder.itemView.setOnClickListener(v -> listener.onFileMenu(v, file));
            holder.menu.setOnClickListener(v -> listener.onFileMenu(v, file));
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView meta;
        final ImageView menu;

        ViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.item_icon);
            name = itemView.findViewById(R.id.item_name);
            meta = itemView.findViewById(R.id.item_meta);
            menu = itemView.findViewById(R.id.item_menu);
        }
    }
}
