package com.yadaventerprise.phoneserver.ui.api;

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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.database.Records;
import com.yadaventerprise.phoneserver.data.security.TokenPolicy;
import com.yadaventerprise.phoneserver.data.security.TokenPolicyStore;
import com.yadaventerprise.phoneserver.data.storage.ProjectStore;
import com.yadaventerprise.phoneserver.data.tokens.TokenStore;
import com.yadaventerprise.phoneserver.network.http.DataException;
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

        adapter = new ApiTokensAdapter(tokens, new ApiTokensAdapter.Listener() {
            @Override
            public void onRevoke(Records.ApiTokenRecord token) {
                confirmRevoke(token);
            }

            @Override
            public void onEdit(Records.ApiTokenRecord token) {
                editPolicy(token);
            }

            @Override
            public void onRegenerate(Records.ApiTokenRecord token) {
                confirmRegenerate(token);
            }

            @Override
            public void onToggleSuspend(Records.ApiTokenRecord token) {
                toggleSuspend(token);
            }
        });
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

        TextView roleLabel = new TextView(this);
        roleLabel.setText("Role");
        roleLabel.setPadding(0, dp(10), 0, 0);
        form.addView(roleLabel);

        Spinner roleSpinner = new Spinner(this);
        ArrayAdapter<String> roleAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Viewer (read only)", "Editor (read & write)", "Admin (full access)"});
        roleSpinner.setAdapter(roleAdapter);
        roleSpinner.setSelection(1);
        form.addView(roleSpinner);

        EditText rateInput = new EditText(this);
        rateInput.setHint("Rate limit per minute (0 = global)");
        rateInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        rateInput.setSingleLine(true);
        form.addView(rateInput);

        EditText expiryInput = new EditText(this);
        expiryInput.setHint("Expires in days (0 = never)");
        expiryInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        expiryInput.setSingleLine(true);
        form.addView(expiryInput);

        EditText dailyQuotaInput = new EditText(this);
        dailyQuotaInput.setHint("Daily requests (0 = unlimited)");
        dailyQuotaInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        dailyQuotaInput.setSingleLine(true);
        form.addView(dailyQuotaInput);

        EditText monthlyQuotaInput = new EditText(this);
        monthlyQuotaInput.setHint("Monthly requests (0 = unlimited)");
        monthlyQuotaInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        monthlyQuotaInput.setSingleLine(true);
        form.addView(monthlyQuotaInput);

        EditText concurrentInput = new EditText(this);
        concurrentInput.setHint("Concurrent requests (0 = default)");
        concurrentInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        concurrentInput.setSingleLine(true);
        form.addView(concurrentInput);

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
            String role = roleForPosition(roleSpinner.getSelectedItemPosition());
            int rate = parseNonNegative(rateInput);
            int days = Math.min(parseNonNegative(expiryInput), 36500);
            long dailyQuota = parseNonNegativeLong(dailyQuotaInput);
            long monthlyQuota = parseNonNegativeLong(monthlyQuotaInput);
            int concurrent = parseNonNegative(concurrentInput);
            dialog.dismiss();
            createToken(name, projectId, role, rate, days, dailyQuota, monthlyQuota, concurrent);
        }));
        dialog.show();
    }

    private void createToken(String name, String projectId, String role, int rate, int days,
                             long dailyQuota, long monthlyQuota, int concurrent) {
        new Thread(() -> {
            String error = null;
            TokenStore.Created created = null;
            try {
                TokenPolicy policy = TokenPolicy.preset(role);
                policy.rateLimitPerMinute = Math.max(0, Math.min(6000, rate));
                policy.expiresAt = days > 0
                        ? System.currentTimeMillis() + days * 24L * 60L * 60L * 1000L : 0;
                created = TokenStore.create(this, name, projectId, policy.canWrite(),
                        policy.expiresAt, dailyQuota, monthlyQuota, concurrent);
                policy.tokenId = created.record.id;
                TokenPolicyStore.get(this).save(policy);
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

    private void editPolicy(Records.ApiTokenRecord token) {
        TokenPolicy policy = TokenPolicyStore.get(this).get(token.id, token);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, pad / 2, pad, 0);

        TextView roleLabel = new TextView(this);
        roleLabel.setText("Role");
        form.addView(roleLabel);

        Spinner roleSpinner = new Spinner(this);
        ArrayAdapter<String> roleAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Viewer (read only)", "Editor (read & write)", "Admin (full access)"});
        roleSpinner.setAdapter(roleAdapter);
        roleSpinner.setSelection(positionForRole(policy.role));
        form.addView(roleSpinner);

        EditText rateInput = new EditText(this);
        rateInput.setHint("Rate limit per minute (0 = global)");
        rateInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        rateInput.setSingleLine(true);
        rateInput.setText(String.valueOf(policy.rateLimitPerMinute));
        form.addView(rateInput);

        EditText expiryInput = new EditText(this);
        expiryInput.setHint("Expires in days (0 = never)");
        expiryInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        expiryInput.setSingleLine(true);
        long remainingDays = policy.expiresAt <= 0 ? 0
                : Math.max(0, (policy.expiresAt - System.currentTimeMillis()
                + 24L * 60L * 60L * 1000L - 1) / (24L * 60L * 60L * 1000L));
        expiryInput.setText(String.valueOf(remainingDays));
        form.addView(expiryInput);

        EditText dailyQuotaInput = new EditText(this);
        dailyQuotaInput.setHint("Daily requests (0 = unlimited)");
        dailyQuotaInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        dailyQuotaInput.setText(String.valueOf(token.dailyQuota));
        form.addView(dailyQuotaInput);

        EditText monthlyQuotaInput = new EditText(this);
        monthlyQuotaInput.setHint("Monthly requests (0 = unlimited)");
        monthlyQuotaInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        monthlyQuotaInput.setText(String.valueOf(token.monthlyQuota));
        form.addView(monthlyQuotaInput);

        EditText concurrentInput = new EditText(this);
        concurrentInput.setHint("Concurrent requests (0 = default)");
        concurrentInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        concurrentInput.setText(String.valueOf(token.concurrentLimit));
        form.addView(concurrentInput);

        new AlertDialog.Builder(this)
                .setTitle("Token policy · " + token.name)
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String role = roleForPosition(roleSpinner.getSelectedItemPosition());
                    int rate = Math.max(0, Math.min(6000, parseNonNegative(rateInput)));
                    int days = Math.min(parseNonNegative(expiryInput), 36500);
                    long dailyQuota = parseNonNegativeLong(dailyQuotaInput);
                    long monthlyQuota = parseNonNegativeLong(monthlyQuotaInput);
                    int concurrent = parseNonNegative(concurrentInput);
                    new Thread(() -> {
                        String failure = null;
                        try {
                            TokenStore.updateQuotas(this, token.id, dailyQuota, monthlyQuota, concurrent);
                            TokenPolicy updated = TokenPolicy.preset(role);
                            updated.tokenId = token.id;
                            updated.rateLimitPerMinute = rate;
                            updated.expiresAt = days > 0
                                    ? System.currentTimeMillis() + days * 24L * 60L * 60L * 1000L : 0;
                            TokenPolicyStore.get(this).save(updated);
                            TokenStore.setExpiresAt(this, token.id, updated.expiresAt);
                        } catch (DataException e) {
                            failure = e.getMessage();
                        }
                        final String error = failure;
                        handler.post(() -> {
                            if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                            else Toast.makeText(this, "Token policy updated", Toast.LENGTH_SHORT).show();
                            load();
                        });
                    }).start();
                })
                .show();
    }

    private static String roleForPosition(int position) {
        if (position == 2) return TokenPolicy.ROLE_ADMIN;
        if (position == 0) return TokenPolicy.ROLE_VIEWER;
        return TokenPolicy.ROLE_EDITOR;
    }

    private static int positionForRole(String role) {
        if (TokenPolicy.ROLE_ADMIN.equals(role)) return 2;
        if (TokenPolicy.ROLE_VIEWER.equals(role)) return 0;
        return 1;
    }

    private static int parseNonNegative(EditText input) {
        try {
            return Math.max(0, Integer.parseInt(input.getText().toString().trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseNonNegativeLong(EditText input) {
        try {
            return Math.max(0L, Long.parseLong(input.getText().toString().trim()));
        } catch (NumberFormatException e) {
            return 0L;
        }
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

    private void confirmRegenerate(Records.ApiTokenRecord token) {
        new AlertDialog.Builder(this)
                .setTitle("Rotate token '" + token.name + "'?")
                .setMessage("The current token stops working immediately. Update clients with the new value.")
                .setPositiveButton("Rotate", (d, w) -> regenerateToken(token))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void regenerateToken(Records.ApiTokenRecord token) {
        new Thread(() -> {
            String error = null;
            TokenStore.Created result = null;
            try {
                result = TokenStore.regenerate(this, token.id);
            } catch (DataException e) {
                error = e.getMessage();
            }
            final TokenStore.Created created = result;
            final String message = error;
            handler.post(() -> {
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                } else {
                    showTokenDialog(created.token);
                    load();
                }
            });
        }).start();
    }

    private void toggleSuspend(Records.ApiTokenRecord token) {
        boolean suspend = !token.suspended;
        new Thread(() -> {
            TokenStore.setSuspended(this, token.id, suspend);
            handler.post(() -> {
                Toast.makeText(this, suspend ? "Token suspended" : "Token resumed", Toast.LENGTH_SHORT).show();
                load();
            });
        }).start();
    }

    private void revokeToken(Records.ApiTokenRecord token) {
        new Thread(() -> {
            TokenStore.revoke(this, token.id);
            TokenPolicyStore.get(this).delete(token.id);
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
