package com.yadaventerprise.phoneserver.ui.ai;
import android.os.Bundle; import android.widget.TextView; import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R; import com.yadaventerprise.phoneserver.data.ai.AiActionAuditStore; import java.util.concurrent.Executors; import org.json.JSONObject;
public class AiAuditHistoryActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_ai_simple);
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        ((TextView)findViewById(R.id.title)).setText("AI Action Audit History");
        TextView body = findViewById(R.id.body);
        Executors.newSingleThreadExecutor().execute(() -> {
            StringBuilder sb = new StringBuilder();
            for (JSONObject e : AiActionAuditStore.get(getApplicationContext()).list(100)) {
                sb.append(e.optLong("time")).append(" ").append(e.optString("actor")).append(" ")
                        .append(e.optString("action")).append(" confirmed=").append(e.optBoolean("confirmed"))
                        .append(" success=").append(e.optBoolean("success")).append("\n")
                        .append(e.optString("detail")).append("\n\n");
            }
            if (sb.length() == 0) sb.append("No AI action audit history.");
            final String text = sb.toString();
            runOnUiThread(() -> body.setText(text));
        });
    }
}
