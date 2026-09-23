package com.yadaventerprise.phoneserver;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Locale;

public class RemoteEntryAdapter extends RecyclerView.Adapter<RemoteEntryAdapter.ViewHolder> {

    public interface Listener {
        void onOpen(RemoteEntry entry);
        void onMenu(RemoteEntry entry, View anchor);
    }

    private final List<RemoteEntry> entries;
    private final Listener listener;

    public RemoteEntryAdapter(List<RemoteEntry> entries, Listener listener) {
        this.entries = entries;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_remote_entry, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RemoteEntry entry = entries.get(position);
        holder.name.setText(entry.name);
        holder.meta.setText(entry.isDir ? "Folder" : formatSize(entry.size));
        holder.itemView.setOnClickListener(v -> listener.onOpen(entry));
        holder.menu.setOnClickListener(v -> listener.onMenu(entry, v));
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, meta, menu;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.entry_name);
            meta = itemView.findViewById(R.id.entry_meta);
            menu = itemView.findViewById(R.id.entry_menu);
        }
    }
}
