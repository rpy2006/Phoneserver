package com.yadaventerprise.phoneserver.ui.security;

import java.text.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.security.AccessLogEntry;
import com.yadaventerprise.phoneserver.data.security.IpBlockStore;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class AccessLogAdapter extends RecyclerView.Adapter<AccessLogAdapter.Holder> {
    public interface Listener {
        void onBlockIp(AccessLogEntry entry);
    }

    private final List<AccessLogEntry> entries = new ArrayList<>();
    private final Listener listener;

    public AccessLogAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<AccessLogEntry> items) {
        entries.clear();
        if (items != null) entries.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_access_log, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        AccessLogEntry entry = entries.get(position);
        holder.status.setText(String.valueOf(entry.status));
        holder.status.setTextColor(statusColor(holder.itemView, entry.status));
        holder.method.setText(entry.method);
        holder.time.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(new Date(entry.time)));
        holder.path.setText(entry.path);

        StringBuilder meta = new StringBuilder();
        meta.append(entry.ip);
        meta.append(" · ").append(entry.actor);
        if (entry.tokenId != null && !entry.tokenId.isEmpty()) {
            meta.append(" · token ").append(entry.tokenId);
        }
        meta.append(" · ").append(entry.durationMs).append(" ms");
        if (entry.blocked) meta.append(" · blocked");
        holder.meta.setText(meta.toString());
        holder.userAgent.setText(entry.userAgent);
        holder.userAgent.setVisibility(
                entry.userAgent == null || entry.userAgent.isEmpty() ? View.GONE : View.VISIBLE);
        holder.block.setVisibility(canBlock(entry) ? View.VISIBLE : View.GONE);
        holder.block.setOnClickListener(v -> listener.onBlockIp(entry));
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    private static boolean canBlock(AccessLogEntry entry) {
        return entry.ip != null && !entry.ip.isEmpty()
                && !IpBlockStore.isLoopback(entry.ip);
    }

    private static int statusColor(View view, int status) {
        int res;
        if (status >= 200 && status < 300) {
            res = R.color.online_green;
        } else if (status >= 400) {
            res = R.color.offline_red;
        } else {
            res = R.color.home_text_secondary;
        }
        return view.getContext().getColor(res);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView status;
        final TextView method;
        final TextView time;
        final TextView path;
        final TextView meta;
        final TextView userAgent;
        final TextView block;

        Holder(@NonNull View itemView) {
            super(itemView);
            status = itemView.findViewById(R.id.log_status);
            method = itemView.findViewById(R.id.log_method);
            time = itemView.findViewById(R.id.log_time);
            path = itemView.findViewById(R.id.log_path);
            meta = itemView.findViewById(R.id.log_meta);
            userAgent = itemView.findViewById(R.id.log_user_agent);
            block = itemView.findViewById(R.id.log_block);
        }
    }
}
