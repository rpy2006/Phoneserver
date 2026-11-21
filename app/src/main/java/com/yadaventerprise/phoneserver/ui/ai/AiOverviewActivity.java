package com.yadaventerprise.phoneserver.ui.ai;
import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.ai.AiAssistantService;
import java.util.concurrent.Executors;
public class AiOverviewActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_simple);
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        ((TextView)findViewById(R.id.title)).setText("AI Overview");
        TextView body = findViewById(R.id.body);
        Executors.newSingleThreadExecutor().execute(() -> {
            String text = AiAssistantService.insights(getApplicationContext()) + "\n\nUse AI Settings to control provider, data sharing, and consent. All AI actions require authorization and confirmation before sensitive operations.";
            runOnUiThread(() -> body.setText(text));
        });
    }
}
