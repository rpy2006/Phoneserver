package com.yadaventerprise.phoneserver.ui.developer;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.developer.ApiUsageStore;
import com.yadaventerprise.phoneserver.data.developer.ExtensionStore;
import com.yadaventerprise.phoneserver.data.tokens.TokenStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookConfig;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookStore;
import org.json.JSONArray;
import org.json.JSONObject;

public class DeveloperOverviewActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_simple);
        ((TextView)findViewById(R.id.title)).setText("Developer Overview");
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        new Thread(() -> {
            int active=0,revoked=0,suspended=0,quotaKeys=0;
            long lastUsed = 0;
            String lastKey = "-";
            for (com.yadaventerprise.phoneserver.data.database.Records.ApiTokenRecord t : TokenStore.list(this)) {
                if (t.revoked) revoked++; else if (t.suspended) suspended++; else active++;
                if (t.dailyQuota > 0 || t.monthlyQuota > 0 || t.concurrentLimit > 0) quotaKeys++;
                if (t.lastUsedAt > lastUsed) {
                    lastUsed = t.lastUsedAt;
                    lastKey = t.name;
                }
            }
            int hooks=0, enabled=0, failures=0;
            for (WebhookConfig hook : WebhookStore.get(this).all()) {
                hooks++;
                if (hook.enabled) enabled++;
                failures += hook.failureCount;
            }
            int extensions=0, approved=0, enabledExtensions=0;
            for (com.yadaventerprise.phoneserver.data.developer.ExtensionRecord record : ExtensionStore.get(this).list()) {
                extensions++;
                if (record.approved) approved++;
                if (record.enabled) enabledExtensions++;
            }
            JSONObject usage = ApiUsageStore.get(this).summary(System.currentTimeMillis()-24L*60*60*1000L);
            JSONArray top = usage.optJSONArray("topEndpoints");
            String topEndpoint = "-";
            if (top != null && top.length() > 0) {
                JSONObject first = top.optJSONObject(0);
                if (first != null) topEndpoint = first.optString("endpoint") + " (" + first.optInt("count") + ")";
            }
            String text = "API Keys\nActive: " + active + "\nSuspended: " + suspended + "\nRevoked: " + revoked
                    + "\nKeys with quotas: " + quotaKeys
                    + "\nMost recent use: " + lastKey
                    + "\n\nWebhooks\nConfigured: " + hooks + "\nEnabled: " + enabled
                    + "\nFailed deliveries: " + failures
                    + "\n\nExtensions\nInstalled: " + extensions + "\nApproved: " + approved
                    + "\nEnabled: " + enabledExtensions
                    + "\n\nUsage (24h)\nTotal: " + usage.optInt("totalRequests",0)
                    + "\nSuccess: " + usage.optInt("successRequests",0)
                    + "\nFailed: " + usage.optInt("failedRequests",0)
                    + "\nRate limited: " + usage.optInt("rateLimitViolations",0)
                    + "\nAvg response: " + usage.optLong("averageResponseMs",0) + " ms"
                    + "\nTop endpoint: " + topEndpoint;
            runOnUiThread(() -> {
                if (!isFinishing()) ((TextView)findViewById(R.id.body)).setText(text);
            });
        }).start();
    }
}
