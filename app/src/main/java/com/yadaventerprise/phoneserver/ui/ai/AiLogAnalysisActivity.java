package com.yadaventerprise.phoneserver.ui.ai;
import android.os.Bundle; import android.widget.TextView; import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R; import com.yadaventerprise.phoneserver.data.ai.AiAssistantService; import java.util.concurrent.Executors;
public class AiLogAnalysisActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) { super.onCreate(savedInstanceState); setContentView(R.layout.activity_ai_simple);
        findViewById(R.id.back_button).setOnClickListener(v -> finish()); ((TextView)findViewById(R.id.title)).setText("Log Analysis");
        TextView body = findViewById(R.id.body); Executors.newSingleThreadExecutor().execute(() -> { try { String t = AiAssistantService.ask(getApplicationContext(), "Summarize recent logs and group related errors").answer; runOnUiThread(() -> body.setText(t)); } catch (Exception e) { runOnUiThread(() -> body.setText("Error: " + e.getMessage())); } }); }
}
