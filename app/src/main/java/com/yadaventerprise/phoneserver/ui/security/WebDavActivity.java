package com.yadaventerprise.phoneserver.ui.security;

import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfig;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfigStore;
import com.yadaventerprise.phoneserver.network.tls.TlsCertificateStore;
import com.yadaventerprise.phoneserver.network.tls.TlsIdentity;
import com.yadaventerprise.phoneserver.service.ServerService;
import com.yadaventerprise.phoneserver.util.NetworkUtils;

public class WebDavActivity extends AppCompatActivity {

    private TextView urlText;
    private TextView fingerprintText;
    private TextView statusText;
    private Switch remoteSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webdav);
        urlText = findViewById(R.id.dav_url);
        fingerprintText = findViewById(R.id.dav_fingerprint);
        statusText = findViewById(R.id.dav_status);
        remoteSwitch = findViewById(R.id.switch_remote);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        remoteSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                ContentStore.setAllowRemoteAccess(this, isChecked));
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        remoteSwitch.setChecked(ContentStore.getAllowRemoteAccess(this));
        if (!ServerService.isServerRunning()) {
            urlText.setText("Start the server to get a URL");
            fingerprintText.setText("");
            statusText.setText("Server is offline");
            return;
        }
        String ip = NetworkUtils.getLocalIpAddress();
        HttpsConfig https = HttpsConfigStore.get(this).get();
        String base;
        if (https.enabled) {
            base = https.qrBaseUrl(ip);
            TlsIdentity identity = TlsCertificateStore.get(this).load();
            fingerprintText.setText(identity == null ? "" : "SHA-256  " + identity.fingerprint);
        } else {
            base = "http://" + (ip == null ? "localhost" : ip) + ":" + ServerService.PORT;
            fingerprintText.setText("");
        }
        urlText.setText(base + "/dav/");
        statusText.setText(https.enabled
                ? "HTTPS is enabled. Use the pinned certificate shown above."
                : "HTTP is not encrypted. Enable HTTPS for remote use.");
    }
}
