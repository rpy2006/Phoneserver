package com.yadaventerprise.phoneserver.ui.security;

import androidx.appcompat.app.AppCompatActivity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.security.IpBlockStore;
import com.yadaventerprise.phoneserver.data.security.SecurityEvent;
import com.yadaventerprise.phoneserver.data.security.SecurityEventStore;

import java.util.List;

public class SecurityEventsActivity extends AppCompatActivity implements SecurityEventAdapter.Listener {
    private SecurityEventAdapter adapter;
    private EditText search;
    private TextView empty;
    private int generation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_security_events);

        adapter = new SecurityEventAdapter(this);
        RecyclerView recycler = findViewById(R.id.events_recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        search = findViewById(R.id.search_input);
        empty = findViewById(R.id.events_empty);
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.clear_button).setOnClickListener(v -> confirmClear());
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                load();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        final int request = ++generation;
        final String query = search.getText().toString();
        new Thread(() -> {
            SecurityEventStore.Query eventQuery = new SecurityEventStore.Query();
            eventQuery.search = query;
            eventQuery.limit = 500;
            List<SecurityEvent> events = SecurityEventStore.get(getApplicationContext()).read(eventQuery);
            runOnUiThread(() -> {
                if (isFinishing() || request != generation) return;
                adapter.submit(events);
                empty.setVisibility(events.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Clear security events?")
                .setMessage("This removes the recorded intrusion history from this device.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    SecurityEventStore.get(this).clear();
                    load();
                })
                .show();
    }

    @Override
    public void onBlockIp(SecurityEvent event) {
        new AlertDialog.Builder(this)
                .setTitle("Block " + event.ip + "?")
                .setMessage("Requests from this address will be rejected until you unblock it.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Block", (dialog, which) -> {
                    IpBlockStore.get(this).block(event.ip, "Blocked from security events", 0, "user");
                    Toast.makeText(this, "Blocked " + event.ip, Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}
