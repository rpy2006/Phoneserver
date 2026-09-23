package com.yadaventerprise.phoneserver;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class QrCodeActivity extends AppCompatActivity {

    private ImageView qrImage;
    private TextView subtitle;
    private TextView addressText;

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

        String url = "http://" + ip + ":" + ServerService.PORT;
        subtitle.setText("Point another phone's camera at this to open the server");
        addressText.setText(url);

        Bitmap bitmap = generateQrBitmap(url, 600);
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
