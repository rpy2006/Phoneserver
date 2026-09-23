package com.yadaventerprise.phoneserver;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.util.List;
import java.util.Locale;

public class HomeFragment extends Fragment {

    private TextView statSerersCount, statServersStatus, statFilesCount;
    private TextView localAddressText, localStatusText, localUptimeText;
    private View localStatusDot;
    private Button localToggleButton, localManageButton;
    private android.widget.LinearLayout remoteServersContainer;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshLocalServerCard();
            refreshOverviewStats();
            handler.postDelayed(this, 2000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        statSerersCount = view.findViewById(R.id.stat_servers_count);
        statServersStatus = view.findViewById(R.id.stat_servers_status);
        statFilesCount = view.findViewById(R.id.stat_files_count);

        localAddressText = view.findViewById(R.id.local_address_text);
        localStatusDot = view.findViewById(R.id.local_status_dot);
        localStatusText = view.findViewById(R.id.local_status_text);
        localUptimeText = view.findViewById(R.id.local_uptime_text);
        localToggleButton = view.findViewById(R.id.local_toggle_button);
        localManageButton = view.findViewById(R.id.local_manage_button);
        remoteServersContainer = view.findViewById(R.id.remote_servers_container);

        localToggleButton.setOnClickListener(v -> onToggleClicked());
        localManageButton.setOnClickListener(v -> startActivity(new Intent(getContext(), FilesActivity.class)));

        view.findViewById(R.id.menu_button).setOnClickListener(this::showOverflowMenu);
        view.findViewById(R.id.overflow_button).setOnClickListener(this::showOverflowMenu);
        view.findViewById(R.id.search_button).setOnClickListener(v ->
                startActivity(new Intent(getContext(), FilesActivity.class)));

        view.findViewById(R.id.see_all_button).setOnClickListener(v ->
                startActivity(new Intent(getContext(), ServersListActivity.class)));

        view.findViewById(R.id.quick_browse_files).setOnClickListener(v ->
                startActivity(new Intent(getContext(), FilesActivity.class)));
        view.findViewById(R.id.quick_toggle_server).setOnClickListener(v -> onToggleClicked());
        view.findViewById(R.id.quick_qr).setOnClickListener(v -> goToQrTab());
        view.findViewById(R.id.quick_settings).setOnClickListener(v -> goToSettingsTab());

        view.findViewById(R.id.fab_add).setOnClickListener(v -> showAddServerDialog());
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshRunnable.run();
        refreshRemoteServers();
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
    }

