package com.yadaventerprise.phoneserver;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class ApiEditorActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView emptyText;
    private ApiAdapter adapter;
    private final List<ApiEndpointConfig> endpointList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_api_editor);

        recyclerView = findViewById(R.id.api_recycler);
        emptyText = findViewById(R.id.empty_text);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new ApiAdapter(endpointList, new ApiAdapter.Listener() {
            @Override
            public void onOpen(ApiEndpointConfig endpoint) {
                Intent intent = new Intent(ApiEditorActivity.this, ApiEndpointEditorActivity.class);
                intent.putExtra(ApiEndpointEditorActivity.EXTRA_EDIT_PATH, endpoint.path);
                intent.putExtra(ApiEndpointEditorActivity.EXTRA_EDIT_METHOD, endpoint.method);
                startActivity(intent);
            }

            @Override
            public void onDelete(ApiEndpointConfig endpoint) {
                new AlertDialog.Builder(ApiEditorActivity.this)
                        .setTitle("Delete endpoint?")
                        .setMessage(endpoint.method + " " + endpoint.path)
                        .setPositiveButton("Delete", (d, w) -> {
                            ApiEndpointStore.delete(ApiEditorActivity.this, endpoint.path, endpoint.method);
                            Toast.makeText(ApiEditorActivity.this, "Deleted", Toast.LENGTH_SHORT).show();
                            refreshList();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        recyclerView.setAdapter(adapter);

        findViewById(R.id.add_api_button).setOnClickListener(v ->
                startActivity(new Intent(this, ApiEndpointEditorActivity.class)));

        refreshList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private void refreshList() {
        endpointList.clear();
        endpointList.addAll(ApiEndpointStore.getAll(this));
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(endpointList.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
