package com.yadaventerprise.phoneserver.ui.remote;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.remote.RemotePermissions;
import com.yadaventerprise.phoneserver.data.remote.RemoteRole;
import com.yadaventerprise.phoneserver.data.remote.RemoteSession;
import com.yadaventerprise.phoneserver.data.remote.RemoteSessionStore;
import com.yadaventerprise.phoneserver.data.remote.RemoteUser;
import com.yadaventerprise.phoneserver.data.remote.RemoteUserStore;
import java.util.Collections;
import java.util.List;

public class UserManagementActivity extends AppCompatActivity {

    private static final String[] ADD_ROLE_LABELS = {"Owner", "Administrator", "Manager", "Viewer"};
    private static final String[] ADD_ROLE_VALUES = {
            RemoteRole.OWNER, RemoteRole.ADMINISTRATOR, RemoteRole.MANAGER, RemoteRole.VIEWER
    };
    private static final String[] EDIT_ROLE_LABELS = {"Administrator", "Manager", "Viewer"};
    private static final String[] EDIT_ROLE_VALUES = {
            RemoteRole.ADMINISTRATOR, RemoteRole.MANAGER, RemoteRole.VIEWER
    };
    private static final String[] STATUS_LABELS = {"Active", "Suspended", "Revoked"};
    private static final String[] STATUS_VALUES = {
            RemoteUser.STATUS_ACTIVE, RemoteUser.STATUS_SUSPENDED, RemoteUser.STATUS_REVOKED
    };