    private void goToSettingsTab() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).goToPage(MainPagerAdapter.PAGE_SETTINGS);
        }
    }

    private void goToQrTab() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).goToPage(MainPagerAdapter.PAGE_QR);
        }
    }

    private void showOverflowMenu(View anchor) {
        Context context = getContext();
        if (context == null) return;
        PopupMenu menu = new PopupMenu(context, anchor);
        menu.getMenu().add("Edit Website");
        menu.getMenu().add("API Endpoints");
        menu.getMenu().add("Storage Access");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.equals("Edit Website")) {
                startActivity(new Intent(context, WebsiteFilesActivity.class));
            } else if (title.equals("API Endpoints")) {
                startActivity(new Intent(context, ApiEditorActivity.class));
            } else if (title.equals("Storage Access")) {
                if (ContentStore.hasFullStorageAccess(context)) {
                    Toast.makeText(context, "Already granted", Toast.LENGTH_SHORT).show();
                } else if (getActivity() != null) {
                    ContentStore.requestFullStorageAccess(getActivity());
                }
            }
            return true;
        });
        menu.show();
    }

    private void onToggleClicked() {
        Context context = getContext();
        if (context == null) return;
        if (ServerService.isServerRunning()) {
            Intent stop = new Intent(context, ServerService.class);
            stop.setAction(ServerService.ACTION_STOP);
            context.startService(stop);
        } else {
            requestNotificationPermissionIfNeeded();
            Intent start = new Intent(context, ServerService.class);
            start.setAction(ServerService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(start);
            } else {
                context.startService(start);
            }
        }
        handler.postDelayed(this::refreshLocalServerCard, 300);
        handler.postDelayed(this::refreshOverviewStats, 300);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && getActivity() != null) {
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(getActivity(),
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    private void refreshLocalServerCard() {
        if (getContext() == null) return;
        boolean running = ServerService.isServerRunning();
        if (running) {
            String ip = NetworkUtils.getLocalIpAddress();
            localAddressText.setText(ip != null ? ip + ":" + ServerService.PORT : "Connect to Wi-Fi");
            localStatusDot.setBackgroundTintList(getContext().getColorStateList(R.color.online_green));
            localStatusText.setText("Running");
            localStatusText.setTextColor(getContext().getColor(R.color.home_accent));
            localUptimeText.setText(formatUptime(ServerService.getUptimeMillis()));
            localToggleButton.setText("Stop");
        } else {
            localAddressText.setText("Not running");
            localStatusDot.setBackgroundTintList(getContext().getColorStateList(R.color.offline_red));
            localStatusText.setText("Offline");
            localStatusText.setTextColor(getContext().getColor(R.color.home_text_secondary));
            localUptimeText.setText("");
            localToggleButton.setText("Start");
        }

        View root = getView();
        if (root != null) {
            TextView quickLabel = root.findViewById(R.id.quick_toggle_label);
            if (quickLabel != null) quickLabel.setText(running ? "Stop Server" : "Start Server");
        }
    }

    private String formatUptime(long millis) {
        if (millis <= 0) return "";
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "Uptime: %02d:%02d:%02d", hours, minutes, seconds);
    }

    private void refreshOverviewStats() {
        Context context = getContext();
        if (context == null) return;
        boolean running = ServerService.isServerRunning();
        List<ServerProfile> profiles = ServerProfileStore.getAll(context);
        int totalServers = 1 + profiles.size();
        statSerersCount.setText(String.valueOf(totalServers));
        statServersStatus.setText(running ? "● Running" : "● Stopped");
        statServersStatus.setTextColor(context.getColor(running ? R.color.home_accent : R.color.home_text_secondary));

        new Thread(() -> {
            int count = countFilesRecursive(ContentStore.getPublicFilesDir(context), 0);
            handler.post(() -> {
                if (statFilesCount != null) statFilesCount.setText(String.valueOf(count));
            });
        }).start();
    }

    private int countFilesRecursive(File dir, int depth) {
        if (dir == null || depth > 4) return 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int count = 0;
        for (File f : children) {
            if (f.isDirectory()) {
                count += countFilesRecursive(f, depth + 1);
            } else {
                count++;
            }
        }
        return count;
    }

    private void refreshRemoteServers() {
        Context context = getContext();
        if (context == null || remoteServersContainer == null) return;
        remoteServersContainer.removeAllViews();
        List<ServerProfile> profiles = ServerProfileStore.getAll(context);
        int shown = 0;
        for (ServerProfile profile : profiles) {
            if (shown >= 3) break;
            addRemoteServerCard(profile);
            shown++;
        }
    }

    private void addRemoteServerCard(ServerProfile profile) {
        Context context = getContext();
        if (context == null) return;
        View card = LayoutInflater.from(context).inflate(R.layout.item_home_server, remoteServersContainer, false);

        ImageView icon = card.findViewById(R.id.server_icon);
        TextView name = card.findViewById(R.id.server_name);
        TextView address = card.findViewById(R.id.server_address);
        View statusDot = card.findViewById(R.id.server_status_dot);
        TextView statusText = card.findViewById(R.id.server_status_text);
        TextView menuButton = card.findViewById(R.id.server_menu);
        Button secondaryButton = card.findViewById(R.id.server_secondary_button);
        Button primaryButton = card.findViewById(R.id.server_primary_button);

        icon.setImageResource(R.drawable.ic_globe);
        name.setText(profile.name);
        address.setText(profile.ip + ":" + profile.port);
        statusDot.setBackgroundTintList(context.getColorStateList(R.color.home_text_secondary));
        statusText.setText("Checking...");
        statusText.setTextColor(context.getColor(R.color.home_text_secondary));

        secondaryButton.setText("Edit");
        secondaryButton.setOnClickListener(v -> startActivity(new Intent(context, ServersListActivity.class)));
        primaryButton.setText("Connect");
        primaryButton.setOnClickListener(v -> {
            Intent intent = new Intent(context, RemoteBrowseActivity.class);
            intent.putExtra("profile_id", profile.id);
            intent.putExtra("path", "");
            startActivity(intent);
        });
        menuButton.setOnClickListener(v -> startActivity(new Intent(context, ServersListActivity.class)));

        remoteServersContainer.addView(card);

        new Thread(() -> {
            boolean online = RemoteApiClient.isOnline(profile);
            handler.post(() -> {
                if (getContext() == null) return;
                statusDot.setBackgroundTintList(getContext().getColorStateList(online ? R.color.online_green : R.color.offline_red));
                statusText.setText(online ? "Online" : "Offline");
                statusText.setTextColor(getContext().getColor(online ? R.color.home_accent : R.color.home_text_secondary));
            });
        }).start();
    }

    private void showAddServerDialog() {
        Context context = getContext();
        if (context == null) return;
        startActivity(new Intent(context, AddServerActivity.class));
    }
}
