package com.yadaventerprise.phoneserver.ui.developer;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.ui.docs.DeveloperDocsActivity;
import com.yadaventerprise.phoneserver.ui.remote.RolesPermissionsActivity;
import com.yadaventerprise.phoneserver.ui.security.HttpsSetupActivity;

public class DeveloperSettingsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_simple);
        ((TextView)findViewById(R.id.title)).setText("Developer Settings");
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        LinearLayout actions = findViewById(R.id.action_bar);
        actions.addView(actionButton("API Docs", DeveloperDocsActivity.class));
        actions.addView(actionButton("HTTPS", HttpsSetupActivity.class));
        actions.addView(actionButton("Roles", RolesPermissionsActivity.class));
        actions.addView(actionButton("Tokens", DeveloperApiKeysActivity.class));
        ((TextView)findViewById(R.id.body)).setText(
                "Authentication\n"
                        + "- API keys are stored as hashes; plaintext is shown once.\n"
                        + "- Scope keys to a project when possible.\n"
                        + "- Viewer is read-only, editor can write, admin can manage security.\n"
                        + "- Permissions are checked server-side on every request.\n\n"
                        + "Quotas\n"
                        + "- Daily and monthly counters use UTC calendar windows.\n"
                        + "- Concurrent requests are reserved before work starts and released on completion or failure.\n"
                        + "- Set 0 for unlimited daily/monthly or default concurrency.\n\n"
                        + "Webhooks\n"
                        + "- Prefer HTTPS endpoints.\n"
                        + "- Signatures are HMAC-SHA256 over timestamp.deliveryId.body.\n"
                        + "- Secrets are write-only and are never returned by developer APIs.\n"
                        + "- Replay protection rejects old or repeated delivery ids.\n\n"
                        + "Extensions\n"
                        + "- Manifests describe metadata, permissions, and dependencies only.\n"
                        + "- Executable code fields are rejected.\n"
                        + "- Administrator approval is required before an extension can be enabled.\n\n"
                        + "Storage\n"
                        + "- Developer state uses JSON/file storage with atomic writes.\n"
                        + "- No SQL database or arbitrary executable extension code is used.\n"
                        + "- Use the sandbox screen to validate JSON and preview webhook payloads without production access.");
    }

    private Button actionButton(String label, Class<?> target) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.bg_button_outline);
        button.setTextColor(getColor(R.color.home_text_primary));
        button.setOnClickListener(v -> startActivity(new Intent(this, target)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = Math.round(6 * getResources().getDisplayMetrics().density);
        button.setLayoutParams(params);
        return button;
    }
}
