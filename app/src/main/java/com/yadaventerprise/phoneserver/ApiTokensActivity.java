package com.yadaventerprise.phoneserver;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yadaventerprise.phoneserver.data.DataException;
import com.yadaventerprise.phoneserver.data.ProjectStore;
import com.yadaventerprise.phoneserver.data.Records;
import com.yadaventerprise.phoneserver.data.TokenStore;

import java.util.ArrayList;
import java.util.List;

public class ApiTokensActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView emptyText;
    private ApiTokensAdapter adapter;
    private final List<Records.ApiTokenRecord> tokens = new ArrayList<>();
    private final List<Records.ProjectRecord> projects = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_api_tokens);

        recyclerView = findViewById(R.id.tokens_recycler);
        emptyText = findViewById(R.id.empty_text);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new ApiTokensAdapter(tokens, this::confirmRevoke);
        recyclerView.setAdapter(adapter);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.fab_add).setOnClickListener(v -> prepareCreateDialog());

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        new Thread(() -> {
            List<Records.ApiTokenRecord> loaded = TokenStore.list(this);
            List<Records.ProjectRecord> loadedProjects = ProjectStore.list(this);
            handler.post(() -> {
                tokens.clear();
                tokens.addAll(loaded);
                projects.clear();
                projects.addAll(loadedProjects);
                adapter.notifyDataSetChanged();
                emptyText.setVisibility(tokens.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private void prepareCreateDialog() {
        final List<String> scopeLabels = new ArrayList<>();
        scopeLabels.add("All projects (unscoped)");
        for (Records.ProjectRecord project : projects) {
            scopeLabels.add(project.name);
        }

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        form.setPadding(pad, pad / 2, pad, 0);

        EditText nameInput = new EditText(this);
        nameInput.setHint("Token name (required)");
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        nameInput.setSingleLine(true);
        form.addView(nameInput);

        TextView scopeLabel = new TextView(this);
        scopeLabel.setText("Scope");
        scopeLabel.setPadding(0, dp(10), 0, 0);
        form.addView(scopeLabel);

        Spinner scopeSpinner = new Spinner(this);
        ArrayAdapter<String> scopeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, scopeLabels);
        scopeSpinner.setAdapter(scopeAdapter);
        form.addView(scopeSpinner);

        CheckBox canWriteCheck = new CheckBox(this);
        canWriteCheck.setText("Allow writes (read & write)");
        canWriteCheck.setChecked(true);
        canWriteCheck.setPadding(0, dp(10), 0, 0);
        form.addView(canWriteCheck);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New API token")
                .setView(form)
                .setPositiveButton("Create", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Name is required", Toast.LENGTH_SHORT).show();
                return;
            }
            int position = scopeSpinner.getSelectedItemPosition();
            String projectId = null;
            if (position > 0 && position - 1 < projects.size()) {
                projectId = projects.get(position - 1).id;
            }
            boolean canWrite = canWriteCheck.isChecked();
            dialog.dismiss();
            createToken(name, projectId, canWrite);
        }));
        dialog.show();
    }

    private void createToken(String name, String projectId, boolean canWrite) {
        new Thread(() -> {
            String error = null;
            TokenStore.Created created = null;
            try {
                created = TokenStore.create(this, name, projectId, canWrite);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final TokenStore.Created result = created;
            final String message = error;
            handler.post(() -> {
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                } else {
                    showTokenDialog(result.token);
                    load();
                }
            });
        }).start();
    }

    private void showTokenDialog(String token) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(16), dp(20), dp(4));

        TextView warning = new TextView(this);
        warning.setText("Copy this token now. It cannot be shown again.");
        warning.setTextSize(13);
        layout.addView(warning);

        TextView tokenView = new TextView(this);
        tokenView.setText(token);
        tokenView.setTextIsSelectable(true);
        tokenView.setPadding(dp(12), dp(12), dp(12), dp(12));
        tokenView.setBackgroundResource(R.drawable.bg_input_outline);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        tokenView.setLayoutParams(params);
        layout.addView(tokenView);

        new AlertDialog.Builder(this)
                .setTitle("API token created")
                .setView(layout)
                .setPositiveButton("Copy", (d, w) -> {
                    copyToClipboard(token);
                    Toast.makeText(this, "Token copied", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void copyToClipboard(String token) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("PhoneServer API token", token));
        }
    }

    private void confirmRevoke(Records.ApiTokenRecord token) {
        new AlertDialog.Builder(this)
                .setTitle("Revoke token '" + token.name + "'?")
                .setMessage("Any client using this token will immediately lose access. This cannot be undone.")
                .setPositiveButton("Revoke", (d, w) -> revokeToken(token))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void revokeToken(Records.ApiTokenRecord token) {
        new Thread(() -> {
            TokenStore.revoke(this, token.id);
            handler.post(() -> {
                Toast.makeText(this, "Token revoked", Toast.LENGTH_SHORT).show();
                load();
            });
        }).start();
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value);
    }
}
