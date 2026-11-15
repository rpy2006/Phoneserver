package com.yadaventerprise.phoneserver.ui.sharing;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.yadaventerprise.phoneserver.R;

public class ShareQrActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "share_qr_url";
    public static final String EXTRA_TITLE = "share_qr_title";
    public static final String EXTRA_WARNING = "share_qr_warning";

    private String url = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share_qr);
        url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null) url = "";
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String warning = getIntent().getStringExtra(EXTRA_WARNING);

        TextView qrTitle = findViewById(R.id.qr_title);
        TextView qrUrl = findViewById(R.id.qr_url);
        TextView qrWarning = findViewById(R.id.qr_warning);
        ImageView qrImage = findViewById(R.id.qr_image);
        qrTitle.setText(title == null || title.trim().isEmpty() ? "Shared link" : title);
        qrUrl.setText(url);
        if (warning == null || warning.trim().isEmpty()) {
            qrWarning.setVisibility(View.GONE);
        } else {
            qrWarning.setText(warning);
            qrWarning.setVisibility(View.VISIBLE);
        }
        qrImage.setImageBitmap(generateQrBitmap(url, 700));

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.copy_button).setOnClickListener(v -> copy());
        findViewById(R.id.share_button).setOnClickListener(v -> share());
    }

    private void copy() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Share link", url));
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show();
    }

    private void share() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(intent, "Share link"));
    }

    private Bitmap generateQrBitmap(String content, int size) {
        if (content == null || content.isEmpty()) return null;
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
