package com.yadaventerprise.phoneserver.ui.security;

import java.text.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.security.IpBlockStore;
import com.yadaventerprise.phoneserver.data.security.SecurityEvent;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SecurityEventAdapter extends RecyclerView.Adapter<SecurityEventAdapter.Holder> {
    public interface Listener {
        void onBlockIp(SecurityEvent event);
    }

    private final List<SecurityEvent> events = new ArrayList<>();
    private final Listener listener;

    public SecurityEventAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<SecurityEvent> items) {
        events.clear();
        if (items != null) events.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_security_event, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        SecurityEvent event = events.get(position);
        holder.severity.setText(event.severity.toUpperCase(Locale.US));
        holder.severity.setTextColor(colorFor(holder.itemView, event.severity));
        holder.type.setText(label(event.type));
        holder.time.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(new Date(event.time)));
        holder.path.setText(event.method + " " + event.path);

        StringBuilder detail = new StringBuilder(event.ip);
        if (event.detail != null && !event.detail.isEmpty()) {
            detail.append(" · ").append(event.detail);
        }
        if (event.autoBlocked) detail.append(" · IP auto-blocked");
        holder.detail.setText(detail.toString());

        boolean canBlock = event.ip != null && !event.ip.isEmpty()
                && !IpBlockStore.isLoopback(event.ip);
        holder.block.setVisibility(canBlock ? View.VISIBLE : View.GONE);
        holder.block.setOnClickListener(v -> listener.onBlockIp(event));
    }

    @Override
    public int getItemCount() {
        return events.size();
    }

    private static String label(String type) {
        switch (type) {
            case SecurityEvent.TYPE_AUTH_FAILURE: return "Authentication failure";
            case SecurityEvent.TYPE_SUSPICIOUS_PATH: return "Suspicious path";
            case SecurityEvent.TYPE_RATE_LIMIT: return "Rate limit exceeded";
            case SecurityEvent.TYPE_BLOCKED_REQUEST: return "Blocked request";
            default: return type;
        }
    }

    private static int colorFor(View view, String severity) {
        int res;
        switch (severity) {
            case SecurityEvent.SEVERITY_HIGH:
                res = R.color.offline_red;
                break;
            case SecurityEvent.SEVERITY_MEDIUM:
                res = R.color.home_accent;
                break;
            default:
                res = R.color.home_text_secondary;
                break;
        }
        return view.getContext().getColor(res);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView severity;
        final TextView type;
        final TextView time;
        final TextView path;
        final TextView detail;
        final TextView block;

        Holder(@NonNull View itemView) {
            super(itemView);
            severity = itemView.findViewById(R.id.event_severity);
            type = itemView.findViewById(R.id.event_type);
            time = itemView.findViewById(R.id.event_time);
            path = itemView.findViewById(R.id.event_path);
            detail = itemView.findViewById(R.id.event_detail);
            block = itemView.findViewById(R.id.event_block);
        }
    }
}
