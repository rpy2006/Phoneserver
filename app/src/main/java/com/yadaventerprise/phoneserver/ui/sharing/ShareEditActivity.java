package com.yadaventerprise.phoneserver.ui.sharing;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.sharing.ShareAccessLogStore;
import com.yadaventerprise.phoneserver.data.sharing.ShareLink;
import com.yadaventerprise.phoneserver.data.sharing.ShareSettings;
import com.yadaventerprise.phoneserver.data.sharing.ShareSettingsStore;
import com.yadaventerprise.phoneserver.data.sharing.ShareStore;
import com.yadaventerprise.phoneserver.data.sharing.ShareUrlBuilder;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookDispatcher;
import com.yadaventerprise.phoneserver.security.PathGuard;
import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import org.json.JSONObject;

public class ShareEditActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "share_id";
    public static final String EXTRA_TYPE = "share_type";
    public static final String EXTRA_PATH = "share_path";

    private static final String STATE_ID = "share_edit_id";
    private static final String STATE_CREATING = "share_edit_creating";
    private static final String STATE_URL = "share_edit_url";
    private static final String STATE_WARNING = "share_edit_warning";
    private static final String STATE_PATH = "share_edit_path";

    private static final String[] TYPE_LABELS = {"File", "Folder"};
    private static final String[] TYPE_VALUES = {ShareLink.TYPE_FILE, ShareLink.TYPE_FOLDER};
    private static final String[] PERMISSION_LABELS = {"View only", "View and download"};
    private static final String[] PERMISSION_VALUES = {
            ShareLink.PERMISSION_VIEW, ShareLink.PERMISSION_DOWNLOAD};
    private static final String[] EXPIRY_LABELS = {
            "1 hour", "24 hours", "7 days", "30 days", "Never", "Custom hours"};
    private static final int[] EXPIRY_MINUTES = {60, 1440, 10080, 43200, 0, -1};
    private static final int EXPIRY_CUSTOM = 5;

    private Spinner typeSpinner;
    private Spinner permissionSpinner;
    private Spinner expirySpinner;
    private EditText nameInput;
    private EditText customHoursInput;
    private EditText maxDownloadsInput;
    private EditText passwordInput;
    private CheckBox removePassword;
    private TextView pathValue;
    private TextView statusText;
    private TextView shareUrl;
    private TextView shareWarning;
    private TextView tokenNote;
    private TextView screenTitle;
    private View shareSection;
    private View rotateButton;
    private Button revokeButton;
    private View deleteButton;
    private View copyButton;
    private View qrButton;

    private ActivityResultLauncher<Intent> pathPicker;
    private String id;
    private String selectedPath = "";
    private String currentUrl = "";
    private String currentWarning = "";
    private ShareLink currentLink;
    private boolean creating = true;
    private boolean requirePasswordByDefault;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share_edit);

        nameInput = findViewById(R.id.input_name);
        customHoursInput = findViewById(R.id.input_custom_hours);
        maxDownloadsInput = findViewById(R.id.input_max_downloads);
        passwordInput = findViewById(R.id.input_password);
        removePassword = findViewById(R.id.remove_password);
        pathValue = findViewById(R.id.path_value);
        statusText = findViewById(R.id.status_text);
        shareUrl = findViewById(R.id.share_url);
        shareWarning = findViewById(R.id.share_warning);
        tokenNote = findViewById(R.id.token_note);
        screenTitle = findViewById(R.id.screen_title);
        shareSection = findViewById(R.id.share_section);
        rotateButton = findViewById(R.id.rotate_button);
        revokeButton = findViewById(R.id.revoke_button);
        deleteButton = findViewById(R.id.delete_button);
        copyButton = findViewById(R.id.copy_button);
        qrButton = findViewById(R.id.qr_button);
        typeSpinner = findViewById(R.id.type_spinner);
        permissionSpinner = findViewById(R.id.permission_spinner);
        expirySpinner = findViewById(R.id.expiry_spinner);

        setupSpinner(typeSpinner, TYPE_LABELS);
        setupSpinner(permissionSpinner, PERMISSION_LABELS);
        setupSpinner(expirySpinner, EXPIRY_LABELS);
        expirySpinner.setOnItemSelectedListener(new AdapterViewListener());

        pathPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    String path = result.getData().getStringExtra(
                            SharePathPickerActivity.RESULT_PATH);
                    String type = result.getData().getStringExtra(
                            SharePathPickerActivity.RESULT_TYPE);
                    if (path == null || path.isEmpty()) return;
                    selectedPath = path;
                    pathValue.setText(path);
                    if (type != null) {
                        typeSpinner.setSelection(indexOf(TYPE_VALUES, type));
                    }
                    if (nameInput.getText().toString().trim().isEmpty()) {
                        nameInput.setText(baseName(path));
                    }
                });

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.browse_button).setOnClickListener(v -> browse());
        findViewById(R.id.save_button).setOnClickListener(v -> save());
        copyButton.setOnClickListener(v -> copyUrl());
        qrButton.setOnClickListener(v -> showQr());
        findViewById(R.id.history_button).setOnClickListener(v -> openHistory());
        rotateButton.setOnClickListener(v -> confirmRotate());
        revokeButton.setOnClickListener(v -> confirmRevoke());
        deleteButton.setOnClickListener(v -> confirmDelete());

        id = getIntent().getStringExtra(EXTRA_ID);
        String savedPath = null;
        if (savedInstanceState != null) {
            savedPath = savedInstanceState.getString(STATE_PATH, "");
            String savedId = savedInstanceState.getString(STATE_ID);
            if ((id == null || id.isEmpty()) && savedId != null && !savedId.isEmpty()) {
                id = savedId;
                creating = savedInstanceState.getBoolean(STATE_CREATING, false);
            }
            currentUrl = savedInstanceState.getString(STATE_URL, "");
            currentWarning = savedInstanceState.getString(STATE_WARNING, "");
        }
        if (id != null && !id.isEmpty()) {
            creating = false;
            currentLink = ShareStore.get(this).get(id);
            if (currentLink == null) {
                Toast.makeText(this, "Share link not found", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            loadLink(currentLink);
            if (!currentUrl.isEmpty()) {
                showIssuedLink(currentUrl, currentWarning, currentLink);
            }
        } else {
            id = null;
            loadDefaults();
            if (savedPath != null) {
                selectedPath = savedPath;
                pathValue.setText(savedPath);
            }
        }
        updateManageVisibility();
    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_ID, id);
        outState.putBoolean(STATE_CREATING, creating);
        outState.putString(STATE_URL, currentUrl);
        outState.putString(STATE_WARNING, currentWarning);
        outState.putString(STATE_PATH, selectedPath);
    }

    private void setupSpinner(Spinner spinner, String[] labels) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private class AdapterViewListener implements android.widget.AdapterView.OnItemSelectedListener {
        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                   int position, long itemId) {
            customHoursInput.setVisibility(position == EXPIRY_CUSTOM ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }

    private void loadDefaults() {
        ShareSettings settings = ShareSettingsStore.get(this).get();
        requirePasswordByDefault = settings.requirePasswordByDefault;
        permissionSpinner.setSelection(indexOf(PERMISSION_VALUES, settings.defaultPermission));
        typeSpinner.setSelection(indexOf(TYPE_VALUES,
                ShareLink.TYPE_FOLDER.equals(getIntent().getStringExtra(EXTRA_TYPE))
                        ? ShareLink.TYPE_FOLDER : ShareLink.TYPE_FILE));
        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path != null && !path.isEmpty()) {
            selectedPath = path;
            pathValue.setText(path);
            nameInput.setText(baseName(path));
        }
        maxDownloadsInput.setText(settings.defaultMaxDownloads > 0
                ? String.valueOf(settings.defaultMaxDownloads) : "");

        int expiry = settings.defaultExpiryMinutes;
        int preset = indexOf(EXPIRY_MINUTES, expiry);
        if (preset >= 0) {
            expirySpinner.setSelection(preset);
        } else {
            expirySpinner.setSelection(EXPIRY_CUSTOM);
            customHoursInput.setText(hoursText(expiry));
        }
        customHoursInput.setVisibility(expirySpinner.getSelectedItemPosition() == EXPIRY_CUSTOM
                ? View.VISIBLE : View.GONE);
        if (requirePasswordByDefault) {
            passwordInput.setHint("Password required by default");
            statusText.setText("This server requires a password for new share links. "
                    + "The password is stored as a salted hash.");
        } else {
            passwordInput.setHint("Optional password");
        }
        screenTitle.setText("New Share");
        shareSection.setVisibility(View.GONE);
        rotateButton.setVisibility(View.GONE);
        revokeButton.setVisibility(View.GONE);
        deleteButton.setVisibility(View.GONE);
    }

    private void loadLink(ShareLink link) {
        screenTitle.setText("Edit Share");
        selectedPath = link.rootPath;
        pathValue.setText(link.rootPath);
        nameInput.setText(link.name);
        typeSpinner.setSelection(indexOf(TYPE_VALUES, link.type));
        permissionSpinner.setSelection(indexOf(PERMISSION_VALUES, link.permission));
        maxDownloadsInput.setText(link.maxDownloads > 0
                ? String.valueOf(link.maxDownloads) : "");

        int duration = expiryDurationMinutes(link);
        int preset = indexOf(EXPIRY_MINUTES, duration);
        if (preset >= 0) {
            expirySpinner.setSelection(preset);
        } else {
            expirySpinner.setSelection(EXPIRY_CUSTOM);
            customHoursInput.setText(hoursText(duration));
        }
        customHoursInput.setVisibility(expirySpinner.getSelectedItemPosition() == EXPIRY_CUSTOM
                ? View.VISIBLE : View.GONE);

        if (link.isPasswordProtected()) {
            passwordInput.setHint("Leave blank to keep the current password");
            removePassword.setVisibility(View.VISIBLE);
        } else {
            passwordInput.setHint("Optional password");
            removePassword.setVisibility(View.GONE);
        }
        removePassword.setChecked(false);

        shareSection.setVisibility(View.VISIBLE);
        shareUrl.setText("The original URL is not stored. Rotate the link secret to issue a new URL.");
        shareWarning.setVisibility(View.GONE);
        tokenNote.setText("Only the secret's hash is stored, so the existing URL cannot be shown again. "
                + "Rotating immediately invalidates the old URL.");
        copyButton.setEnabled(false);
        qrButton.setEnabled(false);
        statusText.setText(linkStatus(link));
        updateManageVisibility();
    }

    private void updateManageVisibility() {
        boolean editing = !creating && currentLink != null;
        int manageVisibility = editing ? View.VISIBLE : View.GONE;
        rotateButton.setVisibility(manageVisibility);
        revokeButton.setVisibility(manageVisibility);
        deleteButton.setVisibility(manageVisibility);
        if (editing) {
            revokeButton.setText(currentLink.revoked ? "Restore access" : "Revoke access");
            rotateButton.setEnabled(!currentLink.revoked && !currentLink.isExpired(
                    System.currentTimeMillis()));
            rotateButton.setAlpha(rotateButton.isEnabled() ? 1f : 0.5f);
        }
    }

    private void browse() {
        String type = TYPE_VALUES[typeSpinner.getSelectedItemPosition()];
        Intent intent = new Intent(this, SharePathPickerActivity.class);
        intent.putExtra(SharePathPickerActivity.EXTRA_TYPE, type);
        pathPicker.launch(intent);
    }

    private void save() {
        String path = PathGuard.normalizeRelative(selectedPath);
        if (path == null || path.isEmpty()) {
            Toast.makeText(this, "Choose a file or folder to share", Toast.LENGTH_SHORT).show();
            return;
        }
        for (String segment : path.split("/")) {
            if (PathGuard.isHidden(segment)) {
                Toast.makeText(this, "Hidden paths cannot be shared", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        File target = ContentStore.resolveSafePath(this, path);
        if (target == null || !target.exists() || !target.canRead()) {
            Toast.makeText(this, "That file or folder is not available", Toast.LENGTH_SHORT).show();
            return;
        }

        String type = TYPE_VALUES[typeSpinner.getSelectedItemPosition()];
        if (ShareLink.TYPE_FOLDER.equals(type) && !target.isDirectory()) {
            Toast.makeText(this, "Select a folder for a folder share", Toast.LENGTH_LONG).show();
            return;
        }
        if (ShareLink.TYPE_FILE.equals(type) && !target.isFile()) {
            Toast.makeText(this, "Select a file for a file share", Toast.LENGTH_LONG).show();
            return;
        }

        long expiresAt;
        int expiryPosition = expirySpinner.getSelectedItemPosition();
        if (expiryPosition == EXPIRY_CUSTOM) {
            Double hours = parseDecimal(customHoursInput.getText().toString());
            if (hours == null || hours <= 0) {
                Toast.makeText(this, "Enter a valid number of hours", Toast.LENGTH_SHORT).show();
                return;
            }
            expiresAt = System.currentTimeMillis() + Math.max(1,
                    Math.round(hours * 60)) * 60000L;
        } else {
            int minutes = EXPIRY_MINUTES[expiryPosition];
            expiresAt = minutes == 0 ? 0 : System.currentTimeMillis() + minutes * 60000L;
        }

        Integer maxDownloads = parseMaxDownloads(maxDownloadsInput.getText().toString());
        if (maxDownloads == null) {
            Toast.makeText(this, "Enter 0 or a positive whole number for downloads",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String password = passwordInput.getText().toString();
        if (creating) {
            if (password.isEmpty() && requirePasswordByDefault) {
                Toast.makeText(this, "Enter a password for this link", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!password.isEmpty() && password.length() < 4) {
                Toast.makeText(this, "Use at least 4 characters for the password",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        } else if (password.length() > 0 && password.length() < 4) {
            Toast.makeText(this, "Use at least 4 characters for the password",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) name = baseName(path);
        ShareLink draft = currentLink == null ? new ShareLink() : currentLink.copy();
        draft.name = name;
        draft.rootPath = path;
        draft.type = type;
        draft.permission = PERMISSION_VALUES[permissionSpinner.getSelectedItemPosition()];
        draft.expiresAt = expiresAt;
        draft.maxDownloads = maxDownloads;

        if (creating) {
            String passwordToSet = password.isEmpty() ? null : password;
            ShareStore.Created created = ShareStore.get(this).create(draft, passwordToSet);
            if (created == null) {
                Toast.makeText(this, "Could not create the share link", Toast.LENGTH_SHORT).show();
                return;
            }
            id = created.link.id;
            currentLink = created.link;
            creating = false;
            ShareAccessLogStore.get(this).record("created", created.link, 200, "", "", "", "");
            JSONObject hookData = new JSONObject();
            try {
                hookData.put("shareId", created.link.id);
                hookData.put("name", created.link.name);
                hookData.put("type", created.link.type);
                hookData.put("permission", created.link.permission);
            } catch (Exception ignored) {
            }
            WebhookDispatcher.emit(this, WebhookDispatcher.EVENT_SHARE_CREATED, hookData);
            screenTitle.setText("Edit Share");

            passwordInput.setText("");
            removePassword.setVisibility(created.link.isPasswordProtected()
                    ? View.VISIBLE : View.GONE);
            showIssuedLink(ShareUrlBuilder.build(this, created.token), created.link);
            updateManageVisibility();
            Toast.makeText(this, "Share link created", Toast.LENGTH_SHORT).show();
        } else {
            boolean clear = removePassword.isChecked();
            String newPassword = password.isEmpty() ? null : password;
            ShareLink updated = ShareStore.get(this).update(id, draft, newPassword, clear);
            if (updated == null) {
                Toast.makeText(this, "Could not save the share link", Toast.LENGTH_SHORT).show();
                return;
            }
            ShareAccessLogStore.get(this).record("updated", updated, 200, "", "", "", "");
            Toast.makeText(this, "Share link saved", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void showIssuedLink(ShareUrlBuilder.Result result, ShareLink link) {
        showIssuedLink(result.url, result.warning, link);
    }

    private void showIssuedLink(String url, String warning, ShareLink link) {
        currentUrl = url;
        currentWarning = warning == null ? "" : warning;
        shareSection.setVisibility(View.VISIBLE);
        shareUrl.setText(currentUrl);
        tokenNote.setText("This URL is shown only now. Copy or scan it before leaving this screen. "
                + "Only a hash of the secret is stored, so it cannot be recovered later.");
        copyButton.setEnabled(true);
        qrButton.setEnabled(true);
        if (currentWarning.isEmpty()) {
            shareWarning.setVisibility(View.GONE);
        } else {
            shareWarning.setText(currentWarning);
            shareWarning.setVisibility(View.VISIBLE);
        }
        statusText.setText(linkStatus(link));
        updateManageVisibility();
    }

    private void copyUrl() {
        if (currentUrl.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Share link", currentUrl));
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show();
    }

    private void showQr() {
        if (currentUrl.isEmpty() || currentLink == null) return;
        Intent intent = new Intent(this, ShareQrActivity.class);
        intent.putExtra(ShareQrActivity.EXTRA_URL, currentUrl);
        intent.putExtra(ShareQrActivity.EXTRA_TITLE, currentLink.name);
        intent.putExtra(ShareQrActivity.EXTRA_WARNING, currentWarning);
        startActivity(intent);
    }

    private void openHistory() {
        if (currentLink == null) return;
        Intent intent = new Intent(this, ShareAccessHistoryActivity.class);
        intent.putExtra(ShareAccessHistoryActivity.EXTRA_LINK_ID, currentLink.id);
        intent.putExtra(ShareAccessHistoryActivity.EXTRA_LINK_NAME, currentLink.name);
        startActivity(intent);
    }

    private void confirmRotate() {
        if (currentLink == null) return;
        if (currentLink.isExpired(System.currentTimeMillis())) {
            Toast.makeText(this, "Update the expiry before rotating this link",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Rotate link secret?")
                .setMessage("The current URL will stop working immediately. "
                        + "Download counts for the link will be reset.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Rotate", (dialog, which) -> rotate())
                .show();
    }

    private void rotate() {
        ShareStore.Created created = ShareStore.get(this).rotateToken(id);
        if (created == null) {
            Toast.makeText(this, "Could not rotate the link", Toast.LENGTH_SHORT).show();
            return;
        }
        currentLink = created.link;
        ShareAccessLogStore.get(this).record("rotated", created.link, 200, "", "", "", "");
        passwordInput.setText("");
        showIssuedLink(ShareUrlBuilder.build(this, created.token), created.link);
        Toast.makeText(this, "New share link issued", Toast.LENGTH_SHORT).show();
    }

    private void confirmRevoke() {
        if (currentLink == null) return;
        boolean revoke = !currentLink.revoked;
        new AlertDialog.Builder(this)
                .setTitle(revoke ? "Revoke access?" : "Restore access?")
                .setMessage(revoke
                        ? "The link will stop working until you restore it."
                        : "The link will work again if it has not expired.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(revoke ? "Revoke" : "Restore", (dialog, which) -> {
                    ShareLink updated = ShareStore.get(this).setRevoked(id, revoke);
                    if (updated == null) {
                        Toast.makeText(this, "Could not update the link", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    currentLink = updated;
                    ShareAccessLogStore.get(this).record(revoke ? "revoked" : "restored",
                            updated, 200, "", "", "", "");
                    statusText.setText(linkStatus(updated));
                    updateManageVisibility();
                })
                .show();
    }

    private void confirmDelete() {
        if (currentLink == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete share link?")
                .setMessage("The link will be removed permanently. Access history is kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    ShareAccessLogStore.get(this).record("deleted", currentLink, 200,
                            "", "", "", "");
                    if (ShareStore.get(this).delete(id)) {
                        Toast.makeText(this, "Share link deleted", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, "Could not delete the link", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private String linkStatus(ShareLink link) {
        long now = System.currentTimeMillis();
        String status;
        if (link.revoked) {
            status = "Access is revoked";
        } else if (link.isExpired(now)) {
            status = "Expired " + dateText(link.expiresAt);
        } else if (link.isDownloadLimitReached()) {
            status = "Download limit reached";
        } else if (link.expiresAt > 0) {
            status = "Active until " + dateText(link.expiresAt);
        } else {
            status = "Active with no expiry";
        }
        status = status + " · " + link.downloadCount
                + (link.maxDownloads > 0 ? "/" + link.maxDownloads : "")
                + " downloads · " + (link.canDownload() ? "download allowed" : "view only");
        if (link.lastAccessAt > 0) {
            status = status + " · Last access " + dateText(link.lastAccessAt);
        }
        return status;
    }

    private String dateText(long time) {
        if (time <= 0) return "never";
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(time));
    }

    private int expiryDurationMinutes(ShareLink link) {
        if (link.expiresAt <= 0) return 0;
        long duration = link.expiresAt - System.currentTimeMillis();
        if (duration <= 0) return -2;
        return Math.max(1, (int) Math.round(duration / 60000.0));
    }

    private String hoursText(int minutes) {
        if (minutes <= 0) return "";
        if (minutes % 60 == 0) return String.valueOf(minutes / 60);
        return String.format(java.util.Locale.US, "%.2f", minutes / 60.0);
    }

    private String baseName(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private Double parseDecimal(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseMaxDownloads(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return 0;
        try {
            int parsed = Integer.parseInt(text);
            return parsed < 0 ? null : parsed;
        } catch (Exception e) {
            return null;
        }
    }

    private int indexOf(String[] values, String value) {
        if (value != null) {
            for (int i = 0; i < values.length; i++) {
                if (value.equals(values[i])) return i;
            }
        }
        return 0;
    }

    private int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (value == values[i]) return i;
        }
        return -1;
    }
}
