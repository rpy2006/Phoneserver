package com.yadaventerprise.phoneserver;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.IOException;

/**
 * Renders a PDF page by page using Android's built-in PdfRenderer - no external
 * library needed. One page is rasterized to a bitmap at a time (not the whole
 * document at once), so even fairly large PDFs stay light on memory.
 */
public class PdfViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";

    private ParcelFileDescriptor fileDescriptor;
    private PdfRenderer renderer;
    private int currentPage = 0;
    private int pageCount = 0;

    private ImageView pageImage;
    private TextView pageIndicator;
    private int targetWidth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        File file = path != null ? new File(path) : null;

        TextView title = findViewById(R.id.pdf_title);
        pageImage = findViewById(R.id.pdf_page_image);
        pageIndicator = findViewById(R.id.page_indicator);

        if (file == null || !file.exists()) {
            Toast.makeText(this, "PDF not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        title.setText(file.getName());

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        targetWidth = metrics.widthPixels;

        try {
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(fileDescriptor);
            pageCount = renderer.getPageCount();
            renderPage(0);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open this PDF", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        findViewById(R.id.prev_button).setOnClickListener(v -> {
            if (currentPage > 0) renderPage(currentPage - 1);
        });
        findViewById(R.id.next_button).setOnClickListener(v -> {
            if (currentPage < pageCount - 1) renderPage(currentPage + 1);
        });
    }

    private void renderPage(int index) {
        if (renderer == null || index < 0 || index >= pageCount) return;
        try {
            PdfRenderer.Page page = renderer.openPage(index);
            int height = (int) ((float) page.getHeight() / page.getWidth() * targetWidth);
            Bitmap bitmap = Bitmap.createBitmap(targetWidth, Math.max(height, 1), Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();

            pageImage.setImageBitmap(bitmap);
            currentPage = index;
            pageIndicator.setText((currentPage + 1) + " / " + pageCount);
        } catch (Exception e) {
            Toast.makeText(this, "Could not render this page", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (renderer != null) renderer.close();
            if (fileDescriptor != null) fileDescriptor.close();
        } catch (IOException ignored) {
        }
    }
}
