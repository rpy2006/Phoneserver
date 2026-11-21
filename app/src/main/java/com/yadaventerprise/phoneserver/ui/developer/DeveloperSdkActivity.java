package com.yadaventerprise.phoneserver.ui.developer;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class DeveloperSdkActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_simple);
        ((TextView)findViewById(R.id.title)).setText("SDK Documentation");
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        StringBuilder sb = new StringBuilder();
        sb.append("JavaScript SDK\nPath: /assets/developer/phoneserver-sdk.js\n\nUsage:\nconst client = new PhoneServerSDK({ baseUrl: 'https://host:port/api/v1', token: 'pst_...' });\nawait client.listFiles(projectId);\nawait client.uploadFile(projectId, file);\nawait client.createShare({ path, mode });\nawait client.trackAdEvent({ campaignId, event });\nawait client.registerWebhook({ name, url, events });\n\nThe SDK uses fetch, supports timeout, and never requires admin password.\n\n---\n");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("developer/phoneserver-sdk.js")))) {
            String line; int n=0; while ((line=r.readLine())!=null && n<220) { sb.append(line).append('\n'); n++; }
        } catch (Exception e) { sb.append("SDK file unavailable: ").append(e.getMessage()); }
        ((TextView)findViewById(R.id.body)).setText(sb.toString());
    }
}
