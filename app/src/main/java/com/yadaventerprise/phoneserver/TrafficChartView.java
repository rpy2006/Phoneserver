package com.yadaventerprise.phoneserver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plots upload/download traffic history as two lines with a simple GB-scale
 * y-axis. Built as a plain custom View (Canvas drawing) rather than a
 * charting library, to keep the app dependency-light.
 */
public class TrafficChartView extends View {

    private final List<Float> uploadPoints = new ArrayList<>();
    private final List<Float> downloadPoints = new ArrayList<>();

    private final Paint uploadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint downloadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TrafficChartView(Context context, AttributeSet attrs) {
        super(context, attrs);

        uploadPaint.setColor(getResources().getColor(R.color.home_accent));
        uploadPaint.setStyle(Paint.Style.STROKE);
        uploadPaint.setStrokeWidth(4f);
        uploadPaint.setStrokeJoin(Paint.Join.ROUND);

        downloadPaint.setColor(0xFFA5D6A7); // lighter green
        downloadPaint.setStyle(Paint.Style.STROKE);
        downloadPaint.setStrokeWidth(4f);
        downloadPaint.setStrokeJoin(Paint.Join.ROUND);

        gridPaint.setColor(0xFFEDEFED);
        gridPaint.setStrokeWidth(1.5f);

        labelPaint.setColor(0xFF6E756E);
        labelPaint.setTextSize(24f);
    }

    /** Values are cumulative bytes-per-sample deltas, in MB, oldest first. */
    public void setData(List<Float> uploadMb, List<Float> downloadMb) {
        uploadPoints.clear();
        uploadPoints.addAll(uploadMb);
        downloadPoints.clear();
        downloadPoints.addAll(downloadMb);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int leftPadding = 90;
        int bottomPadding = 40;
        float chartWidth = getWidth() - leftPadding;
        float chartHeight = getHeight() - bottomPadding;

        float max = 1f;
        for (float v : uploadPoints) max = Math.max(max, v);
        for (float v : downloadPoints) max = Math.max(max, v);

        // grid lines + labels (4 horizontal bands)
        for (int i = 0; i <= 3; i++) {
            float y = chartHeight - (chartHeight * i / 3f);
            canvas.drawLine(leftPadding, y, getWidth(), y, gridPaint);
            float value = max * i / 3f;
            String label = value >= 1024 ? String.format(Locale.US, "%.1f GB", value / 1024f)
                    : String.format(Locale.US, "%.0f MB", value);
            canvas.drawText(label, 0, y + 8, labelPaint);
        }

        if (uploadPoints.size() < 2) return;

        drawLine(canvas, uploadPoints, uploadPaint, leftPadding, chartWidth, chartHeight, max);
        drawLine(canvas, downloadPoints, downloadPaint, leftPadding, chartWidth, chartHeight, max);
    }

    private void drawLine(Canvas canvas, List<Float> points, Paint paint, float leftPadding,
                           float chartWidth, float chartHeight, float max) {
        if (points.size() < 2) return;
        Path path = new Path();
        float stepX = chartWidth / (points.size() - 1);
        for (int i = 0; i < points.size(); i++) {
            float x = leftPadding + i * stepX;
            float y = chartHeight - (points.get(i) / max) * chartHeight;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, paint);
    }
}
