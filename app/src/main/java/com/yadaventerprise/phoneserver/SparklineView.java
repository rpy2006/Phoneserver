package com.yadaventerprise.phoneserver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** A small, label-free line graph for showing a trend at a glance (CPU/RAM/Traffic history). */
public class SparklineView extends View {

    private final List<Float> values = new ArrayList<>();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SparklineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        linePaint.setColor(getResources().getColor(R.color.home_accent));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(4f);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        fillPaint.setColor(getResources().getColor(R.color.home_accent_light));
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setValues(List<Float> newValues) {
        values.clear();
        values.addAll(newValues);
        invalidate();
    }

    public void addValue(float value, int maxPoints) {
        values.add(value);
        while (values.size() > maxPoints) {
            values.remove(0);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (values.size() < 2) return;

        float max = Float.MIN_VALUE;
        float min = Float.MAX_VALUE;
        for (float v : values) {
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        if (max == min) {
            max += 1f;
            min -= 1f;
        }

        float width = getWidth();
        float height = getHeight();
        float stepX = width / (values.size() - 1);

        Path linePath = new Path();
        Path fillPath = new Path();

        for (int i = 0; i < values.size(); i++) {
            float x = i * stepX;
            float normalized = (values.get(i) - min) / (max - min);
            float y = height - (normalized * height * 0.85f) - (height * 0.075f);
            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, height);
                fillPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }
        fillPath.lineTo(width, height);
        fillPath.close();

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);
    }
}
