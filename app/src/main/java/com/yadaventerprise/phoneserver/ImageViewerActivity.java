package com.yadaventerprise.phoneserver;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

public class ImageViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        File file = path != null ? new File(path) : null;

        TextView title = findViewById(R.id.image_title);
        ImageView imageView = findViewById(R.id.image_view);

        if (file == null || !file.exists()) {
            Toast.makeText(this, "Image not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        title.setText(file.getName());

        Bitmap bitmap = decodeSampledBitmap(file);
        if (bitmap == null) {
            Toast.makeText(this, "Could not open this image", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        imageView.setImageBitmap(bitmap);
    }

    /** Decodes the image scaled down to roughly screen size, so large photos don't run out of memory. */
    private Bitmap decodeSampledBitmap(File file) {
        try {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int targetWidth = metrics.widthPixels;
            int targetHeight = metrics.heightPixels;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);

            int inSampleSize = 1;
            int halfWidth = bounds.outWidth / 2;
            int halfHeight = bounds.outHeight / 2;
            while ((halfWidth / inSampleSize) >= targetWidth && (halfHeight / inSampleSize) >= targetHeight) {
                inSampleSize *= 2;
            }

            BitmapFactory.Options loadOptions = new BitmapFactory.Options();
            loadOptions.inSampleSize = inSampleSize;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), loadOptions);
        } catch (Exception e) {
            return null;
        }
    }
}
