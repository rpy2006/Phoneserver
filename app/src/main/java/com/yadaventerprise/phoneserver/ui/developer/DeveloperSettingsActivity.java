package com.yadaventerprise.phoneserver.ui.developer;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;

public class DeveloperSettingsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_simple);
        ((TextView)findViewById(R.id.title)).setText("Developer Settings");
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        ((TextView)findViewById(R.id.body)).setText("Developer platform uses existing PhoneServer token policies, server-side authorization, JSON/file storage, and HTTPS recommendations for external access. API keys are shown once and stored as hashes. Webhook secrets are not exposed in developer API responses. Extensions are manifest-based and require admin approval before enabling.");
    }
}
