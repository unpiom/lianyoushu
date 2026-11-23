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
import java.util.function.Consumer;

final class MeasurementChart<T extends Journal.Measurement> extends View {
    private static final DateTimeFormatter AXIS_DATE = DateTimeFormatter.ofPattern("M/d", Locale.SIMPLIFIED_CHINESE);
    private static final int[] TICKS = {0, 7, 14, 21, 29};
    private static final int LOW_POINT_BLUE = 0xff3470b5;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final Path area = new Path();
    private final Ui ui;
    private final List<T> data;
    private final LocalDate start;
    private final Consumer<T> selection;
    private final String metric;
    private final String unit;
    private final double low;
    private final double high;
    private final int gridIntervals;
    private final Journal.Measurement minimum;
    private final Journal.Measurement maximum;
    private int selected = -1;
    private float left;
    private float right;
    private float top;
    private float bottom;

    MeasurementChart(Context context, LocalDate today, List<T> data, String metric, String unit,
                     boolean markExtremes, Consumer<T> selection) {
        super(context);
        ui = new Ui(context);
        start = today.minusDays(29);
        this.data = data;
        this.metric = metric;
        this.unit = unit;
        this.selection = selection;
        Journal.Measurement lowest = null;
        Journal.Measurement highest = null;
        for (Journal.Measurement point : data) {
            if (lowest == null || point.value < lowest.value
                    || (point.value == lowest.value && point.date.isAfter(lowest.date))) lowest = point;
            if (highest == null || point.value > highest.value
                    || (point.value == highest.value && point.date.isAfter(highest.date))) highest = point;
        }
        minimum = markExtremes ? lowest : null;
        maximum = markExtremes ? highest : null;
        if (lowest == null) {
            low = 0;
            high = 1;
            gridIntervals = 3;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        } else {
            double padding = Math.max(0.25, (highest.value - lowest.value) * 0.15);
            double paddedLow = Math.max(0, lowest.value - padding);
            double paddedHigh = highest.value + padding;
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

        if (maximum != null) {
            drawExtremum(canvas, maximum, Ui.RED, maximum == minimum);
            if (maximum != minimum) drawExtremum(canvas, minimum, LOW_POINT_BLUE, false);
        }
    }

    private void drawExtremum(Canvas canvas, Journal.Measurement point, int color, boolean shared) {
        float x = x(point);
        float y = y(point);
        float radius = ui.dp(4);
        paint.setColor(Ui.WHITE);
        canvas.drawCircle(x, y, ui.dp(6), paint);
        paint.setColor(color);
        canvas.drawCircle(x, y, radius, paint);
        if (shared) {
            paint.setColor(LOW_POINT_BLUE);
            canvas.drawArc(x - radius, y - radius, x + radius, y + radius, -90, 180, true, paint);
        }
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
        selection.accept(data.get(index));
        describeSelection();
        invalidate();
    }

    private void describeSelection() {
        Journal.Measurement point = data.get(selected);
        String extremum = point == maximum ? (point == minimum ? "，最高及最低" : "，最高")
                : point == minimum ? "，最低" : "";
        setContentDescription("近 30 天" + metric + "曲线，当前为 " + AXIS_DATE.format(point.date)
                + "，" + MainActivity.kg(point.value) + " " + unit + extremum);
    }
}
