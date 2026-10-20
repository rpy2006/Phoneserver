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

public class ProjectsAdapter extends RecyclerView.Adapter<ProjectsAdapter.ViewHolder> {

    public interface Listener {
        void onOpen(Records.ProjectRecord project);

        void onMenu(View anchor, Records.ProjectRecord project);
    }

    private final List<Records.ProjectRecord> projects;
    private final Listener listener;

    public ProjectsAdapter(List<Records.ProjectRecord> projects, Listener listener) {
        this.projects = projects;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_project, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Records.ProjectRecord project = projects.get(position);
        holder.name.setText(project.name);
        holder.description.setText(project.description == null || project.description.isEmpty()
                ? "No description" : project.description);

        holder.usage.setText("Updated " + ProjectsActivity.formatTime(project.updatedAt));

        holder.itemView.setOnClickListener(v -> listener.onOpen(project));
        holder.menu.setOnClickListener(v -> listener.onMenu(v, project));
    }

    @Override
    public int getItemCount() {
        return projects.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView description;
        final TextView usage;
        final ImageView menu;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.project_name);
            description = itemView.findViewById(R.id.project_description);
            usage = itemView.findViewById(R.id.project_usage);
            menu = itemView.findViewById(R.id.project_menu);
        }
    }
}
