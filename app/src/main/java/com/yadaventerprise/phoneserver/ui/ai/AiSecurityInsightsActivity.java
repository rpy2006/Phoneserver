package com.yadaventerprise.phoneserver.ui.ai;
import android.os.Bundle; import android.widget.TextView; import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R; import com.yadaventerprise.phoneserver.data.ai.AiAssistantService; import java.util.concurrent.Executors;
public class AiSecurityInsightsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) { super.onCreate(savedInstanceState); setContentView(R.layout.activity_ai_simple);
        findViewById(R.id.back_button).setOnClickListener(v -> finish()); ((TextView)findViewById(R.id.title)).setText("Security Insights");
        TextView body = findViewById(R.id.body); Executors.newSingleThreadExecutor().execute(() -> { String t = AiAssistantService.securityInsights(getApplicationContext()); runOnUiThread(() -> body.setText(t)); }); }
}
