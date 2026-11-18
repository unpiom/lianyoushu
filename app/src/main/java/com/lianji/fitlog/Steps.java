package com.lianji.fitlog;

import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.health.connect.AggregateRecordsGroupedByPeriodResponse;
import android.health.connect.AggregateRecordsRequest;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.LocalTimeRangeFilter;
import android.health.connect.datatypes.StepsRecord;
import android.os.Build;
import android.os.OutcomeReceiver;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import static com.lianji.fitlog.Ui.*;

final class Steps {
    static final String READ_STEPS = "android.permission.health.READ_STEPS";
    static final int REQUEST_PERMISSION = 27;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE);

    private final MainActivity activity;
    private final Ui ui;
    private final Store store;
    private final Editors editors;
    private Map<LocalDate, Long> system = Collections.emptyMap();
    private LocalDate systemDate;
    private int request;
    private boolean loading;
    private boolean readFailed;

    Steps(MainActivity activity, Ui ui, Store store, Editors editors) {
        this.activity = activity;
        this.ui = ui;
        this.store = store;
        this.editors = editors;
    }

    void reload(LocalDate today) {
        int token = ++request;
        readFailed = false;
        loading = false;
        if (!canRead()) {
            system = Collections.emptyMap();
            activity.stepDataChanged();
            return;
        }
        if (!today.equals(systemDate)) system = Collections.emptyMap();
        loading = true;
        activity.stepDataChanged();
        Api34.read(activity, today, data -> {
            if (request != token || activity.isDestroyed() || activity.isFinishing()) return;
            systemDate = today;
            loading = false;
            readFailed = data == null;
            system = data == null || !canRead() ? Collections.emptyMap() : data;
            activity.stepDataChanged();
        });
    }

    View card(LocalDate today) {
        Map<LocalDate, Long> manual = store.manualSteps(today.minusDays(6), today);
        Long count = value(today, manual);
        Long goal = store.stepGoal();
        LinearLayout card = ui.card();
        LinearLayout heading = ui.row();
        heading.addView(ui.icon(Icon.STEPS, GREEN, 20));
        ui.weighted(heading, ui.heading("今日步数", 18), 1, 8);
        heading.addView(ui.secondary("详情", () -> showDetails(LocalDate.now())));
        ui.add(card, heading, 0);

        LinearLayout metrics = ui.row();
        metrics.setBaselineAligned(true);
        TextView number = ui.text(count == null ? "—" : format(count), 30, TEXT, true);
        number.setIncludeFontPadding(false);
        metrics.addView(number);
        TextView unit = ui.text("步", 14, MUTED, false);
        ui.padding(unit, 6, 0, 0, 0);
        metrics.addView(unit);
        ui.spacer(metrics);
        if (count != null) metrics.addView(ui.pill(source(today, manual), TINT, GREEN));
        ui.add(card, metrics, 3);
        if (goal != null) {
            ui.add(card, ui.text("每日目标 " + format(goal) + " 步", 13, MUTED, false), 5);
            ui.progress(card, count == null ? 0 : (int) Math.min(count, goal), goal.intValue(), 8);
        }

        long sum = 0;
        int days = 0;
        for (int offset = 0; offset < 7; offset++) {
            Long steps = value(today.minusDays(offset), manual);
            if (steps != null) {
                sum += steps;
                days++;
            }
        }
        String average = days == 0 ? "近 7 天日均  暂无" : "近 7 天日均  " + format(Math.round((double) sum / days)) + " 步";
        ui.add(card, ui.text(average, 14, MUTED, false), 10);
        LinearLayout actions = ui.row();
        ui.weighted(actions, ui.secondary("手动记步", () -> editors.editSteps(LocalDate.now())), 1, 0);
        ui.weighted(actions, ui.secondary(goal == null ? "设定目标" : "步数目标", editors::editStepGoal), 1, 8);
        ui.add(card, actions, 11);
        return card;
    }

    private void showDetails(LocalDate today) {
        Map<LocalDate, Long> manual = store.manualSteps(today.minusDays(6), today);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = ui.column();
        ui.padding(body, 20, 9, 20, 13);
        scroll.addView(body, new ScrollView.LayoutParams(MATCH, WRAP));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("近 7 天步数")
                .setView(scroll).setPositiveButton("完成", null).create();
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = today.minusDays(offset);
            Long count = value(date, manual);
            LinearLayout row = ui.row();
            row.setMinimumHeight(ui.dp(36));
            ui.weighted(row, ui.text(DAY.format(date), 14, TEXT, false), 1, 0);
            if (count != null) row.addView(ui.pill(source(date, manual), TINT, GREEN));
            TextView number = ui.text(count == null ? "—" : format(count) + " 步", 14, GREEN, true);
            number.setGravity(Gravity.END);
            number.setMinWidth(ui.dp(86));
            row.addView(number);
            ui.add(body, row, 0);
        }
        ui.divider(body, 8);
        ui.add(body, ui.text("日均按已有步数的日期计算，手动步数优先显示。", 13, MUTED, false), 7);
        ui.add(body, ui.secondary("补记步数", () -> {
            dialog.dismiss();
            editors.pickDate(LocalDate.now(), editors::editSteps);
        }), 10);
        if (isAvailable()) {
            String status = !canRead() ? "授权后可读取本机 Health Connect 步数。"
                    : loading ? "正在读取系统步数。"
                    : readFailed ? "系统步数暂时读取失败。"
                    : system.isEmpty() ? "系统步数已授权，等待步数数据。" : "已授权读取系统步数。";
            ui.add(body, ui.text(status, 13, MUTED, false), 9);
            ui.add(body, ui.secondary(canRead() ? "刷新系统步数" : "读取系统步数", () -> {
                dialog.dismiss();
                if (canRead()) reload(LocalDate.now());
                else activity.requestPermissions(new String[]{READ_STEPS}, REQUEST_PERMISSION);
            }), 7);
        } else {
            ui.add(body, ui.text("支持 Health Connect 的 Android 14 及以上设备可读取系统步数。", 13, MUTED, false), 10);
        }
        dialog.show();
    }

    private Long value(LocalDate date, Map<LocalDate, Long> manual) {
        if (manual.containsKey(date)) return manual.get(date);
        return canRead() ? system.get(date) : null;
    }

    private String source(LocalDate date, Map<LocalDate, Long> manual) {
        return manual.containsKey(date) ? "手动" : "系统";
    }

    private boolean isAvailable() {
        return Build.VERSION.SDK_INT >= 34 && Api34.isAvailable(activity);
    }

    private boolean canRead() {
        return isAvailable() && activity.checkSelfPermission(READ_STEPS) == PackageManager.PERMISSION_GRANTED;
    }

    private static String format(long count) {
        return NumberFormat.getIntegerInstance(Locale.US).format(count);
    }

    private static final class Api34 {
        static boolean isAvailable(MainActivity activity) {
            return activity.getSystemService(HealthConnectManager.class) != null;
        }

        static void read(MainActivity activity, LocalDate today, Consumer<Map<LocalDate, Long>> callback) {
            HealthConnectManager manager = activity.getSystemService(HealthConnectManager.class);
            LocalTimeRangeFilter range = new LocalTimeRangeFilter.Builder()
                    .setStartTime(today.minusDays(6).atStartOfDay())
                    .setEndTime(today.plusDays(1).atStartOfDay()).build();
            AggregateRecordsRequest<Long> query = new AggregateRecordsRequest.Builder<Long>(range)
                    .addAggregationType(StepsRecord.STEPS_COUNT_TOTAL).build();
            try {
                manager.aggregateGroupByPeriod(query, Period.ofDays(1), activity.getMainExecutor(),
                        new OutcomeReceiver<List<AggregateRecordsGroupedByPeriodResponse<Long>>, HealthConnectException>() {
                            @Override public void onResult(List<AggregateRecordsGroupedByPeriodResponse<Long>> groups) {
                                Map<LocalDate, Long> result = new TreeMap<>();
                                for (AggregateRecordsGroupedByPeriodResponse<Long> group : groups) {
                                    Long count = group.get(StepsRecord.STEPS_COUNT_TOTAL);
                                    if (count != null) result.put(group.getStartTime().toLocalDate(), count);
                                }
                                callback.accept(result);
                            }

                            @Override public void onError(HealthConnectException error) {
                                Log.w("Lianji", "Unable to read system steps", error);
                                callback.accept(null);
                            }
                        });
            } catch (RuntimeException error) {
                Log.w("Lianji", "Unable to read system steps", error);
                callback.accept(null);
            }
        }
    }
}
