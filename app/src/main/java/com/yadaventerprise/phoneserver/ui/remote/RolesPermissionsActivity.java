package com.yadaventerprise.phoneserver.ui.remote;

import android.os.Bundle;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.remote.RemotePermissions;
import com.yadaventerprise.phoneserver.data.remote.RemoteRole;
import java.util.Set;

public class RolesPermissionsActivity extends AppCompatActivity {

    private static final String[] ROLES = {
            RemoteRole.OWNER, RemoteRole.ADMINISTRATOR, RemoteRole.MANAGER,
            RemoteRole.VIEWER, RemoteRole.DEVICE
    };
    private static final String[] LABELS = {
            "Owner", "Administrator", "Manager", "Viewer", "Device"
    };
    private static final String[] DESCRIPTIONS = {
            "Full control of every remote feature; cannot be denied",
            "All permissions except the protected owner account",
            "Dashboard, device viewing and file management",
            "Read-only dashboard, devices and files",
            "Reporting device with dashboard, device and file access"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_roles_permissions);

        LinearLayout container = findViewById(R.id.roles_container);
        findViewById(R.id.back_button).setOnClickListener(v -> finish());

        for (int i = 0; i < ROLES.length; i++) {
            LinearLayout card = RemoteUi.card(this);
            card.addView(RemoteUi.row(this, R.drawable.ic_shield, LABELS[i], DESCRIPTIONS[i]));
            Set<String> permissions = RemotePermissions.forRole(ROLES[i]);
            card.addView(RemoteUi.detail(this, "Permissions (" + permissions.size() + "):"));
            if (permissions.isEmpty()) {
                card.addView(RemoteUi.detail(this, "None"));
            } else {
                for (String permission : permissions) {
                    card.addView(RemoteUi.detail(this, "• " + pretty(permission)));
                }
            }
            container.addView(card);
        }
    }

    private String pretty(String permission) {
        return permission.startsWith("remote.") ? permission.substring(7) : permission;
    }
}
