package com.yadaventerprise.phoneserver.ui.developer;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookDispatcher;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookHistoryEntry;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookHistoryStore;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookStore;

public class DeveloperWebhookToolsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_simple);
        ((TextView)findViewById(R.id.title)).setText("Webhook Tools");
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("Supported events\n");
            for (String e : WebhookDispatcher.ALL_EVENTS) sb.append("- ").append(e).append("\n");
            sb.append("\nSignature\nX-PhoneServer-Signature: sha256=<HMAC_SHA256(body, secret)>\n");
            sb.append("\nConfigured webhooks\n");
            for (com.yadaventerprise.phoneserver.data.webhooks.WebhookConfig h : WebhookStore.get(this).all()) {
                sb.append("- ").append(h.name).append(" | ").append(h.enabled?"enabled":"disabled").append(" | ").append(h.url).append("\n");
            }
            sb.append("\nRecent deliveries\n");
            for (WebhookHistoryEntry e : WebhookHistoryStore.get(this).list()) {
                sb.append(e.createdAt).append(" ").append(e.event).append(" ").append(e.success?"OK":"FAIL").append(" ").append(e.responseCode).append("\n");
                if (sb.length() > 6000) break;
            }
            runOnUiThread(() -> ((TextView)findViewById(R.id.body)).setText(sb.toString()));
        }).start();
    }
}
