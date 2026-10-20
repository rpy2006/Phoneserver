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

public class ProjectBackupsAdapter extends RecyclerView.Adapter<ProjectBackupsAdapter.ViewHolder> {

    public interface Listener {
        void onMenu(View anchor, Records.BackupRecord backup);
    }

    private final List<Records.BackupRecord> backups;
    private final Listener listener;

    public ProjectBackupsAdapter(List<Records.BackupRecord> backups, Listener listener) {
        this.backups = backups;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_backup, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Records.BackupRecord backup = backups.get(position);
        holder.name.setText(backup.fileName);
        StringBuilder meta = new StringBuilder();
        meta.append(ServerStats.formatBytes(backup.sizeBytes))
                .append(" · ").append(backup.includesFiles ? "with files" : "databases only")
                .append(" · ").append(backup.valid ? "valid" : "unverified")
                .append('\n').append("Created ").append(ProjectsActivity.formatTime(backup.createdAt));
        if (backup.note != null && !backup.note.isEmpty()) {
            meta.append('\n').append(backup.note);
        }
        holder.meta.setText(meta.toString());
        holder.menu.setOnClickListener(v -> listener.onMenu(v, backup));
    }

    @Override
    public int getItemCount() {
        return backups.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView meta;
        final ImageView menu;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.backup_name);
            meta = itemView.findViewById(R.id.backup_meta);
            menu = itemView.findViewById(R.id.backup_menu);
        }
    }
}
