package com.yadaventerprise.phoneserver;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Live monitoring view: real CPU/RAM/traffic per server, real API call counts
 * per endpoint, real connected-client list (from actual request IPs and
 * User-Agent headers), and a real recent-activity feed. Nothing here is
 * simulated - it all comes from ServerStats (local) or a server's own
 * /api/stats endpoint (remote), polled while this screen is open.
 */
public class DashboardActivity extends AppCompatActivity {

    private static final int POLL_INTERVAL_MS = 4000;
    private static final int MAX_HISTORY_POINTS = 20;
    private static final int MAX_CHART_POINTS = 30;

    private static class ServerCardHolder {
        View root;
        TextView name, address, statusPill, uptime;
        TextView cpuValue, ramValue, trafficValue, clientsValue, requestsValue, updownValue;
        SparklineView cpuSparkline, ramSparkline, trafficSparkline;
        Button secondaryButton, primaryButton;

        boolean isLocal;
        ServerProfile profile;

        List<Float> cpuHistory = new ArrayList<>();
        List<Float> ramHistory = new ArrayList<>();
        List<Float> trafficHistory = new ArrayList<>();

        long lastBytesUp = -1;
        long lastBytesDown = -1;
        boolean online = false;

        long snapshotRequestCount, snapshotBytesUp, snapshotBytesDown, snapshotUptime;
        int snapshotActiveClients;
        List<StatsSnapshot.EndpointEntry> snapshotEndpoints = new ArrayList<>();
        List<StatsSnapshot.ClientEntry> snapshotClients = new ArrayList<>();
        List<StatsSnapshot.ActivityEntry> snapshotActivity = new ArrayList<>();
    }

    private final List<ServerCardHolder> cardHolders = new ArrayList<>();
    private final List<Float> chartUploadMb = new ArrayList<>();
    private final List<Float> chartDownloadMb = new ArrayList<>();

    private LinearLayoutRefs refs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollAllServers();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    // simple holder for the many top-level view refs, to keep onCreate readable
    private static class LinearLayoutRefs {
        android.widget.LinearLayout serversContainer, endpointsContainer, clientsContainer, activityContainer;
        TextView endpointsEmpty, clientsEmpty, activityEmpty;
        TextView trafficTotal, trafficUp, trafficDown, apiTotal;
        TrafficChartView trafficChart;
        TextView clientsTotalText, clientsActiveText;
        TextView dashboardSubtitle;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        refs = new LinearLayoutRefs();
        refs.serversContainer = findViewById(R.id.servers_container);
        refs.endpointsContainer = findViewById(R.id.endpoints_container);
        refs.clientsContainer = findViewById(R.id.clients_container);
        refs.activityContainer = findViewById(R.id.activity_container);
        refs.endpointsEmpty = findViewById(R.id.endpoints_empty_text);
        refs.clientsEmpty = findViewById(R.id.clients_empty_text);
        refs.activityEmpty = findViewById(R.id.activity_empty_text);
        refs.trafficTotal = findViewById(R.id.traffic_total_value);
        refs.trafficUp = findViewById(R.id.traffic_up_value);
        refs.trafficDown = findViewById(R.id.traffic_down_value);
        refs.apiTotal = findViewById(R.id.api_total_value);
        refs.trafficChart = findViewById(R.id.traffic_chart);
        refs.clientsTotalText = findViewById(R.id.clients_total_text);
        refs.clientsActiveText = findViewById(R.id.clients_active_text);
        refs.dashboardSubtitle = findViewById(R.id.dashboard_subtitle);

        findViewById(R.id.manage_all_button).setOnClickListener(v ->
                startActivity(new Intent(this, ServersListActivity.class)));
        findViewById(R.id.refresh_button).setOnClickListener(v -> pollAllServers());
        findViewById(R.id.fab_add).setOnClickListener(v -> showAddServerDialog());

        setupBottomNav();
        buildServerCards();
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildServerCards(); // rebuild in case servers were added/removed elsewhere
        handler.post(pollRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(pollRunnable);
    }

