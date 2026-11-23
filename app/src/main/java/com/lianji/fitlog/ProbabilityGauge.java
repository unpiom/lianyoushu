package com.lianji.fitlog;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

final class ProbabilityGauge extends View {
    private static final long FILL_DURATION_MS = 640;
    private static final long WAVE_DURATION_MS = 2300;

    private final Ui ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path circle = new Path();
    private final Path backWave = new Path();
    private final Path frontWave = new Path();
    private final double probability;
    private final String[] labelLines;
    private float centerX;
    private float centerY;
    private float radius;
    private float finalWaterOffset;
    private Shader water;
    private long motionStart = -1;

    ProbabilityGauge(Context context, Journal.Forecast forecast) {
        super(context);
        ui = new Ui(context);
        probability = forecast == null ? Double.NaN : forecast.probability;
        String label = labelFor(context, forecast);
        labelLines = label.split("\n");
        String spoken = label.replace('\n', ' ');
        setContentDescription(Double.isNaN(probability) ? spoken : context.getString(R.string.forecast_probability_a11y, spoken));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    }

    private static String labelFor(Context context, Journal.Forecast forecast) {
        if (forecast == null) return context.getString(R.string.forecast_missing_goal);
        switch (forecast.status) {
            case WAITING:
                return forecast.records < Journal.MIN_FORECAST_RECORDS
                        ? context.getString(R.string.forecast_missing_count, Journal.MIN_FORECAST_RECORDS)
                        : context.getString(R.string.forecast_missing_span, Journal.MIN_FORECAST_SPAN_DAYS);
            case NEEDS_UPDATE:
                return context.getString(R.string.forecast_missing_recent, Journal.MAX_LAST_RECORD_AGE_DAYS);
            case NO_VARIATION:
                return context.getString(R.string.forecast_missing_variation);
            case REACHED:
                return context.getString(R.string.forecast_reached);
            case ESTIMATED:
                double probability = forecast.probability;
                if (probability < 0.05) return context.getString(R.string.forecast_probability_low);
                if (probability > 0.95) return context.getString(R.string.forecast_probability_high);
                long percent = Math.round(probability * 20) * 5;
                return context.getString(R.string.forecast_probability_number, percent);
            default:
                throw new IllegalArgumentException("Unknown forecast status");
        }
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        motionStart = visible && probability > 0 && ValueAnimator.areAnimatorsEnabled()
                ? SystemClock.uptimeMillis() : -1;
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        centerX = width / 2f;
        centerY = height / 2f;
        radius = Math.min(centerX, centerY) - ui.dp(6);
        if (radius <= 0) return;
        circle.reset();
        circle.addCircle(centerX, centerY, radius, Path.Direction.CW);
        fitLabel();
        if (probability > 0) {
            finalWaterOffset = waterOffset(probability);
            water = new LinearGradient(centerX - radius, centerY - radius, centerX + radius, centerY + radius,
                    0xff4e9e89, 0xff155342, Shader.TileMode.CLAMP);
        }
    }

    private void wave(Path path, float y, float amplitude, float phase) {
        float left = centerX - radius - 2 * radius * phase;
        path.reset();
        path.moveTo(left, y);
        float start = left;
        for (int cycle = 0; cycle < 2; cycle++) {
            float middle = start + radius;
            float end = middle + radius;
            path.cubicTo(start + radius / 3, y - amplitude, middle - radius / 3, y - amplitude, middle, y);
            path.cubicTo(middle + radius / 3, y + amplitude, end - radius / 3, y + amplitude, end, y);
            start = end;
        }
        path.lineTo(start, centerY + radius);
        path.lineTo(left, centerY + radius);
        path.close();
    }

    private static float waterOffset(double fraction) {
        if (fraction <= 0) return 1;
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
            boolean moving = motionStart >= 0;
            long elapsed = moving ? SystemClock.uptimeMillis() - motionStart : 0;
            double remaining = moving ? Math.max(0, 1 - (double) elapsed / FILL_DURATION_MS) : 0;
            float phase = (float) (elapsed % WAVE_DURATION_MS) / WAVE_DURATION_MS;
            float offset = remaining == 0 ? finalWaterOffset
                    : waterOffset(probability * (1 - remaining * remaining * remaining));
            float y = centerY + radius * offset;
            float amplitude = Math.min(ui.dp(5), radius * (1 - Math.abs(offset)) * 0.4f);
            wave(backWave, y, -amplitude, phase);
            wave(frontWave, y, amplitude, phase);
            paint.setColor(0xffafd1c2);
            canvas.drawPath(backWave, paint);
            paint.setShader(water);
            canvas.drawPath(frontWave, paint);
            paint.setShader(null);
            if (moving && (remaining > 0 || probability < 1)) postInvalidateOnAnimation();
        }
        drawLabel(canvas, Double.isNaN(probability) ? Ui.MUTED : Ui.FOREST);
        if (probability > 0) {
            canvas.clipPath(frontWave);
            drawLabel(canvas, Ui.WHITE);
        }
        canvas.restoreToCount(saved);
    }

    private void fitLabel() {
        boolean multiline = labelLines.length > 1;
        paint.setTextSize(Math.min(ui.sp(multiline ? 16 : 27), radius * (multiline ? 0.37f : 0.62f)));
        float width = 0;
        for (String line : labelLines) width = Math.max(width, paint.measureText(line));
        float maxWidth = radius * 1.65f;
        if (width > maxWidth) paint.setTextSize(paint.getTextSize() * maxWidth / width);
    }

    private void drawLabel(Canvas canvas, int color) {
        paint.setColor(color);
        float lineHeight = paint.descent() - paint.ascent();
        float baseline = centerY - (paint.ascent() + paint.descent() + (labelLines.length - 1) * lineHeight) / 2;
        for (String line : labelLines) {
            canvas.drawText(line, centerX, baseline, paint);
            baseline += lineHeight;
        }
    }
}
