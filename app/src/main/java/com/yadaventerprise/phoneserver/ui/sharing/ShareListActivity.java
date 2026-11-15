package com.yadaventerprise.phoneserver.ui.sharing;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.sharing.ShareLink;
import com.yadaventerprise.phoneserver.data.sharing.ShareStore;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ShareListActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "share_list_mode";
    public static final String MODE_FILES = "files";
    public static final String MODE_FOLDERS = "folders";
    public static final String MODE_ACTIVE = "active";
    public static final String MODE_EXPIRED = "expired";

    private final List<ShareLink> links = new ArrayList<>();
    private LinkAdapter adapter;
    private TextView subtitle;
    private TextView empty;
    private String mode = MODE_FILES;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share_list);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = MODE_FILES;
        subtitle = findViewById(R.id.list_subtitle);
        empty = findViewById(R.id.share_empty);
        RecyclerView recycler = findViewById(R.id.share_recycler);
        adapter = new LinkAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        View add = findViewById(R.id.add_button);
        if (MODE_EXPIRED.equals(mode)) {
            add.setVisibility(View.GONE);
        } else {
            add.setOnClickListener(v -> openEditor(null));
        }
        configureHeader();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void configureHeader() {
        TextView title = findViewById(R.id.screen_title);
        if (MODE_FOLDERS.equals(mode)) {
            title.setText("Shared Folders");
            subtitle.setText("Folder links and their permissions");
        } else if (MODE_ACTIVE.equals(mode)) {
            title.setText("Active Links");
            subtitle.setText("Links that can currently be opened");
        } else if (MODE_EXPIRED.equals(mode)) {
            title.setText("Expired Links");
            subtitle.setText("Expired and revoked links are kept for your records");
        } else {
            title.setText("Shared Files");
            subtitle.setText("File links and their download limits");
        }
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        links.clear();
        for (ShareLink link : ShareStore.get(this).all()) {
            boolean matches;
            if (MODE_FILES.equals(mode)) {
                matches = ShareLink.TYPE_FILE.equals(link.type);
            } else if (MODE_FOLDERS.equals(mode)) {
                matches = ShareLink.TYPE_FOLDER.equals(link.type);
            } else if (MODE_ACTIVE.equals(mode)) {
                matches = link.isActive(now);
            } else {
                matches = !link.isActive(now);
            }
            if (matches) links.add(link);
        }
        adapter.notifyDataSetChanged();
        empty.setText(emptyText());
        empty.setVisibility(links.isEmpty() ? View.VISIBLE : View.GONE);
        subtitle.setText(subtitleText());
    }

    private String emptyText() {
        if (MODE_FOLDERS.equals(mode)) return "No folder links. Tap + New to share a folder.";
        if (MODE_ACTIVE.equals(mode)) return "No active links right now.";
        if (MODE_EXPIRED.equals(mode)) return "No expired or revoked links.";
        return "No file links. Tap + New to share a file.";
    }

    private String subtitleText() {
        int count = links.size();
        if (MODE_EXPIRED.equals(mode)) {
            return count == 0 ? "Expired and revoked links stay visible here"
                    : count + (count == 1 ? " inactive link" : " inactive links");
        }
        if (MODE_ACTIVE.equals(mode)) {
            return count == 0 ? "Nothing is currently shared"
                    : count + (count == 1 ? " link can be opened" : " links can be opened");
        }
        return count == 0 ? "No links in this section"
                : count + (count == 1 ? " link in this section" : " links in this section");
    }

    private void openEditor(String id) {
        Intent intent = new Intent(this, ShareEditActivity.class);
        if (id != null) {
            intent.putExtra(ShareEditActivity.EXTRA_ID, id);
        } else if (MODE_FOLDERS.equals(mode)) {
            intent.putExtra(ShareEditActivity.EXTRA_TYPE, ShareLink.TYPE_FOLDER);
        } else {
            intent.putExtra(ShareEditActivity.EXTRA_TYPE, ShareLink.TYPE_FILE);
        }
        startActivity(intent);
    }

    private class LinkAdapter extends RecyclerView.Adapter<LinkHolder> {

        @NonNull
        @Override
        public LinkHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_share_link, parent, false);
            return new LinkHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull LinkHolder holder, int position) {
            ShareLink link = links.get(position);
            holder.name.setText(displayName(link));
            holder.path.setText((ShareLink.TYPE_FOLDER.equals(link.type) ? "Folder · " : "File · ")
                    + link.rootPath);
            holder.meta.setText(meta(link));
            holder.status.setText(status(link));
            if (link.revoked || link.isExpired(System.currentTimeMillis())) {
                holder.status.setTextColor(holder.itemView.getContext().getColor(R.color.offline_red));
            } else if (link.isDownloadLimitReached()) {
                holder.status.setTextColor(holder.itemView.getContext().getColor(R.color.offline_red));
            } else {
                holder.status.setTextColor(holder.itemView.getContext().getColor(R.color.home_accent));
            }
            holder.itemView.setOnClickListener(v -> openEditor(link.id));
        }

        @Override
        public int getItemCount() {
            return links.size();
        }
    }

    private static class LinkHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView path;
        final TextView meta;
        final TextView status;

        LinkHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.link_name);
            path = itemView.findViewById(R.id.link_path);
            meta = itemView.findViewById(R.id.link_meta);
            status = itemView.findViewById(R.id.link_status);
        }
    }

    private String displayName(ShareLink link) {
        if (link.name != null && !link.name.trim().isEmpty()) return link.name;
        int slash = link.rootPath == null ? -1 : link.rootPath.lastIndexOf('/');
        return slash >= 0 ? link.rootPath.substring(slash + 1) : link.rootPath;
    }

    private String meta(ShareLink link) {
        String expiry = link.expiresAt <= 0 ? "No expiry" : "Expires "
                + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(link.expiresAt));
        String downloads = link.maxDownloads <= 0
                ? link.downloadCount + " downloads"
                : link.downloadCount + "/" + link.maxDownloads + " downloads";
        String permission = link.canDownload() ? "Download" : "View only";
        String password = link.isPasswordProtected() ? " · Password" : "";
        return expiry + " · " + downloads + " · " + permission + password;
    }

    private String status(ShareLink link) {
        long now = System.currentTimeMillis();
        if (link.revoked) return "REVOKED";
        if (link.isExpired(now)) return "EXPIRED";
        if (link.isDownloadLimitReached()) return "DOWNLOAD LIMIT REACHED";
        return "ACTIVE";
    }
}
