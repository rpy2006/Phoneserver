package com.yadaventerprise.phoneserver.ui.sharing;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.sharing.ShareAccessEvent;
import com.yadaventerprise.phoneserver.data.sharing.ShareAccessLogStore;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ShareAccessHistoryActivity extends AppCompatActivity {

    public static final String EXTRA_LINK_ID = "history_link_id";
    public static final String EXTRA_LINK_NAME = "history_link_name";

    private static final String[] FILTER_LABELS = {
            "All events", "Visits", "Views", "Downloads", "Unlocks", "Denied",
            "Invalid links", "Management"};

    private final List<ShareAccessEvent> events = new ArrayList<>();
    private EventAdapter adapter;
    private EditText searchInput;
    private TextView empty;
    private TextView note;
    private String linkId = "";
    private String linkName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share_history);
        linkId = getIntent().getStringExtra(EXTRA_LINK_ID);
        if (linkId == null) linkId = "";
        linkName = getIntent().getStringExtra(EXTRA_LINK_NAME);
        if (linkName == null) linkName = "";

        TextView title = findViewById(R.id.screen_title);
        searchInput = findViewById(R.id.search_input);
        empty = findViewById(R.id.event_empty);
        note = findViewById(R.id.history_note);
        Spinner filter = findViewById(R.id.event_filter);
        RecyclerView recycler = findViewById(R.id.event_recycler);

        if (!linkId.isEmpty()) {
            title.setText("Link Activity");
            note.setText("Activity for " + (linkName.isEmpty() ? "this link" : linkName)
                    + ". The log stores IP, path and browser details, never passwords or link secrets.");
        } else {
            note.setText("Only the most recent 2,000 events are shown. The log stores IP, path and "
                    + "browser details, never passwords or link secrets.");
        }
        note.setVisibility(View.VISIBLE);

        ArrayAdapter<String> filterAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, FILTER_LABELS);
        filterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filter.setAdapter(filterAdapter);

        adapter = new EventAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        View clear = findViewById(R.id.clear_button);
        if (linkId.isEmpty()) {
            clear.setOnClickListener(v -> confirmClear());
        } else {
            clear.setVisibility(View.GONE);
        }
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                refresh();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refresh();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        refresh();
    }

    private void refresh() {
        Spinner filter = findViewById(R.id.event_filter);
        int category = filter.getSelectedItemPosition();
        ShareAccessLogStore.Query query = new ShareAccessLogStore.Query();
        query.linkId = linkId;
        query.search = searchInput.getText().toString();
        query.limit = 2000;

        events.clear();
        for (ShareAccessEvent event : ShareAccessLogStore.get(this).read(query)) {
            if (matchesCategory(event, category)) events.add(event);
        }
        adapter.notifyDataSetChanged();
        empty.setText(emptyText(category));
        empty.setVisibility(events.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private boolean matchesCategory(ShareAccessEvent event, int category) {
        String name = event.event == null ? "" : event.event;
        switch (category) {
            case 1:
                return ShareAccessEvent.VISIT.equals(name);
            case 2:
                return ShareAccessEvent.VIEW.equals(name);
            case 3:
                return ShareAccessEvent.DOWNLOAD.equals(name);
            case 4:
                return ShareAccessEvent.UNLOCKED.equals(name);
            case 5:
                return name.startsWith("denied_");
            case 6:
                return ShareAccessEvent.INVALID_TOKEN.equals(name);
            case 7:
                return ShareAccessEvent.CREATED.equals(name)
                        || ShareAccessEvent.UPDATED.equals(name)
                        || ShareAccessEvent.REVOKED.equals(name)
                        || ShareAccessEvent.RESTORED.equals(name)
                        || ShareAccessEvent.DELETED.equals(name)
                        || ShareAccessEvent.ROTATED.equals(name);
            default:
                return true;
        }
    }

    private String emptyText(int category) {
        if (category == 0) return "No sharing access recorded yet.";
        if (category == 5) return "No denied access attempts recorded.";
        return "No events match this filter.";
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Clear sharing history?")
                .setMessage("All recorded share views, downloads and management events will be deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    ShareAccessLogStore.get(this).clear();
                    refresh();
                })
                .show();
    }

    private class EventAdapter extends RecyclerView.Adapter<EventHolder> {

        @NonNull
        @Override
        public EventHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_share_event, parent, false);
            return new EventHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull EventHolder holder, int position) {
            ShareAccessEvent event = events.get(position);
            String name = event.linkName == null || event.linkName.isEmpty()
                    ? "Unknown link" : event.linkName;
            holder.name.setText(name);
            holder.status.setText(eventLabel(event.event)
                    + (event.status > 0 ? " · " + event.status : ""));
            holder.status.setTextColor(holder.itemView.getContext().getColor(
                    event.status >= 400 ? R.color.offline_red : R.color.home_text_secondary));
            holder.time.setText(event.time <= 0 ? "" : DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT).format(new Date(event.time)));
            holder.link.setText(!empty(event.path) ? event.path : event.detail);
            holder.detail.setText(detailText(event));
        }

        @Override
        public int getItemCount() {
            return events.size();
        }
    }

    private static class EventHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView status;
        final TextView time;
        final TextView link;
        final TextView detail;

        EventHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.event_name);
            status = itemView.findViewById(R.id.event_status);
            time = itemView.findViewById(R.id.event_time);
            link = itemView.findViewById(R.id.event_link);
            detail = itemView.findViewById(R.id.event_detail);
        }
    }

    private String detailText(ShareAccessEvent event) {
        StringBuilder builder = new StringBuilder();
        if (!empty(event.ip)) builder.append(event.ip);
        if (!empty(event.path) && !empty(event.detail)) {
            if (builder.length() > 0) builder.append(" · ");
            builder.append(event.detail);
        }
        if (!empty(event.userAgent)) {
            if (builder.length() > 0) builder.append(" · ");
            String ua = event.userAgent;
            builder.append(ua.length() > 90 ? ua.substring(0, 90) + "…" : ua);
        }
        return builder.toString();
    }

    private boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String eventLabel(String event) {
        if (event == null) return "Event";
        switch (event) {
            case ShareAccessEvent.CREATED: return "Created";
            case ShareAccessEvent.UPDATED: return "Updated";
            case ShareAccessEvent.REVOKED: return "Revoked";
            case ShareAccessEvent.RESTORED: return "Restored";
            case ShareAccessEvent.DELETED: return "Deleted";
            case ShareAccessEvent.ROTATED: return "Secret rotated";
            case ShareAccessEvent.VISIT: return "Visited";
            case ShareAccessEvent.VIEW: return "Viewed";
            case ShareAccessEvent.DOWNLOAD: return "Downloaded";
            case ShareAccessEvent.UNLOCKED: return "Password unlocked";
            case ShareAccessEvent.INVALID_TOKEN: return "Invalid link";
            case ShareAccessEvent.DENIED_PASSWORD: return "Wrong password";
            case ShareAccessEvent.DENIED_EXPIRED: return "Denied: expired";
            case ShareAccessEvent.DENIED_REVOKED: return "Denied: revoked";
            case ShareAccessEvent.DENIED_LIMIT: return "Denied: download limit";
            case ShareAccessEvent.DENIED_PERMISSION: return "Denied: no download";
            case ShareAccessEvent.DENIED_RATE_LIMIT: return "Denied: rate limit";
            default: return event;
        }
    }
}
