package com.yadaventerprise.phoneserver.ui.security;

import androidx.appcompat.app.AppCompatActivity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.security.IpBlockStore;

import java.util.List;

public class BlockedIpsActivity extends AppCompatActivity implements BlockedIpAdapter.Listener {
    private BlockedIpAdapter adapter;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blocked_ips);

        adapter = new BlockedIpAdapter(this);
        RecyclerView recycler = findViewById(R.id.blocks_recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        empty = findViewById(R.id.blocks_empty);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.add_button).setOnClickListener(v -> showAddDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        new Thread(() -> {
            List<IpBlockStore.Block> blocks = IpBlockStore.get(getApplicationContext()).list();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                adapter.submit(blocks);
                empty.setVisibility(blocks.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private void showAddDialog() {
        EditText input = new EditText(this);
        input.setHint("IP address");
        input.setSingleLine(true);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);

        new AlertDialog.Builder(this)
                .setTitle("Block an IP address")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Block", (dialog, which) -> {
                    String ip = input.getText().toString().trim();
                    if (!IpBlockStore.isValidIp(ip)) {
                        Toast.makeText(this, "Enter a valid IPv4 or IPv6 address",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (IpBlockStore.isLoopback(ip)) {
                        Toast.makeText(this, "Loopback addresses cannot be blocked",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    IpBlockStore.get(this).block(ip, "Added manually", 0, "user");
                    load();
                })
                .show();
    }

    @Override
    public void onUnblock(IpBlockStore.Block block) {
        IpBlockStore.get(this).unblock(block.ip);
        Toast.makeText(this, "Unblocked " + block.ip, Toast.LENGTH_SHORT).show();
        load();
    }
}
