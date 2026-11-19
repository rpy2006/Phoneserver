package com.yadaventerprise.phoneserver.ui.remote;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.remote.RemotePairingRequest;
import com.yadaventerprise.phoneserver.data.remote.RemotePairingStore;
import com.yadaventerprise.phoneserver.data.remote.RemoteSettings;
import com.yadaventerprise.phoneserver.data.remote.RemoteSettingsStore;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfig;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfigStore;
import com.yadaventerprise.phoneserver.util.NetworkUtils;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;

public class DevicePairingActivity extends AppCompatActivity {

    private static final String STATE_CODE = "state_pairing_code";
    private static final String STATE_EXPIRES = "state_pairing_expires";

    private TextView codeText;
    private TextView expiryText;
    private TextView urlText;
    private TextView requestsEmpty;
    private ImageView qrImage;
    private EditText nameInput;
    private LinearLayout requestsContainer;
    private String pairingCode = "";
    private long codeExpires;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_pairing);

        codeText = findViewById(R.id.code_text);
        expiryText = findViewById(R.id.expiry_text);
        urlText = findViewById(R.id.pair_url_text);
        qrImage = findViewById(R.id.qr_image);
        nameInput = findViewById(R.id.pairing_name_input);
        requestsContainer = findViewById(R.id.requests_container);
        requestsEmpty = findViewById(R.id.requests_empty);

        if (savedInstanceState != null) {
            pairingCode = savedInstanceState.getString(STATE_CODE, "");
            codeExpires = savedInstanceState.getLong(STATE_EXPIRES, 0);
        }

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.refresh_button).setOnClickListener(v -> {
            ensureCode();
            loadRequests();
        });
        findViewById(R.id.new_code_button).setOnClickListener(v -> generateCode());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureCode();
        loadRequests();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_CODE, pairingCode);
        outState.putLong(STATE_EXPIRES, codeExpires);
    }

    private void ensureCode() {
        if (pairingCode != null && !pairingCode.isEmpty()
                && codeExpires > System.currentTimeMillis()) {
            showCurrentCode();
            return;
        }
        generateCode();
    }

    private void generateCode() {
        RemoteSettings settings = RemoteSettingsStore.get(getApplicationContext()).get();
        HttpsConfig https = HttpsConfigStore.get(getApplicationContext()).get();
        if (!https.enabled) {
            pairingCode = "";
            codeExpires = 0;
            codeText.setText("Enable HTTPS to pair");
            expiryText.setText("No secure base URL available");
            urlText.setText("");
            qrImage.setImageBitmap(null);
            return;
        }
        if (!settings.allowPairing) {
            pairingCode = "";
            codeExpires = 0;
            codeText.setText("Pairing disabled");
            expiryText.setText("Enable pairing in remote access settings");
            urlText.setText("");
            qrImage.setImageBitmap(null);
            return;
        }

        String label = nameInput.getText().toString().trim();
        if (label.isEmpty()) label = "Remote device";
        RemotePairingStore.Created created = RemotePairingStore.get(getApplicationContext())
                .create(label, settings.pairingTtlMinutes * 60_000L);
        pairingCode = created.code;
        codeExpires = created.request.expiresAt;
        showCurrentCode();
    }

    private void showCurrentCode() {
        if (pairingCode == null || pairingCode.isEmpty()) return;
        HttpsConfig https = HttpsConfigStore.get(getApplicationContext()).get();
        String baseUrl = https.qrBaseUrl(NetworkUtils.getLocalIpAddress());
        urlText.setText(baseUrl);
        expiryText.setText("Expires: " + RemoteUi.time(codeExpires));
        codeText.setText(pairingCode);
        try {
            JSONObject payload = new JSONObject();
            payload.put("code", pairingCode);
            payload.put("url", baseUrl);
            qrImage.setImageBitmap(RemoteUi.qrBitmap(payload.toString(), 600));
        } catch (Exception e) {
            qrImage.setImageBitmap(null);
        }
    }

    private void loadRequests() {
        new Thread(() -> {
            final List<RemotePairingRequest> requests =
                    RemotePairingStore.get(getApplicationContext()).all();
            Collections.sort(requests, (a, b) -> Long.compare(b.createdAt, a.createdAt));
            runOnUiThread(() -> {
                if (isFinishing()) return;
                requestsContainer.removeAllViews();
                requestsEmpty.setVisibility(requests.isEmpty()
                        ? android.view.View.VISIBLE : android.view.View.GONE);
                long now = System.currentTimeMillis();
                for (RemotePairingRequest request : requests) {
                    LinearLayout card = RemoteUi.card(this);
                    String title = request.deviceName.isEmpty()
                            ? request.requestedName : request.deviceName;
                    if (title.isEmpty()) title = "Unnamed request";
                    String subtitle = "Status: " + request.status
                            + " · Requested " + RemoteUi.shortTime(request.createdAt)
                            + " · Expires " + RemoteUi.shortTime(request.expiresAt);
                    card.addView(RemoteUi.row(this, R.drawable.ic_device_admin, title, subtitle));
                    card.addView(RemoteUi.detail(this, "Requested as: " + request.requestedName));
                    if (!request.deviceInfo.isEmpty()) {
                        card.addView(RemoteUi.detail(this, "Device info: " + truncate(request.deviceInfo, 160)));
                    }
                    if (!request.approvedBy.isEmpty()) {
                        card.addView(RemoteUi.detail(this, "Handled by: " + request.approvedBy
                                + " · " + RemoteUi.time(request.approvedAt)));
                    }
                    if (RemotePairingRequest.STATUS_PENDING.equals(request.status)
                            && request.expiresAt > now) {
                        LinearLayout actions = RemoteUi.actions(this);
                        TextView approve = RemoteUi.action(this, "Approve");
                        TextView reject = RemoteUi.action(this, "Reject");
                        approve.setOnClickListener(v -> approve(request.id));
                        reject.setOnClickListener(v -> reject(request.id));
                        actions.addView(approve);
                        LinearLayout.LayoutParams rejectParams =
                                new LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT);
                        rejectParams.setMarginStart(RemoteUi.dp(this, 8));
                        reject.setLayoutParams(rejectParams);
                        actions.addView(reject);
                        card.addView(actions);
                    }
                    requestsContainer.addView(card);
                }
            });
        }).start();
    }

    private void approve(String id) {
        String deviceId = UUID.randomUUID().toString();
        RemotePairingRequest approved = RemotePairingStore.get(getApplicationContext())
                .approve(id, "local-admin", deviceId);
        Toast.makeText(this, approved == null
                ? "Request is no longer active" : "Pairing approved", Toast.LENGTH_SHORT).show();
        loadRequests();
    }

    private void reject(String id) {
        RemotePairingRequest rejected = RemotePairingStore.get(getApplicationContext())
                .reject(id, "local-admin");
        Toast.makeText(this, rejected == null
                ? "Request not found" : "Pairing rejected", Toast.LENGTH_SHORT).show();
        loadRequests();
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
