package com.yadaventerprise.phoneserver.ui.developer;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import org.json.JSONArray;
import org.json.JSONObject;

public class DeveloperSandboxActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_sandbox);
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        EditText input = findViewById(R.id.input);
        TextView output = findViewById(R.id.output);
        findViewById(R.id.run_button).setOnClickListener(v -> {
            try {
                JSONObject body = new JSONObject(input.getText().toString());
                String action = body.optString("action", "echo");
                JSONObject result = new JSONObject();
                result.put("mode", "sandbox");
                result.put("productionAccess", false);
                result.put("action", action);
                if ("validate-json".equals(action)) {
                    String raw = body.optString("json", "");
                    try { new JSONObject(raw); result.put("ok", true); result.put("type","object"); }
                    catch (Exception e1) { try { new JSONArray(raw); result.put("ok", true); result.put("type","array"); } catch (Exception e2) { result.put("ok", false); result.put("error","invalid JSON"); } }
                } else if ("error-simulation".equals(action)) {
                    result.put("ok", false); result.put("simulatedStatus", body.optInt("status", 429));
                } else if ("webhook-preview".equals(action)) {
                    JSONObject payload = new JSONObject(); payload.put("event", body.optString("event","developer.test")); payload.put("timestamp", System.currentTimeMillis()); payload.put("data", body.optJSONObject("data"));
                    result.put("ok", true); result.put("payload", payload);
                } else { result.put("ok", true); result.put("data", body.opt("data")); }
                output.setText(result.toString(2));
            } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
        });
        input.setText("{\n  \"action\": \"echo\",\n  \"data\": {\"hello\":\"world\"}\n}");
    }
}
