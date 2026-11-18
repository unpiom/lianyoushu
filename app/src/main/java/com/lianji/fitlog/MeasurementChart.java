package com.lianji.fitlog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

final class MeasurementChart extends View {
    interface Selection {
        void onSelected(Journal.Measurement reading);
    }

    private static final DateTimeFormatter AXIS_DATE = DateTimeFormatter.ofPattern("M/d", Locale.SIMPLIFIED_CHINESE);
    private static final int[] TICKS = {0, 7, 14, 21, 29};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final Path area = new Path();
    private final Ui ui;
    private final List<? extends Journal.Measurement> data;
    private final LocalDate start;
    private final Selection selection;
    private final String metric;
    private final String unit;
    private final double low;
    private final double high;
    private final int gridIntervals;
    private int selected = -1;
    private float left;
    private float right;
    private float top;
    private float bottom;

    MeasurementChart(Context context, LocalDate today, List<? extends Journal.Measurement> data,
                     String metric, String unit, Selection selection) {
        super(context);
        ui = new Ui(context);
        start = today.minusDays(29);
        this.data = data;
        this.metric = metric;
        this.unit = unit;
        this.selection = selection;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Journal.Measurement point : data) {
            min = Math.min(min, point.value);
            max = Math.max(max, point.value);
        }
        if (data.isEmpty()) {
            low = 0;
            high = 1;
            gridIntervals = 3;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        } else {
            double padding = Math.max(0.25, (max - min) * 0.15);
            double paddedLow = Math.max(0, min - padding);
            double paddedHigh = max + padding;
            double step = gridStep((paddedHigh - paddedLow) / 3);
            low = Math.floor(paddedLow / step) * step;
            high = Math.ceil(paddedHigh / step) * step;
            gridIntervals = (int) Math.round((high - low) / step);
            selected = data.size() - 1;
            setFocusable(true);
            setClickable(true);
            describeSelection();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setTextSize(ui.sp(10));
        left = data.isEmpty() ? ui.dp(7) : Math.max(ui.dp(32), paint.measureText(tickLabel(high)) + ui.dp(8));
        right = getWidth() - ui.dp(8);
        top = ui.dp(12);
        bottom = getHeight() - ui.dp(25);
        if (right <= left || bottom <= top) return;

        for (int i = 0; i <= gridIntervals; i++) {
            float y = top + (bottom - top) * i / gridIntervals;
            paint.setColor(Ui.LINE);
            paint.setStrokeWidth(ui.dp(1));
            canvas.drawLine(left, y, right, y, paint);
            if (!data.isEmpty()) {
                paint.setColor(Ui.MUTED);
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(tickLabel(high - (high - low) * i / gridIntervals), left - ui.dp(7), y + ui.dp(4), paint);
            }
        }
        paint.setColor(Ui.MUTED);
        for (int tick : TICKS) {
            float x = left + (right - left) * tick / 29;
            paint.setTextAlign(tick == 0 ? Paint.Align.LEFT : tick == 29 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(AXIS_DATE.format(start.plusDays(tick)), x, getHeight() - ui.dp(6), paint);
        }
        if (data.isEmpty()) return;

        line.reset();
        for (int i = 0; i < data.size(); i++) {
            Journal.Measurement point = data.get(i);
            if (i == 0) line.moveTo(x(point), y(point));
            else line.lineTo(x(point), y(point));
        }
        if (data.size() > 1) {
            area.set(line);
            area.lineTo(x(data.get(data.size() - 1)), bottom);
            area.lineTo(x(data.get(0)), bottom);
            area.close();
            paint.setShader(new LinearGradient(0, top, 0, bottom, 0x552a8b63, 0x052a8b63, Shader.TileMode.CLAMP));
            canvas.drawPath(area, paint);
            paint.setShader(null);
        }
        paint.setColor(Ui.GREEN);
        paint.setStrokeWidth(ui.dp(2.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(line, paint);
        paint.setStyle(Paint.Style.FILL);
        for (Journal.Measurement point : data) canvas.drawCircle(x(point), y(point), ui.dp(2.6f), paint);

        Journal.Measurement point = data.get(selected);
        paint.setColor(0xffa7cbb7);
        paint.setStrokeWidth(ui.dp(1));
        canvas.drawLine(x(point), y(point), x(point), bottom, paint);
        paint.setColor(Ui.WHITE);
        canvas.drawCircle(x(point), y(point), ui.dp(6), paint);
        paint.setColor(Ui.GREEN);
        canvas.drawCircle(x(point), y(point), ui.dp(4), paint);
    }

    private static double gridStep(double approximate) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(approximate)));
        double normalized = approximate / magnitude;
        if (normalized <= 1) return magnitude;
        if (normalized <= 2) return magnitude * 2;
        if (normalized <= 5) return magnitude * 5;
        return magnitude * 10;
    }

    private static String tickLabel(double kg) {
        return String.format(Locale.US, "%.1f", kg);
    }

    private float x(Journal.Measurement reading) {
        return left + (right - left) * ChronoUnit.DAYS.between(start, reading.date) / 29;
    }

    private float y(Journal.Measurement reading) {
        return (float) (bottom - (bottom - top) * (reading.value - low) / (high - low));
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (data.isEmpty()) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            int nearest = 0;
            for (int i = 1; i < data.size(); i++) {
                if (Math.abs(x(data.get(i)) - event.getX()) < Math.abs(x(data.get(nearest)) - event.getX())) nearest = i;
            }
            select(nearest);
            super.performClick();
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        if (data.isEmpty()) return false;
        select((selected + 1) % data.size());
        return true;
    }

    private void select(int index) {
        selected = index;
        selection.onSelected(data.get(index));
        describeSelection();
        invalidate();
    }

    private void describeSelection() {
        Journal.Measurement point = data.get(selected);
        setContentDescription("近 30 天" + metric + "曲线，当前为 " + AXIS_DATE.format(point.date)
                + "，" + MainActivity.kg(point.value) + " " + unit);
    }
}
