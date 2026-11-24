package com.yadaventerprise.phoneserver.ui.developer;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.developer.ExtensionRecord;
import com.yadaventerprise.phoneserver.data.developer.ExtensionStore;
import com.yadaventerprise.phoneserver.util.TimeFormat;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public class DeveloperExtensionsActivity extends AppCompatActivity {
    private static final String STATE_EXTENSION = "selected_extension";
    private final List<ExtensionRecord> extensions = new ArrayList<>();
    private final List<JSONObject> activityLog = new ArrayList<>();
    private TextView body;
    private Spinner spinner;
    private String selectedId;

    private interface Action {
        void run() throws Exception;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_extensions);
        body = findViewById(R.id.body);
        spinner = findViewById(R.id.extension_spinner);
        if (savedInstanceState != null) selectedId = savedInstanceState.getString(STATE_EXTENSION);
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.install_button).setOnClickListener(v -> promptInstall());
        findViewById(R.id.approve_button).setOnClickListener(v -> runAction("approved", () ->
                ExtensionStore.get(this).approve(requiredId(), true, "developer-ui")));
        findViewById(R.id.revoke_button).setOnClickListener(v -> runAction("revoked", () ->
                ExtensionStore.get(this).approve(requiredId(), false, "developer-ui")));
        findViewById(R.id.enable_button).setOnClickListener(v -> runAction("enabled", () ->
                ExtensionStore.get(this).setEnabled(requiredId(), true, true, "developer-ui")));
        findViewById(R.id.disable_button).setOnClickListener(v -> runAction("disabled", () ->
                ExtensionStore.get(this).setEnabled(requiredId(), false, true, "developer-ui")));
        findViewById(R.id.remove_button).setOnClickListener(v -> confirmRemove());
        findViewById(R.id.activity_button).setOnClickListener(v -> refresh());
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= extensions.size()) return;
                selectedId = extensions.get(position).id;
                body.setText(render(extensions, activityLog));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_EXTENSION, selectedId);
    }

    private String requiredId() {
        ExtensionSelection selection = currentSelection();
        if (selection == null) throw new IllegalArgumentException("select an extension first");
        return selection.id;
    }

    private ExtensionSelection currentSelection() {
        int position = spinner.getSelectedItemPosition();
        if (position < 0 || position >= extensions.size()) return null;
        ExtensionRecord record = extensions.get(position);
        selectedId = record.id;
        return new ExtensionSelection(record.id, record.name);
    }

    private void runAction(String successLabel, Action action) {
        try {
            action.run();
            Toast.makeText(this, "Extension " + successLabel, Toast.LENGTH_SHORT).show();
            refresh();
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmRemove() {
        ExtensionSelection selection = currentSelection();
        if (selection == null) {
            Toast.makeText(this, "Select an extension first", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Remove extension '" + selection.name + "'?")
                .setMessage("The manifest and approval state are removed. Activity history is kept.")
                .setPositiveButton("Remove", (d, w) -> {
                    ExtensionStore.get(this).remove(selection.id, "developer-ui");
                    selectedId = null;
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refresh() {
        new Thread(() -> {
            final List<ExtensionRecord> loaded = ExtensionStore.get(this).list();
            final List<JSONObject> activity = ExtensionStore.get(this).activity(20);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                extensions.clear();
                extensions.addAll(loaded);
                activityLog.clear();
                activityLog.addAll(activity);
                List<String> labels = new ArrayList<>();
                int selection = 0;
                for (int i = 0; i < extensions.size(); i++) {
                    ExtensionRecord record = extensions.get(i);
                    labels.add(record.name + " v" + record.version + " · " + record.status);
                    if (record.id.equals(selectedId)) selection = i;
                }
                if (labels.isEmpty()) labels.add("No extensions installed");
                spinner.setAdapter(new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_dropdown_item, labels));
                spinner.setSelection(selection);
                body.setText(render(extensions, activityLog));
            });
        }).start();
    }

    private String render(List<ExtensionRecord> records, List<JSONObject> activity) {
        StringBuilder sb = new StringBuilder();
        sb.append("Installed extensions\n");
        if (records.isEmpty()) sb.append("- none\n");
        for (ExtensionRecord record : records) {
            sb.append("- ").append(record.name).append(" v").append(record.version)
                    .append(" · ").append(record.status).append('\n');
            sb.append("  id ").append(record.id).append(" · approved ")
                    .append(record.approved ? "yes" : "no").append(" · enabled ")
                    .append(record.enabled ? "yes" : "no").append('\n');
        }
        ExtensionRecord selected = null;
        for (ExtensionRecord record : records) {
            if (record.id.equals(selectedId)) {
                selected = record;
                break;
            }
        }
        sb.append("\nSelected extension\n");
        if (selected == null) {
            sb.append("- none\n");
        } else {
            sb.append("Name: ").append(selected.name).append('\n');
            sb.append("Version: ").append(selected.version).append('\n');
            sb.append("Author: ").append(selected.author.isEmpty() ? "-" : selected.author).append('\n');
            sb.append("Description: ").append(selected.description.isEmpty() ? "-" : selected.description).append('\n');
            sb.append("Permissions\n");
            if (selected.permissions.isEmpty()) sb.append("- none\n");
            else for (String permission : selected.permissions) sb.append("- ").append(permission).append('\n');
            sb.append("Dependencies\n");
            if (selected.dependencies.isEmpty()) sb.append("- none\n");
            else for (String dependency : selected.dependencies) sb.append("- ").append(dependency).append('\n');
            sb.append("Installed: ").append(TimeFormat.formatTime(selected.installedAt)).append('\n');
            sb.append("Updated: ").append(TimeFormat.formatTime(selected.updatedAt)).append('\n');
        }
        sb.append("\nActivity\n");
        if (activity.isEmpty()) sb.append("- none\n");
        for (JSONObject row : activity) {
            sb.append(TimeFormat.formatTime(row.optLong("time"))).append(" ")
                    .append(row.optString("action")).append(" · ")
                    .append(row.optString("extensionId")).append(" · ")
                    .append(row.optString("actor")).append('\n');
            String detail = row.optString("detail");
            if (!detail.isEmpty()) sb.append("  ").append(detail).append('\n');
        }
        return sb.toString();
    }

    private void promptInstall() {
        EditText input = new EditText(this);
        input.setHint("Paste extension manifest JSON");
        input.setMinLines(6);
        new AlertDialog.Builder(this)
                .setTitle("Install Extension")
                .setMessage("Manifests may declare metadata, permissions, and dependencies only. Executable code fields are rejected.")
                .setView(input)
                .setPositiveButton("Install", (d, w) -> {
                    String text = input.getText().toString();
                    new Thread(() -> {
                        try {
                            ExtensionRecord record = ExtensionStore.get(this)
                                    .install(new JSONObject(text), "developer-ui");
                            selectedId = record.id;
                            runOnUiThread(() -> {
                                Toast.makeText(this, "Extension installed and awaiting approval", Toast.LENGTH_SHORT).show();
                                refresh();
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static final class ExtensionSelection {
        final String id;
        final String name;

        ExtensionSelection(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
