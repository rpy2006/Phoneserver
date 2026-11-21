package com.yadaventerprise.phoneserver.ui.ai;
import android.os.Bundle; import android.widget.TextView; import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R; import com.yadaventerprise.phoneserver.data.ai.AiConversationStore; import java.util.concurrent.Executors; import org.json.JSONObject;
public class AiHistoryActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_ai_simple);
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        ((TextView)findViewById(R.id.title)).setText("Conversation History");
        TextView body = findViewById(R.id.body);
        Executors.newSingleThreadExecutor().execute(() -> {
            StringBuilder sb = new StringBuilder();
            for (JSONObject e : AiConversationStore.get(getApplicationContext()).list(100)) {
                sb.append("[").append(e.optString("role")).append("] ").append(e.optString("content")).append("\n\n");
            }
            if (sb.length() == 0) sb.append("No conversation history.");
            final String text = sb.toString();
            runOnUiThread(() -> body.setText(text));
        });
    }
}
