package com.yadaventerprise.phoneserver;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ApiAdapter extends RecyclerView.Adapter<ApiAdapter.ViewHolder> {

    public interface Listener {
        void onOpen(ApiEndpointConfig endpoint);
        void onDelete(ApiEndpointConfig endpoint);
    }

    private final List<ApiEndpointConfig> endpoints;
    private final Listener listener;

    public ApiAdapter(List<ApiEndpointConfig> endpoints, Listener listener) {
        this.endpoints = endpoints;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_api, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ApiEndpointConfig endpoint = endpoints.get(position);
        holder.method.setText(endpoint.method);
        holder.path.setText(endpoint.path);
        holder.jsonPreview.setText(endpoint.body);
        holder.itemView.setOnClickListener(v -> listener.onOpen(endpoint));
        holder.delete.setOnClickListener(v -> listener.onDelete(endpoint));
    }

    @Override
    public int getItemCount() {
        return endpoints.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView method;
        TextView path;
        TextView jsonPreview;
        TextView delete;

        ViewHolder(View itemView) {
            super(itemView);
            method = itemView.findViewById(R.id.api_method);
            path = itemView.findViewById(R.id.api_path);
            jsonPreview = itemView.findViewById(R.id.api_json_preview);
            delete = itemView.findViewById(R.id.delete_button);
        }
    }
}
