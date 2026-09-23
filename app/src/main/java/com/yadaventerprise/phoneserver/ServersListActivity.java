package com.yadaventerprise.phoneserver;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ServersListActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView emptyText;
    private ServersAdapter adapter;
    private final List<ServerProfile> profileList = new ArrayList<>();
    private final Map<String, Boolean> onlineStatus = new HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_servers_list);

        recyclerView = findViewById(R.id.servers_recycler);
        emptyText = findViewById(R.id.empty_text);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new ServersAdapter(profileList, onlineStatus, new ServersAdapter.Listener() {
            @Override
            public void onConnect(ServerProfile profile) {
                Intent intent = new Intent(ServersListActivity.this, RemoteBrowseActivity.class);
                intent.putExtra("profile_id", profile.id);
                intent.putExtra("path", "");
                startActivity(intent);
            }

            @Override
            public void onEdit(ServerProfile profile) {
                Intent intent = new Intent(ServersListActivity.this, AddServerActivity.class);
                intent.putExtra(AddServerActivity.EXTRA_PROFILE_ID, profile.id);
                startActivity(intent);
            }

            @Override
            public void onDelete(ServerProfile profile) {
                new AlertDialog.Builder(ServersListActivity.this)
                        .setTitle("Remove server?")
                        .setMessage("This only removes it from your saved list.")
                        .setPositiveButton("Remove", (d, w) -> {
                            ServerProfileStore.delete(ServersListActivity.this, profile.id);
                            onlineStatus.remove(profile.id);
                            refreshList();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        recyclerView.setAdapter(adapter);

        findViewById(R.id.add_server_button).setOnClickListener(v ->
                startActivity(new Intent(this, AddServerActivity.class)));

        refreshList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private void refreshList() {
        profileList.clear();
        profileList.addAll(ServerProfileStore.getAll(this));
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(profileList.isEmpty() ? View.VISIBLE : View.GONE);
        checkAllStatuses();
    }

    /** Pings each saved server's /status endpoint in the background and updates its dot. */
    private void checkAllStatuses() {
        for (ServerProfile profile : new ArrayList<>(profileList)) {
            new Thread(() -> {
                boolean online = RemoteApiClient.isOnline(profile);
                mainHandler.post(() -> {
                    onlineStatus.put(profile.id, online);
                    int index = profileList.indexOf(profile);
                    if (index >= 0) adapter.notifyItemChanged(index);
                });
            }).start();
        }
    }
}
