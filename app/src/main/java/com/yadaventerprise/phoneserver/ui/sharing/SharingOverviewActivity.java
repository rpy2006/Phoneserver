package com.yadaventerprise.phoneserver.ui.sharing;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.sharing.ShareAccessLogStore;
import com.yadaventerprise.phoneserver.data.sharing.ShareLink;
import com.yadaventerprise.phoneserver.data.sharing.ShareSettings;
import com.yadaventerprise.phoneserver.data.sharing.ShareSettingsStore;
import com.yadaventerprise.phoneserver.data.sharing.ShareStore;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfig;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfigStore;
import com.yadaventerprise.phoneserver.service.ServerService;
import java.util.List;

public class SharingOverviewActivity extends AppCompatActivity {

    private TextView securityBanner;
    private TextView statActive;
    private TextView statExpired;
    private TextView statDownloads;
    private TextView statFolders;
    private TextView filesSubtitle;
    private TextView foldersSubtitle;
    private TextView activeSubtitle;
    private TextView expiredSubtitle;
    private TextView historySubtitle;
    private TextView settingsSubtitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sharing_overview);
        securityBanner = findViewById(R.id.security_banner);
        statActive = findViewById(R.id.stat_active);
        statExpired = findViewById(R.id.stat_expired);
        statDownloads = findViewById(R.id.stat_downloads);
        statFolders = findViewById(R.id.stat_folders);
        filesSubtitle = findViewById(R.id.shared_files_subtitle);
        foldersSubtitle = findViewById(R.id.shared_folders_subtitle);
        activeSubtitle = findViewById(R.id.active_links_subtitle);
        expiredSubtitle = findViewById(R.id.expired_links_subtitle);
        historySubtitle = findViewById(R.id.history_subtitle);
        settingsSubtitle = findViewById(R.id.settings_subtitle);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.add_button).setOnClickListener(v ->
                startActivity(new Intent(this, ShareEditActivity.class)));
        findViewById(R.id.row_shared_files).setOnClickListener(v ->
                openList(ShareListActivity.MODE_FILES));
        findViewById(R.id.row_shared_folders).setOnClickListener(v ->
                openList(ShareListActivity.MODE_FOLDERS));
        findViewById(R.id.row_active_links).setOnClickListener(v ->
                openList(ShareListActivity.MODE_ACTIVE));
        findViewById(R.id.row_expired_links).setOnClickListener(v ->
                openList(ShareListActivity.MODE_EXPIRED));
        findViewById(R.id.row_access_history).setOnClickListener(v ->
                startActivity(new Intent(this, ShareAccessHistoryActivity.class)));
        findViewById(R.id.row_sharing_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SharingSettingsActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        List<ShareLink> links = ShareStore.get(this).all();
        int active = 0;
        int inactive = 0;
        int downloads = 0;
        int fileLinks = 0;
        int folderLinks = 0;
        int activeFolders = 0;
        for (ShareLink link : links) {
            downloads += link.downloadCount;
            if (ShareLink.TYPE_FOLDER.equals(link.type)) {
                folderLinks++;
                if (link.isActive(now)) activeFolders++;
            } else {
                fileLinks++;
            }
            if (link.isActive(now)) active++;
            else inactive++;
        }
        statActive.setText(String.valueOf(active));
        statExpired.setText(String.valueOf(inactive));
        statDownloads.setText(String.valueOf(downloads));
        statFolders.setText(String.valueOf(activeFolders));

        filesSubtitle.setText(fileLinks == 0 ? "No file links yet"
                : fileLinks + (fileLinks == 1 ? " file link" : " file links"));
        foldersSubtitle.setText(folderLinks == 0 ? "No folder links yet"
                : activeFolders + " active of " + folderLinks);
        activeSubtitle.setText(active == 0 ? "No active links"
                : active + (active == 1 ? " active link" : " active links"));
        expiredSubtitle.setText(inactive == 0 ? "No expired or revoked links"
                : inactive + (inactive == 1 ? " inactive link" : " inactive links"));
        int events = ShareAccessLogStore.get(this).read(new ShareAccessLogStore.Query()).size();
        historySubtitle.setText(events == 0 ? "No views or downloads recorded"
                : events + (events == 1 ? " recent event" : " recent events"));

        ShareSettings settings = ShareSettingsStore.get(this).get();
        String expiry = settings.defaultExpiryMinutes == 0 ? "no expiry"
                : formatMinutes(settings.defaultExpiryMinutes);
        settingsSubtitle.setText("Default " + expiry + " · "
                + (ShareLink.PERMISSION_VIEW.equals(settings.defaultPermission)
                ? "view only" : "view & download"));

        HttpsConfig https = HttpsConfigStore.get(this).get();
        if (https.enabled) {
            securityBanner.setText("HTTPS is enabled. New links use https:// and are encrypted in transit."
                    + (ServerService.isServerRunning() ? "" : " Start the server before sharing."));
            securityBanner.setTextColor(getColor(R.color.home_accent));
        } else {
            securityBanner.setText("HTTPS is off. Links use plain HTTP and are not encrypted. "
                    + "Enable HTTPS before sharing outside your local network."
                    + (ServerService.isServerRunning() ? "" : " The server is not running."));
            securityBanner.setTextColor(getColor(R.color.offline_red));
        }
        findViewById(R.id.add_button).setVisibility(View.VISIBLE);
    }

    private void openList(String mode) {
        Intent intent = new Intent(this, ShareListActivity.class);
        intent.putExtra(ShareListActivity.EXTRA_MODE, mode);
        startActivity(intent);
    }

    private String formatMinutes(int minutes) {
        if (minutes % 1440 == 0) {
            int days = minutes / 1440;
            return days + (days == 1 ? " day" : " days");
        }
        if (minutes % 60 == 0) {
            int hours = minutes / 60;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return minutes + " minutes";
    }
}
