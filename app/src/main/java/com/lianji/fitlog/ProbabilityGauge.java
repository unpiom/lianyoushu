package com.lianji.fitlog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

final class ProbabilityGauge extends View {
    private final Ui ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path circle = new Path();
    private final Path backWave = new Path();
    private final Path frontWave = new Path();
    private final double probability;
    private final String label;
    private float centerX;
    private float centerY;
    private float radius;
    private Shader water;

    ProbabilityGauge(Context context, double probability) {
        super(context);
        ui = new Ui(context);
        this.probability = probability;
        String description;
        if (Double.isNaN(probability)) {
            label = "—";
            description = "暂无法估计达标概率";
        } else if (probability < 0.05) {
            label = "<5%";
            description = "达标概率低于百分之五";
        } else if (probability > 0.95) {
            label = ">95%";
            description = "达标概率高于百分之九十五";
        } else {
            long percent = Math.round(probability * 20) * 5;
            label = percent + "%";
            description = "达标概率约百分之" + percent;
        }
        setContentDescription(description);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        centerX = width / 2f;
        centerY = height / 2f;
        radius = Math.min(centerX, centerY) - ui.dp(6);
        if (radius <= 0) return;
        circle.reset();
        circle.addCircle(centerX, centerY, radius, Path.Direction.CW);
        if (probability > 0) {
            float y = centerY + radius * waterOffset(probability);
            float amplitude = Math.min(ui.dp(5), (radius - Math.abs(y - centerY)) * 0.4f);
            wave(backWave, y, -amplitude);
            wave(frontWave, y, amplitude);
            water = new LinearGradient(centerX - radius, centerY - radius, centerX + radius, centerY + radius,
                    0xff4e9e89, 0xff155342, Shader.TileMode.CLAMP);
        }
    }

    private void wave(Path path, float y, float amplitude) {
        float left = centerX - radius;
        float right = centerX + radius;
        path.reset();
        path.moveTo(left, y);
        path.cubicTo(left + radius / 3, y - amplitude, centerX - radius / 3, y - amplitude, centerX, y);
        path.cubicTo(centerX + radius / 3, y + amplitude, right - radius / 3, y + amplitude, right, y);
        path.lineTo(right, centerY + radius);
        path.lineTo(left, centerY + radius);
        path.close();
    }

    private static float waterOffset(double fraction) {
        if (fraction >= 1) return -1;
        // Solve for the height of the circular segment with the requested filled area.
        double low = -1;
        double high = 1;
        for (int step = 0; step < 24; step++) {
            double middle = (low + high) / 2;
            double area = (Math.acos(middle) - middle * Math.sqrt(1 - middle * middle)) / Math.PI;
            if (area > fraction) low = middle;
            else high = middle;
        }
        return (float) ((low + high) / 2);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (radius <= 0) return;

        paint.setColor(0xffe3efe6);
        canvas.drawCircle(centerX, centerY, radius + ui.dp(5), paint);
        paint.setColor(0xfff8fbf7);
        canvas.drawCircle(centerX, centerY, radius + ui.dp(1), paint);

        int saved = canvas.save();
        canvas.clipPath(circle);
        if (probability > 0) {
            paint.setColor(0xffafd1c2);
            canvas.drawPath(backWave, paint);
            paint.setShader(water);
            canvas.drawPath(frontWave, paint);
            paint.setShader(null);
        }
        paint.setTextSize(Math.min(ui.sp(27), radius * 0.62f));
        float width = paint.measureText(label);
        if (width > radius * 1.7f) paint.setTextSize(paint.getTextSize() * radius * 1.7f / width);
        float baseline = centerY - (paint.ascent() + paint.descent()) / 2;
        paint.setColor(Double.isNaN(probability) ? Ui.MUTED : Ui.FOREST);
        canvas.drawText(label, centerX, baseline, paint);
        if (probability > 0) {
            canvas.clipPath(frontWave);
            paint.setColor(Ui.WHITE);
            canvas.drawText(label, centerX, baseline, paint);
        }
        canvas.restoreToCount(saved);
    }
}
