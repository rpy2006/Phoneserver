package com.yadaventerprise.phoneserver.ui.remote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.yadaventerprise.phoneserver.R;
import java.text.DateFormat;
import java.util.Date;

final class RemoteUi {
    private RemoteUi() {
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_home_card);
        card.setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(context, 14);
        card.setLayoutParams(params);
        return card;
    }

    static TextView heading(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(15);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setTextColor(ContextCompat.getColor(context, R.color.home_text_primary));
        return view;
    }

    static TextView body(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(ContextCompat.getColor(context, R.color.home_text_secondary));
        return view;
    }

    static TextView action(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(12);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundResource(R.drawable.bg_pill_light);
        view.setTextColor(ContextCompat.getColor(context, R.color.home_accent));
        view.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        return view;
    }

    static View divider(Context context) {
        View view = new View(context);
        view.setBackgroundColor(ContextCompat.getColor(context, R.color.home_divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.topMargin = dp(context, 10);
        params.bottomMargin = dp(context, 10);
        view.setLayoutParams(params);
        return view;
    }

    static LinearLayout row(Context context, int icon, String title, String subtitle) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout iconBox = new FrameLayout(context);
        iconBox.setBackgroundResource(R.drawable.bg_home_icon_circle);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                dp(context, 36), dp(context, 36));
        iconBox.setLayoutParams(iconParams);
        ImageView imageView = new ImageView(context);
        imageView.setImageResource(icon);
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                dp(context, 18), dp(context, 18), Gravity.CENTER);
        imageView.setLayoutParams(imageParams);
        iconBox.addView(imageView);
        head.addView(iconBox);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginStart(dp(context, 12));
        texts.setLayoutParams(textParams);
        TextView name = heading(context, title);
        texts.addView(name);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView detail = body(context, subtitle);
            detail.setPadding(0, dp(context, 3), 0, 0);
            texts.addView(detail);
        }
        head.addView(texts);
        root.addView(head);
        return root;
    }

    static TextView detail(Context context, String text) {
        TextView view = body(context, text);
        view.setTextSize(12);
        view.setPadding(dp(context, 48), dp(context, 3), 0, 0);
        return view;
    }

    static LinearLayout actions(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setPadding(dp(context, 48), dp(context, 8), 0, 0);
        return layout;
    }

    static EditText input(Context context, String hint, String value, boolean password) {
        EditText input = new EditText(context);
        input.setHint(hint);
        if (value != null) input.setText(value);
        input.setSingleLine(true);
        input.setBackgroundResource(R.drawable.bg_input_outline);
        input.setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10));
        input.setTextColor(ContextCompat.getColor(context, R.color.home_text_primary));
        input.setHintTextColor(ContextCompat.getColor(context, R.color.home_text_secondary));
        input.setTextSize(14);
        int type = InputType.TYPE_CLASS_TEXT;
        if (password) type |= InputType.TYPE_TEXT_VARIATION_PASSWORD;
        input.setInputType(type);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 8);
        input.setLayoutParams(params);
        return input;
    }

    static void confirm(Context context, String title, String message, String positive, Runnable action) {
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive, (dialog, which) -> action.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    static String time(long timestamp) {
        if (timestamp <= 0) return "never";
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(timestamp));
    }

    static String shortTime(long timestamp) {
        if (timestamp <= 0) return "never";
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(timestamp));
    }

    static Bitmap qrBitmap(String content, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size);
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
