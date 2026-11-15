package com.yadaventerprise.phoneserver.ui.qr;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.network.pairing.PairingPayload;
import com.yadaventerprise.phoneserver.network.pairing.PairingStore;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfig;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfigStore;
import com.yadaventerprise.phoneserver.network.tls.TlsCertificateStore;
import com.yadaventerprise.phoneserver.network.tls.TlsIdentity;
import com.yadaventerprise.phoneserver.service.ServerService;
import com.yadaventerprise.phoneserver.util.NetworkUtils;

public class QrCodeActivity extends AppCompatActivity {

    private ImageView qrImage;
    private TextView subtitle;
    private TextView addressText;
    private String pairingCode = "";
    private long pairingExpires = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_code);

        qrImage = findViewById(R.id.qr_image);
        subtitle = findViewById(R.id.qr_subtitle);
        addressText = findViewById(R.id.qr_address_text);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (!ServerService.isServerRunning()) {
            subtitle.setText("Start the server to generate a QR code");
            addressText.setText("");
            qrImage.setImageBitmap(null);
            return;
        }

        String ip = NetworkUtils.getLocalIpAddress();
        if (ip == null) {
            subtitle.setText("Connect to Wi-Fi to get an address");
            return;
        }

        HttpsConfig https = HttpsConfigStore.get(this).get();
        String url;
        String code = "";
        String fingerprint = "";
        if (https.enabled) {
            url = https.qrBaseUrl(ip);
            if (pairingCode.isEmpty() || pairingExpires <= System.currentTimeMillis()) {
                pairingCode = PairingStore.get(this).create("QR pairing", PairingStore.DEFAULT_TTL_MS);
                pairingExpires = System.currentTimeMillis() + PairingStore.DEFAULT_TTL_MS;
            }
            code = pairingCode;
            TlsIdentity identity = TlsCertificateStore.get(this).load();
            fingerprint = identity == null ? "" : identity.fingerprint;
            subtitle.setText("Scan to pair securely with a one-time code");
        } else {
            url = "http://" + ip + ":" + ServerService.PORT;
            subtitle.setText("Point another phone's camera at this to open the server");
        }
        addressText.setText(url);

        Bitmap bitmap = generateQrBitmap(PairingPayload.build(url, fingerprint, code, "PhoneServer"), 600);
        qrImage.setImageBitmap(bitmap);
    }

    private Bitmap generateQrBitmap(String content, int size) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }
}
