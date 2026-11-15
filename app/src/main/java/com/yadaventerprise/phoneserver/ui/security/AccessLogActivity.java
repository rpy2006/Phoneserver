package com.yadaventerprise.phoneserver.ui.security;

import androidx.appcompat.app.AppCompatActivity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.security.AccessLogEntry;
import com.yadaventerprise.phoneserver.data.security.AccessLogStore;
import com.yadaventerprise.phoneserver.data.security.IpBlockStore;

import java.util.List;

public class AccessLogActivity extends AppCompatActivity implements AccessLogAdapter.Listener {
    private static final int[] STATUS_CLASSES = {0, 2, 3, 4, 5};

    private AccessLogAdapter adapter;
    private EditText search;
    private Spinner statusFilter;
    private TextView empty;
    private int generation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_access_log);

        adapter = new AccessLogAdapter(this);
        RecyclerView recycler = findViewById(R.id.log_recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        search = findViewById(R.id.search_input);
        statusFilter = findViewById(R.id.status_filter);
        empty = findViewById(R.id.log_empty);
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.clear_button).setOnClickListener(v -> confirmClear());

        ArrayAdapter<String> filterAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"All status", "2xx", "3xx", "4xx", "5xx"});
        filterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        statusFilter.setAdapter(filterAdapter);
        statusFilter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                load();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

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
        int selected = statusFilter.getSelectedItemPosition();
        final int statusClass = selected >= 0 && selected < STATUS_CLASSES.length
                ? STATUS_CLASSES[selected] : 0;
        new Thread(() -> {
            AccessLogStore.Query logQuery = new AccessLogStore.Query();
            logQuery.search = query;
            logQuery.statusClass = statusClass;
            logQuery.limit = 500;
            List<AccessLogEntry> entries = AccessLogStore.get(getApplicationContext()).read(logQuery);
            runOnUiThread(() -> {
                if (isFinishing() || request != generation) return;
                adapter.submit(entries);
                empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Clear access log?")
                .setMessage("This removes all recorded request entries from this device.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    AccessLogStore.get(this).clear();
                    load();
                })
                .show();
    }

    @Override
    public void onBlockIp(AccessLogEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle("Block " + entry.ip + "?")
                .setMessage("Requests from this address will be rejected until you unblock it.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Block", (dialog, which) -> {
                    IpBlockStore.get(this).block(entry.ip, "Blocked from access log", 0, "user");
                    Toast.makeText(this, "Blocked " + entry.ip, Toast.LENGTH_SHORT).show();
                    load();
                })
                .show();
    }
}
