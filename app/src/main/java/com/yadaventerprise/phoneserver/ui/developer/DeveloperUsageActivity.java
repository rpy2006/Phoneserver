package com.yadaventerprise.phoneserver.ui.developer;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.developer.ApiUsageEntry;
import com.yadaventerprise.phoneserver.data.developer.ApiUsageStore;
import org.json.JSONArray;
import org.json.JSONObject;

public class DeveloperUsageActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_simple);
        ((TextView)findViewById(R.id.title)).setText("API Usage");
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        new Thread(() -> {
            JSONObject s = ApiUsageStore.get(this).summary(System.currentTimeMillis()-24L*60*60*1000L);
            StringBuilder sb = new StringBuilder();
            sb.append("Last 24 hours\nTotal: ").append(s.optInt("totalRequests",0));
            sb.append("\nSuccess: ").append(s.optInt("successRequests",0));
            sb.append("\nFailed: ").append(s.optInt("failedRequests",0));
            sb.append("\nRate limit violations: ").append(s.optInt("rateLimitViolations",0));
            sb.append("\nAvg response: ").append(s.optLong("averageResponseMs",0)).append(" ms\n\nTop endpoints\n");
            JSONArray top = s.optJSONArray("topEndpoints");
            if (top != null) for (int i=0;i<top.length();i++){ JSONObject o=top.optJSONObject(i); if(o!=null) sb.append("- ").append(o.optString("endpoint")).append(": ").append(o.optInt("count")).append("\n"); }
            sb.append("\nRecent requests\n");
            for (ApiUsageEntry e : ApiUsageStore.get(this).list(30)) {
                sb.append(e.method).append(" ").append(e.path).append(" -> ").append(e.status)
                        .append(" (").append(e.durationMs).append("ms)\n");
            }
            runOnUiThread(() -> ((TextView)findViewById(R.id.body)).setText(sb.toString()));
        }).start();
    }
}