    private LinearLayout usersContainer;
    private TextView emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_management);

        usersContainer = findViewById(R.id.users_container);
        emptyText = findViewById(R.id.users_empty);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.refresh_button).setOnClickListener(v -> load());
        findViewById(R.id.add_user_button).setOnClickListener(v -> showAddDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        new Thread(() -> {
            final List<RemoteUser> users = RemoteUserStore.get(getApplicationContext()).all();
            Collections.sort(users, (a, b) -> {
                if (a.isOwner() != b.isOwner()) return a.isOwner() ? -1 : 1;
                return a.username.compareToIgnoreCase(b.username);
            });
            runOnUiThread(() -> {
                if (isFinishing()) return;
                usersContainer.removeAllViews();
                emptyText.setVisibility(users.isEmpty()
                        ? android.view.View.VISIBLE : android.view.View.GONE);
                for (RemoteUser user : users) addUserCard(user);
            });
        }).start();
    }

    private void addUserCard(final RemoteUser user) {
        LinearLayout card = RemoteUi.card(this);
        String name = user.displayName.isEmpty() ? user.username : user.displayName;
        String roleLabel = roleLabel(user.role);
        card.addView(RemoteUi.row(this, R.drawable.ic_person,
                name, "@" + user.username + " · " + roleLabel + " · " + user.status));
        card.addView(RemoteUi.detail(this, "Permissions: "
                + RemotePermissions.forRole(user.role).size() + " from role"
                + (user.grantedPermissions.isEmpty() ? "" : ", "
                + user.grantedPermissions.size() + " granted")
                + (user.deniedPermissions.isEmpty() ? "" : ", "
                + user.deniedPermissions.size() + " denied")));
        if (user.isOwner()) {
            card.addView(RemoteUi.detail(this, "Owner account is protected. Owner permissions cannot be denied."));
        } else {
            card.addView(RemoteUi.detail(this, "Last login: " + RemoteUi.time(user.lastLoginAt)));
            LinearLayout actions = RemoteUi.actions(this);
            TextView edit = RemoteUi.action(this, "Edit");
            edit.setOnClickListener(v -> showEditDialog(user));
            actions.addView(edit);
            if (!RemoteUser.STATUS_SUSPENDED.equals(user.status)) {
                TextView suspend = RemoteUi.action(this, "Suspend");
                addSpaced(actions, suspend);
                suspend.setOnClickListener(v -> RemoteUi.confirm(this, "Suspend user?",
                        name + " will not be able to sign in until reactivated.",
                        "Suspend", () -> setStatus(user, RemoteUser.STATUS_SUSPENDED)));
            }
            if (!RemoteUser.STATUS_REVOKED.equals(user.status)) {
                TextView revoke = RemoteUi.action(this, "Revoke");
                addSpaced(actions, revoke);
                revoke.setOnClickListener(v -> RemoteUi.confirm(this, "Revoke user?",
                        name + " will lose access and all of their sessions will be revoked.",
                        "Revoke", () -> setStatus(user, RemoteUser.STATUS_REVOKED)));
            }
            TextView delete = RemoteUi.action(this, "Delete");
            addSpaced(actions, delete);
            delete.setOnClickListener(v -> RemoteUi.confirm(this, "Delete user?",
                    "Permanently remove " + name + " and revoke their sessions?",
                    "Delete", () -> {
                        RemoteUserStore.get(getApplicationContext()).delete(user.id);
                        revokeSessions(user.id);
                        Toast.makeText(this, "User deleted", Toast.LENGTH_SHORT).show();
                        load();
                    }));
            card.addView(actions);
        }
        usersContainer.addView(card);
    }

    private void addSpaced(LinearLayout parent, TextView view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(RemoteUi.dp(this, 8));
        view.setLayoutParams(params);
        parent.addView(view);
    }

    private void showAddDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(RemoteUi.dp(this, 20), RemoteUi.dp(this, 12),
                RemoteUi.dp(this, 20), RemoteUi.dp(this, 12));
        EditText username = RemoteUi.input(this, "Username", null, false);
        EditText displayName = RemoteUi.input(this, "Display name", null, false);
        EditText password = RemoteUi.input(this, "Password (10+ characters)", null, true);
        Spinner role = spinner(ADD_ROLE_LABELS, 3);
        form.addView(username);
        form.addView(displayName);
        form.addView(password);
        form.addView(role);

        new AlertDialog.Builder(this)
                .setTitle("Add user")
                .setView(form)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = username.getText().toString().trim();
                    if (!RemoteUser.isValidUsername(name)) {
                        username.setError("Use 3–32 letters, digits, dot, dash or underscore");
                        return;
                    }
                    String normalized = RemoteUser.normalizeUsername(name);
                    if (RemoteUserStore.get(getApplicationContext()).findByUsername(normalized) != null) {
                        username.setError("Username already exists");
                        return;
                    }
                    if (!validPassword(password)) return;
                    RemoteUser draft = new RemoteUser();
                    draft.username = normalized;
                    draft.displayName = displayName.getText().toString().trim();
                    if (draft.displayName.isEmpty()) draft.displayName = normalized;
                    draft.role = ADD_ROLE_VALUES[role.getSelectedItemPosition()];
                    RemoteUser created = RemoteUserStore.get(getApplicationContext())
                            .create(draft, password.getText().toString());
                    Toast.makeText(this, created == null
                            ? "Could not create user" : "User created", Toast.LENGTH_SHORT).show();
                    load();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditDialog(final RemoteUser user) {
        if (user.isOwner()) {
            Toast.makeText(this, "The owner account is protected", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(RemoteUi.dp(this, 20), RemoteUi.dp(this, 12),
                RemoteUi.dp(this, 20), RemoteUi.dp(this, 12));
        EditText displayName = RemoteUi.input(this, "Display name", user.displayName, false);
        EditText password = RemoteUi.input(this, "New password (blank keeps current)", null, true);
        int roleIndex = indexOf(EDIT_ROLE_VALUES, user.role);
        int statusIndex = indexOf(STATUS_VALUES, user.status);
        Spinner role = spinner(EDIT_ROLE_LABELS, Math.max(0, roleIndex));
        Spinner status = spinner(STATUS_LABELS, Math.max(0, statusIndex));
        form.addView(displayName);
        form.addView(role);
        form.addView(status);
        form.addView(password);

        new AlertDialog.Builder(this)
                .setTitle("Edit user")
                .setView(form)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newPassword = password.getText().toString();
                    if (!newPassword.isEmpty() && newPassword.length() < 10) {
                        password.setError("Password must be at least 10 characters");
                        return;
                    }
                    user.displayName = displayName.getText().toString().trim();
                    if (user.displayName.isEmpty()) user.displayName = user.username;
                    user.role = EDIT_ROLE_VALUES[role.getSelectedItemPosition()];
                    user.status = STATUS_VALUES[status.getSelectedItemPosition()];
                    RemoteUserStore.get(getApplicationContext()).save(user);
                    if (!RemoteUser.STATUS_ACTIVE.equals(user.status) || !newPassword.isEmpty()) {
                        revokeSessions(user.id);
                    }
                    Toast.makeText(this, "User saved", Toast.LENGTH_SHORT).show();
                    load();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setStatus(RemoteUser user, String status) {
        if (user.isOwner()) {
            Toast.makeText(this, "The owner account is protected", Toast.LENGTH_SHORT).show();
            return;
        }
        user.status = status;
        RemoteUserStore.get(getApplicationContext()).save(user);
        revokeSessions(user.id);
        Toast.makeText(this, "User " + status, Toast.LENGTH_SHORT).show();
        load();
    }

    private void revokeSessions(String userId) {
        RemoteSessionStore.get(getApplicationContext())
                .revokeSubject(RemoteSession.SUBJECT_USER, userId);
    }

    private Spinner spinner(String[] labels, int selection) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selection);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = RemoteUi.dp(this, 8);
        spinner.setLayoutParams(params);
        return spinner;
    }

    private boolean validPassword(EditText input) {
        String password = input.getText().toString();
        if (password.length() < 10) {
            input.setError("Password must be at least 10 characters");
            return false;
        }
        return true;
    }

    private int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) return i;
        }
        return -1;
    }

    private String roleLabel(String role) {
        if (RemoteRole.OWNER.equals(role)) return "Owner";
        if (RemoteRole.ADMINISTRATOR.equals(role)) return "Administrator";
        if (RemoteRole.MANAGER.equals(role)) return "Manager";
        if (RemoteRole.VIEWER.equals(role)) return "Viewer";
        return "Device";
    }
}
