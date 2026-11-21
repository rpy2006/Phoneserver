package com.yadaventerprise.phoneserver.ui.developer;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.developer.ApiUsageStore;
import com.yadaventerprise.phoneserver.data.developer.ExtensionStore;
import com.yadaventerprise.phoneserver.data.tokens.TokenStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookStore;
import org.json.JSONObject;

public class DeveloperPlatformActivity extends AppCompatActivity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_platform);
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        bind(R.id.row_overview, DeveloperOverviewActivity.class);
        bind(R.id.row_api_keys, DeveloperApiKeysActivity.class);
        bind(R.id.row_usage, DeveloperUsageActivity.class);
        bind(R.id.row_sdk, DeveloperSdkActivity.class);
        bind(R.id.row_webhooks, DeveloperWebhookToolsActivity.class);
        bind(R.id.row_extensions, DeveloperExtensionsActivity.class);
        bind(R.id.row_sandbox, DeveloperSandboxActivity.class);
        bind(R.id.row_settings, DeveloperSettingsActivity.class);
        refresh();
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void bind(int id, Class<?> cls) {
        findViewById(id).setOnClickListener(v -> startActivity(new Intent(this, cls)));
    }

    private void refresh() {
        new Thread(() -> {
            int keys = 0, revoked = 0, suspended = 0;
            for (com.yadaventerprise.phoneserver.data.database.Records.ApiTokenRecord t : TokenStore.list(this)) {
                if (t.revoked) revoked++; else if (t.suspended) suspended++; else keys++;
            }
            int hooks = WebhookStore.get(this).all().size();
            int ext = ExtensionStore.get(this).list().size();
            JSONObject usage = ApiUsageStore.get(this).summary(System.currentTimeMillis() - 24L * 60L * 60L * 1000L);
            int total = usage.optInt("totalRequests", 0);
            int failed = usage.optInt("failedRequests", 0);
            int rl = usage.optInt("rateLimitViolations", 0);
            final String sub = keys + " active keys · " + hooks + " webhooks · " + ext + " extensions";
            final String stats = total + " req/24h · " + failed + " failed · " + rl + " rate limited";
            runOnUiThread(() -> {
                ((TextView)findViewById(R.id.overview_subtitle)).setText(sub);
                ((TextView)findViewById(R.id.usage_subtitle)).setText(stats);
            });
        }).start();
    }
}
