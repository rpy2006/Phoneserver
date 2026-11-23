package com.yadaventerprise.phoneserver.ui.developer;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.developer.ExtensionRecord;
import com.yadaventerprise.phoneserver.data.developer.ExtensionStore;
import org.json.JSONObject;

public class DeveloperExtensionsActivity extends AppCompatActivity {
    private TextView body;
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_extensions);
        body = findViewById(R.id.body);
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.install_button).setOnClickListener(v -> promptInstall());
        refresh();
    }
    private void refresh() {
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            for (ExtensionRecord r : ExtensionStore.get(this).list()) {
                sb.append(r.name).append(" v").append(r.version).append(" [").append(r.enabled?"enabled":"disabled").append(r.approved?",approved":"").append("]\n");
                sb.append("id: ").append(r.id).append("\npermissions: ").append(r.permissions).append("\ndependencies: ").append(r.dependencies).append("\n\n");
            }
            if (sb.length()==0) sb.append("No extensions installed. Install a manifest-only extension JSON.");
            runOnUiThread(() -> body.setText(sb.toString()));
        }).start();
    }
    private void promptInstall() {
        EditText input = new EditText(this);
        input.setHint("Paste extension manifest JSON");
        input.setMinLines(6);
        LinearLayout wrap = new LinearLayout(this); wrap.setPadding(40,20,40,0); wrap.addView(input, new LinearLayout.LayoutParams(-1,-2));
        new AlertDialog.Builder(this).setTitle("Install Extension").setView(wrap)
                .setPositiveButton("Install", (d,w) -> {
                    String text = input.getText().toString();
                    new Thread(() -> {
                        try {
                            ExtensionStore.get(this).install(new JSONObject(text), "ui:admin");
                            runOnUiThread(() -> { Toast.makeText(this,"Extension installed",Toast.LENGTH_SHORT).show(); refresh(); });
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }).start();
                }).setNegativeButton("Cancel", null).show();
    }
}
