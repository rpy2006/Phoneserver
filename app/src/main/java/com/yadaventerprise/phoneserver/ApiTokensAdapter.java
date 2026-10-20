package com.yadaventerprise.phoneserver;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yadaventerprise.phoneserver.data.Records;

import java.util.List;

public class ApiTokensAdapter extends RecyclerView.Adapter<ApiTokensAdapter.ViewHolder> {

    public interface Listener {
        void onRevoke(Records.ApiTokenRecord token);
    }

    private final List<Records.ApiTokenRecord> tokens;
    private final Listener listener;

    public ApiTokensAdapter(List<Records.ApiTokenRecord> tokens, Listener listener) {
        this.tokens = tokens;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_token, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Records.ApiTokenRecord token = tokens.get(position);
        holder.name.setText(token.name);

        String scope = token.projectId == null ? "All projects" : "Project " + token.projectId;
        String rights;
        if (token.revoked) {
            rights = "Revoked";
        } else {
            rights = token.canWrite ? "Read & write" : "Read only";
        }

        StringBuilder meta = new StringBuilder();
        meta.append(scope).append(" · ").append(rights);
        meta.append('\n').append("Created ").append(ProjectsActivity.formatTime(token.createdAt));
        if (token.lastUsedAt > 0) {
            meta.append(" · Last used ").append(ProjectsActivity.formatTime(token.lastUsedAt));
        }
        holder.meta.setText(meta.toString());

        if (token.revoked) {
            holder.revoke.setVisibility(View.GONE);
        } else {
            holder.revoke.setVisibility(View.VISIBLE);
            holder.revoke.setOnClickListener(v -> listener.onRevoke(token));
        }
    }

    @Override
    public int getItemCount() {
        return tokens.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView meta;
        final TextView revoke;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.token_name);
            meta = itemView.findViewById(R.id.token_meta);
            revoke = itemView.findViewById(R.id.token_revoke);
        }
    }
}