    private void setupBottomNav() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setSelectedItemId(R.id.nav_dashboard);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_dashboard) {
                return true;
            } else if (id == R.id.nav_home) {
                startActivity(new Intent(this, MainActivity.class));
                finish();
            } else if (id == R.id.nav_qr) {
                startActivity(new Intent(this, QrCodeActivity.class));
            } else if (id == R.id.nav_settings) {
                startActivity(new Intent(this, ServerSettingsActivity.class));
            }
            bottomNav.post(() -> bottomNav.setSelectedItemId(R.id.nav_dashboard));
            return true;
        });
    }

    private void buildServerCards() {
        refs.serversContainer.removeAllViews();
        cardHolders.clear();

        // Local server card
        ServerCardHolder local = inflateCard(refs.serversContainer);
        local.isLocal = true;
        local.name.setText("Local Server");
        local.secondaryButton.setText("Stop");
        local.secondaryButton.setOnClickListener(v -> toggleLocalServer());
        local.primaryButton.setText("Manage");
        local.primaryButton.setOnClickListener(v -> startActivity(new Intent(this, FilesActivity.class)));
        cardHolders.add(local);
        refs.serversContainer.addView(local.root);

        // One card per saved remote server
        for (ServerProfile profile : ServerProfileStore.getAll(this)) {
            ServerCardHolder remote = inflateCard(refs.serversContainer);
            remote.isLocal = false;
            remote.profile = profile;
            remote.name.setText(profile.name);
            remote.address.setText(profile.ip + ":" + profile.port);
            ((ImageView) remote.root.findViewById(R.id.card_icon)).setImageResource(R.drawable.ic_globe);
            remote.secondaryButton.setText("Edit");
            remote.secondaryButton.setOnClickListener(v -> startActivity(new Intent(this, ServersListActivity.class)));
            remote.primaryButton.setText("Connect");
            remote.primaryButton.setOnClickListener(v -> {
                Intent intent = new Intent(this, RemoteBrowseActivity.class);
                intent.putExtra("profile_id", profile.id);
                intent.putExtra("path", "");
                startActivity(intent);
            });
            cardHolders.add(remote);
            refs.serversContainer.addView(remote.root);
        }
    }

    private ServerCardHolder inflateCard(ViewGroup parent) {
        View root = LayoutInflater.from(this).inflate(R.layout.item_dashboard_server, parent, false);
        ServerCardHolder holder = new ServerCardHolder();
        holder.root = root;
        holder.name = root.findViewById(R.id.card_name);
        holder.address = root.findViewById(R.id.card_address);
        holder.statusPill = root.findViewById(R.id.card_status_pill);
        holder.uptime = root.findViewById(R.id.card_uptime);
        holder.cpuValue = root.findViewById(R.id.card_cpu_value);
        holder.ramValue = root.findViewById(R.id.card_ram_value);
        holder.trafficValue = root.findViewById(R.id.card_traffic_value);
        holder.clientsValue = root.findViewById(R.id.card_clients_value);
        holder.requestsValue = root.findViewById(R.id.card_requests_value);
        holder.updownValue = root.findViewById(R.id.card_updown_value);
        holder.cpuSparkline = root.findViewById(R.id.card_cpu_sparkline);
        holder.ramSparkline = root.findViewById(R.id.card_ram_sparkline);
        holder.trafficSparkline = root.findViewById(R.id.card_traffic_sparkline);
        holder.secondaryButton = root.findViewById(R.id.card_secondary_button);
        holder.primaryButton = root.findViewById(R.id.card_primary_button);
        return holder;
    }

    private void toggleLocalServer() {
        if (ServerService.isServerRunning()) {
            Intent stop = new Intent(this, ServerService.class);
            stop.setAction(ServerService.ACTION_STOP);
            startService(stop);
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(new Intent(this, ServerService.class).setAction(ServerService.ACTION_START));
            } else {
                startService(new Intent(this, ServerService.class).setAction(ServerService.ACTION_START));
            }
        }
        handler.postDelayed(this::pollAllServers, 400);
    }

    // ---- polling ----

    private void pollAllServers() {
        for (ServerCardHolder holder : cardHolders) {
            if (holder.isLocal) {
                pollLocal(holder);
            } else {
                pollRemote(holder);
            }
        }
    }

    private void pollLocal(ServerCardHolder holder) {
        boolean running = ServerService.isServerRunning();
        holder.online = running;

        if (!running) {
            holder.address.setText("Not running");
            holder.statusPill.setText("Stopped");
            holder.statusPill.setTextColor(getColor(R.color.home_text_secondary));
            holder.uptime.setText("");
            holder.secondaryButton.setText("Start");
            renderCardValues(holder);
            renderAggregates();
            return;
        }

        String ip = NetworkUtils.getLocalIpAddress();
        holder.address.setText(ip != null ? ip + ":" + ServerService.PORT : "Connecting...");
        holder.statusPill.setText("Running");
        holder.statusPill.setTextColor(getColor(R.color.home_accent));
        holder.secondaryButton.setText("Stop");

        ServerStats stats = ServerStats.get();
        holder.snapshotRequestCount = stats.getRequestCount();
        holder.snapshotBytesUp = stats.getBytesUp();
        holder.snapshotBytesDown = stats.getBytesDown();
        holder.snapshotActiveClients = stats.getActiveClientCount();
        holder.snapshotUptime = ServerService.getUptimeMillis();

        holder.snapshotEndpoints.clear();
        for (Map.Entry<String, Integer> e : stats.getTopEndpoints(10)) {
            StatsSnapshot.EndpointEntry entry = new StatsSnapshot.EndpointEntry();
            entry.path = e.getKey();
            entry.count = e.getValue();
            holder.snapshotEndpoints.add(entry);
        }

        holder.snapshotClients.clear();
        for (ServerStats.ClientInfo c : stats.getClients()) {
            StatsSnapshot.ClientEntry entry = new StatsSnapshot.ClientEntry();
            entry.ip = c.ip;
            entry.os = c.os;
            entry.browser = c.browser;
            entry.lastSeen = c.lastSeenMillis;
            holder.snapshotClients.add(entry);
        }

        holder.snapshotActivity.clear();
        for (ServerStats.ActivityEvent e : stats.getRecentActivity()) {
            StatsSnapshot.ActivityEntry entry = new StatsSnapshot.ActivityEntry();
            entry.type = e.type;
            entry.message = e.message;
            entry.time = e.timeMillis;
            holder.snapshotActivity.add(entry);
        }

        int ram = DeviceStats.getRamUsedPercent(this);
        int cpu = DeviceStats.getAppCpuPercent();
        appendHistory(holder, cpu, ram);
        renderCardValues(holder);
        renderAggregates();
    }

    private void pollRemote(ServerCardHolder holder) {
        ServerProfile profile = holder.profile;
        new Thread(() -> {
            try {
                StatsSnapshot snap = RemoteApiClient.getStats(profile);
                handler.post(() -> {
                    holder.online = true;
                    holder.statusPill.setText("Running");
                    holder.statusPill.setTextColor(getColor(R.color.home_accent));
                    holder.secondaryButton.setText("Edit");

                    holder.snapshotRequestCount = snap.requestCount;
                    holder.snapshotBytesUp = snap.bytesUp;
                    holder.snapshotBytesDown = snap.bytesDown;
                    holder.snapshotActiveClients = snap.activeClients;
                    holder.snapshotUptime = snap.uptimeMillis;
                    holder.snapshotEndpoints = snap.topEndpoints;
                    holder.snapshotClients = snap.clients;
                    holder.snapshotActivity = snap.recentActivity;

                    appendHistory(holder, snap.appCpuPercent, snap.ramUsedPercent);
                    renderCardValues(holder);
                    renderAggregates();
                });
            } catch (RemoteApiClient.ApiException e) {
                handler.post(() -> {
                    holder.online = false;
                    holder.statusPill.setText("Offline");
                    holder.statusPill.setTextColor(getColor(R.color.offline_red));
                    holder.uptime.setText("");
                    renderAggregates();
                });
            }
        }).start();
    }

    private void appendHistory(ServerCardHolder holder, int cpu, int ram) {
        holder.cpuHistory.add((float) cpu);
        while (holder.cpuHistory.size() > MAX_HISTORY_POINTS) holder.cpuHistory.remove(0);
        holder.ramHistory.add((float) ram);
        while (holder.ramHistory.size() > MAX_HISTORY_POINTS) holder.ramHistory.remove(0);

        long deltaUp = holder.lastBytesUp < 0 ? 0 : Math.max(0, holder.snapshotBytesUp - holder.lastBytesUp);
        long deltaDown = holder.lastBytesDown < 0 ? 0 : Math.max(0, holder.snapshotBytesDown - holder.lastBytesDown);
        float deltaMb = (deltaUp + deltaDown) / (1024f * 1024f);
        holder.trafficHistory.add(deltaMb);
        while (holder.trafficHistory.size() > MAX_HISTORY_POINTS) holder.trafficHistory.remove(0);

        float upMb = holder.lastBytesUp < 0 ? 0 : deltaUp / (1024f * 1024f);
        float downMb = holder.lastBytesDown < 0 ? 0 : deltaDown / (1024f * 1024f);
        chartUploadMb.add(upMb);
        while (chartUploadMb.size() > MAX_CHART_POINTS) chartUploadMb.remove(0);
        chartDownloadMb.add(downMb);
        while (chartDownloadMb.size() > MAX_CHART_POINTS) chartDownloadMb.remove(0);

        holder.lastBytesUp = holder.snapshotBytesUp;
        holder.lastBytesDown = holder.snapshotBytesDown;
    }

    private void renderCardValues(ServerCardHolder holder) {
        int cpu = holder.cpuHistory.isEmpty() ? 0 : holder.cpuHistory.get(holder.cpuHistory.size() - 1).intValue();
        int ram = holder.ramHistory.isEmpty() ? 0 : holder.ramHistory.get(holder.ramHistory.size() - 1).intValue();
        holder.cpuValue.setText(cpu + "%");
        holder.ramValue.setText(ram + "%");
        holder.trafficValue.setText(ServerStats.formatBytes(holder.snapshotBytesUp + holder.snapshotBytesDown));
        holder.clientsValue.setText(String.valueOf(holder.snapshotActiveClients));
        holder.requestsValue.setText(String.valueOf(holder.snapshotRequestCount));
        holder.updownValue.setText(ServerStats.formatBytes(holder.snapshotBytesUp) + " / " + ServerStats.formatBytes(holder.snapshotBytesDown));
        holder.uptime.setText(holder.online ? "Uptime: " + formatUptime(holder.snapshotUptime) : "");

        holder.cpuSparkline.setValues(holder.cpuHistory);
        holder.ramSparkline.setValues(holder.ramHistory);
        holder.trafficSparkline.setValues(holder.trafficHistory);
    }

    private String formatUptime(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    // ---- aggregated overview + panels ----

    private void renderAggregates() {
        int onlineServers = 0;
        long totalRequests = 0, totalUp = 0, totalDown = 0;
        int totalActiveClients = 0;
        Map<String, Integer> mergedEndpoints = new HashMap<>();
        List<StatsSnapshot.ClientEntry> mergedClients = new ArrayList<>();
        List<StatsSnapshot.ActivityEntry> mergedActivity = new ArrayList<>();

        for (ServerCardHolder holder : cardHolders) {
            if (!holder.online) continue;
            onlineServers++;
            totalRequests += holder.snapshotRequestCount;
            totalUp += holder.snapshotBytesUp;
            totalDown += holder.snapshotBytesDown;
            totalActiveClients += holder.snapshotActiveClients;
            for (StatsSnapshot.EndpointEntry e : holder.snapshotEndpoints) {
                mergedEndpoints.merge(e.path, e.count, Integer::sum);
            }
            mergedClients.addAll(holder.snapshotClients);
            mergedActivity.addAll(holder.snapshotActivity);
        }

        // Overview stat cards
        bindStatCard(R.id.stat_servers, R.drawable.ic_server_stack, String.valueOf(cardHolders.size()),
                "Servers", onlineServers + " Running");
        bindStatCard(R.id.stat_clients, R.drawable.ic_globe, String.valueOf(totalActiveClients),
                "Connected", totalActiveClients + " Active");
        bindStatCard(R.id.stat_traffic, R.drawable.ic_upload, ServerStats.formatBytes(totalUp + totalDown),
                "Total Traffic", "↑" + ServerStats.formatBytes(totalUp) + " ↓" + ServerStats.formatBytes(totalDown));
        bindStatCard(R.id.stat_api, R.drawable.ic_dashboard_grid, String.valueOf(totalRequests),
                "API Calls", onlineServers + " server" + (onlineServers == 1 ? "" : "s"));

        refs.dashboardSubtitle.setText(onlineServers + " of " + cardHolders.size() + " servers online");

        // Traffic Overview card
        refs.trafficTotal.setText(ServerStats.formatBytes(totalUp + totalDown));
        refs.trafficUp.setText("↑ " + ServerStats.formatBytes(totalUp) + " Upload");
        refs.trafficDown.setText("↓ " + ServerStats.formatBytes(totalDown) + " Download");
        refs.trafficChart.setData(chartUploadMb, chartDownloadMb);

        // API Calls card
        refs.apiTotal.setText(String.valueOf(totalRequests));
        renderEndpoints(mergedEndpoints);

        // Connected Clients card
        renderClients(mergedClients);

        // Recent Activity card
        renderActivity(mergedActivity);
    }

    private void bindStatCard(int includeId, int iconRes, String value, String label, String sub) {
        View card = findViewById(includeId);
        ((ImageView) card.findViewById(R.id.stat_icon)).setImageResource(iconRes);
        ((TextView) card.findViewById(R.id.stat_value)).setText(value);
        ((TextView) card.findViewById(R.id.stat_label)).setText(label);
        ((TextView) card.findViewById(R.id.stat_sub)).setText(sub);
    }

    private void renderEndpoints(Map<String, Integer> merged) {
        refs.endpointsContainer.removeAllViews();
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(merged.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        if (entries.size() > 6) entries = entries.subList(0, 6);

        int total = 0;
        for (Map.Entry<String, Integer> e : entries) total += e.getValue();

        refs.endpointsEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);

        for (Map.Entry<String, Integer> e : entries) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_endpoint_bar, refs.endpointsContainer, false);
            ((TextView) row.findViewById(R.id.endpoint_path)).setText(e.getKey());
            int percent = total > 0 ? (e.getValue() * 100 / total) : 0;
            ((TextView) row.findViewById(R.id.endpoint_count)).setText(e.getValue() + " (" + percent + "%)");

            View track = row.findViewById(R.id.endpoint_bar_track);
            View fill = row.findViewById(R.id.endpoint_bar_fill);
            track.post(() -> {
                ViewGroup.LayoutParams params = fill.getLayoutParams();
                params.width = Math.max(4, track.getWidth() * percent / 100);
                fill.setLayoutParams(params);
            });

            refs.endpointsContainer.addView(row);
        }
    }

    private void renderClients(List<StatsSnapshot.ClientEntry> clients) {
        refs.clientsContainer.removeAllViews();
        clients.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        int shown = Math.min(clients.size(), 8);

        refs.clientsEmpty.setVisibility(clients.isEmpty() ? View.VISIBLE : View.GONE);

        long now = System.currentTimeMillis();
        int activeCount = 0;
        for (StatsSnapshot.ClientEntry c : clients) {
            if (now - c.lastSeen <= 5 * 60 * 1000) activeCount++;
        }
        refs.clientsTotalText.setText("Total " + clients.size() + " client" + (clients.size() == 1 ? "" : "s"));
        refs.clientsActiveText.setText(activeCount + " Active");

        java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US);
        for (int i = 0; i < shown; i++) {
            StatsSnapshot.ClientEntry c = clients.get(i);
            View row = LayoutInflater.from(this).inflate(R.layout.item_dashboard_client, refs.clientsContainer, false);
            ((TextView) row.findViewById(R.id.client_ip)).setText(c.ip);
            ((TextView) row.findViewById(R.id.client_device)).setText(c.os + " - " + c.browser);
            ((TextView) row.findViewById(R.id.client_time)).setText(timeFormat.format(new java.util.Date(c.lastSeen)));
            String initial = c.os != null && !c.os.isEmpty() ? c.os.substring(0, 1) : "?";
            ((TextView) row.findViewById(R.id.client_os_initial)).setText(initial);

            boolean active = now - c.lastSeen <= 5 * 60 * 1000;
            TextView activeLabel = row.findViewById(R.id.client_active_label);
            activeLabel.setText(active ? "Active" : "Idle");
            activeLabel.setTextColor(getColor(active ? R.color.home_accent : R.color.home_text_secondary));

            refs.clientsContainer.addView(row);
        }
    }

    private void renderActivity(List<StatsSnapshot.ActivityEntry> activity) {
        refs.activityContainer.removeAllViews();
        activity.sort((a, b) -> Long.compare(b.time, a.time));
        int shown = Math.min(activity.size(), 10);

        refs.activityEmpty.setVisibility(activity.isEmpty() ? View.VISIBLE : View.GONE);

        java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("h:mm a", Locale.US);
        for (int i = 0; i < shown; i++) {
            StatsSnapshot.ActivityEntry event = activity.get(i);
            View row = LayoutInflater.from(this).inflate(R.layout.item_activity_log, refs.activityContainer, false);

            String title;
            int icon;
            switch (event.type) {
                case "upload":
                    title = "File uploaded";
                    icon = R.drawable.ic_upload;
                    break;
                case "client":
                    title = "New client connected";
                    icon = R.drawable.ic_globe;
                    break;
                case "start":
                    title = "Server started";
                    icon = R.drawable.ic_server_stack;
                    break;
                default:
                    title = "Activity";
                    icon = R.drawable.ic_folder;
            }
            ((TextView) row.findViewById(R.id.activity_title)).setText(title);
            ((TextView) row.findViewById(R.id.activity_detail)).setText(event.message);
            ((TextView) row.findViewById(R.id.activity_time)).setText(timeFormat.format(new java.util.Date(event.time)));
            ((ImageView) row.findViewById(R.id.activity_icon)).setImageResource(icon);

            refs.activityContainer.addView(row);
        }
    }

    private void showAddServerDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_server, null);
        EditText nameInput = dialogView.findViewById(R.id.input_name);
        EditText ipInput = dialogView.findViewById(R.id.input_ip);
        EditText portInput = dialogView.findViewById(R.id.input_port);
        EditText passwordInput = dialogView.findViewById(R.id.input_password);

        new AlertDialog.Builder(this)
                .setTitle("Add Server")
                .setView(dialogView)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    String ip = ipInput.getText().toString().trim();
                    String portText = portInput.getText().toString().trim();
                    String password = passwordInput.getText().toString();

                    if (ip.isEmpty()) {
                        Toast.makeText(this, "Enter an IP address", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int port = 8080;
                    try {
                        if (!portText.isEmpty()) port = Integer.parseInt(portText);
                    } catch (NumberFormatException ignored) {
                    }

                    ServerProfile profile = new ServerProfile();
                    profile.name = name.isEmpty() ? ip : name;
                    profile.ip = ip;
                    profile.port = port;
                    profile.password = password;

                    ServerProfileStore.save(this, profile);
                    buildServerCards();
                    pollAllServers();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
