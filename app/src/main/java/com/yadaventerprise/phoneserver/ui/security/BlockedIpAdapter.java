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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class BlockedIpAdapter extends RecyclerView.Adapter<BlockedIpAdapter.Holder> {
    public interface Listener {
        void onUnblock(IpBlockStore.Block block);
    }

    private final List<IpBlockStore.Block> blocks = new ArrayList<>();
    private final Listener listener;

    public BlockedIpAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<IpBlockStore.Block> items) {
        blocks.clear();
        if (items != null) blocks.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_blocked_ip, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        IpBlockStore.Block block = blocks.get(position);
        holder.ip.setText(block.ip);
        holder.reason.setText(block.reason.isEmpty() ? "No reason recorded" : block.reason);
        String expiry;
        if (block.isPermanent()) {
            expiry = "Permanent";
        } else {
            expiry = "Expires " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(new Date(block.expiresAt));
        }
        holder.expiry.setText(expiry + " · by " + block.blockedBy);
        holder.remove.setOnClickListener(v -> listener.onUnblock(block));
    }

    @Override
    public int getItemCount() {
        return blocks.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView ip;
        final TextView reason;
        final TextView expiry;
        final TextView remove;

        Holder(@NonNull View itemView) {
            super(itemView);
            ip = itemView.findViewById(R.id.block_ip);
            reason = itemView.findViewById(R.id.block_reason);
            expiry = itemView.findViewById(R.id.block_expiry);
            remove = itemView.findViewById(R.id.block_remove);
        }
    }
}
