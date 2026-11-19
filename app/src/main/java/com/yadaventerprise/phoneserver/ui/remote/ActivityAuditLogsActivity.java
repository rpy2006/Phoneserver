package com.yadaventerprise.phoneserver.ui.remote;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.remote.RemoteAuditEntry;
import com.yadaventerprise.phoneserver.data.remote.RemoteAuditStore;
import java.util.Collections;
import java.util.List;

public class ActivityAuditLogsActivity extends AppCompatActivity {

    private TextView summaryText;
    private TextView emptyText;
    private LinearLayout entriesContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_activity_audit_logs);

        summaryText = findViewById(R.id.entries_summary);
        emptyText = findViewById(R.id.entries_empty);
        entriesContainer = findViewById(R.id.entries_container);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.refresh_button).setOnClickListener(v -> load());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        new Thread(() -> {
            final List<RemoteAuditEntry> entries =
                    RemoteAuditStore.get(getApplicationContext()).all(200);
            Collections.sort(entries, (a, b) -> Long.compare(b.timestamp, a.timestamp));
            runOnUiThread(() -> {
                if (isFinishing()) return;
                entriesContainer.removeAllViews();
                emptyText.setVisibility(entries.isEmpty()
                        ? android.view.View.VISIBLE : android.view.View.GONE);
                summaryText.setText("Showing " + entries.size() + " most recent event(s)");
                for (RemoteAuditEntry entry : entries) addEntryCard(entry);
            });
        }).start();
    }

    private void addEntryCard(RemoteAuditEntry entry) {
        LinearLayout card = RemoteUi.card(this);
        String event = entry.event.isEmpty() ? "remote event" : entry.event;
        card.addView(RemoteUi.row(this, R.drawable.ic_history, event,
                RemoteUi.time(entry.timestamp) + " · " + (entry.allowed ? "allowed" : "denied")));
        card.addView(RemoteUi.detail(this, "Actor: " + value(entry.actor)));
        card.addView(RemoteUi.detail(this, "Subject: " + value(entry.subject)));
        card.addView(RemoteUi.detail(this, "IP: " + value(entry.ip)));
        card.addView(RemoteUi.detail(this, "Details: " + value(entry.details)));
        entriesContainer.addView(card);
    }

    private String value(String text) {
        return text == null || text.isEmpty() ? "—" : text;
    }
}
