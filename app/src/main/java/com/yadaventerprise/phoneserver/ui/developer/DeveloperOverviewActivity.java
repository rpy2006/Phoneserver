package com.yadaventerprise.phoneserver.ui.developer;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.developer.ApiUsageStore;
import com.yadaventerprise.phoneserver.data.developer.ExtensionStore;
import com.yadaventerprise.phoneserver.data.tokens.TokenStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookStore;
import org.json.JSONObject;

public class DeveloperOverviewActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_simple);
        ((TextView)findViewById(R.id.title)).setText("Developer Overview");
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        refresh();
    }
    private void refresh() {
        new Thread(() -> {
            int active=0,revoked=0,suspended=0;
            for (com.yadaventerprise.phoneserver.data.database.Records.ApiTokenRecord t : TokenStore.list(this)) {
                if (t.revoked) revoked++; else if (t.suspended) suspended++; else active++;
            }
            JSONObject usage = ApiUsageStore.get(this).summary(System.currentTimeMillis()-24L*60*60*1000L);
            String text = "API Keys\nActive: " + active + "\nSuspended: " + suspended + "\nRevoked: " + revoked
                    + "\n\nWebhooks: " + WebhookStore.get(this).all().size()
                    + "\nExtensions: " + ExtensionStore.get(this).list().size()
                    + "\n\nUsage (24h)\nTotal: " + usage.optInt("totalRequests",0)
                    + "\nSuccess: " + usage.optInt("successRequests",0)
                    + "\nFailed: " + usage.optInt("failedRequests",0)
                    + "\nRate limited: " + usage.optInt("rateLimitViolations",0)
                    + "\nAvg response: " + usage.optLong("averageResponseMs",0) + " ms";
            runOnUiThread(() -> ((TextView)findViewById(R.id.body)).setText(text));
        }).start();
    }
}
