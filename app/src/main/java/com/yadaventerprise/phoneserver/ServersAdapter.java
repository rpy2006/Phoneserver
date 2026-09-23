package com.yadaventerprise.phoneserver;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Map;

public class ServersAdapter extends RecyclerView.Adapter<ServersAdapter.ViewHolder> {

    public interface Listener {
        void onConnect(ServerProfile profile);
        void onEdit(ServerProfile profile);
        void onDelete(ServerProfile profile);
    }

    private final List<ServerProfile> profiles;
    private final Map<String, Boolean> onlineStatus; // profile.id -> true/false; missing = still checking
    private final Listener listener;

    public ServersAdapter(List<ServerProfile> profiles, Map<String, Boolean> onlineStatus, Listener listener) {
        this.profiles = profiles;
        this.onlineStatus = onlineStatus;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_server, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ServerProfile profile = profiles.get(position);
        holder.name.setText(profile.name == null || profile.name.isEmpty() ? profile.ip : profile.name);
        holder.address.setText(profile.ip + ":" + profile.port);

        Boolean online = onlineStatus.get(profile.id);
        if (online == null) {
            holder.statusDot.setBackgroundColor(holder.itemView.getContext().getColor(R.color.text_secondary));
        } else if (online) {
            holder.statusDot.setBackgroundColor(holder.itemView.getContext().getColor(R.color.online_green));
        } else {
            holder.statusDot.setBackgroundColor(holder.itemView.getContext().getColor(R.color.offline_red));
        }

        holder.itemView.setOnClickListener(v -> listener.onConnect(profile));
        holder.edit.setOnClickListener(v -> listener.onEdit(profile));
        holder.delete.setOnClickListener(v -> listener.onDelete(profile));
    }

    @Override
    public int getItemCount() {
        return profiles.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, address, edit, delete;
        View statusDot;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.server_name);
            address = itemView.findViewById(R.id.server_address);
            edit = itemView.findViewById(R.id.edit_button);
            delete = itemView.findViewById(R.id.delete_button);
            statusDot = itemView.findViewById(R.id.status_dot);
        }
    }
}