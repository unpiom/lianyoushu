package com.lianji.fitlog;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.lianji.fitlog.Ui.*;

final class Review {
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy年M月", Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter RANGE = DateTimeFormatter.ofPattern("M/d", Locale.SIMPLIFIED_CHINESE);
    private static final int PARTIAL_GREEN = 0xffc7e6cc;
    private final MainActivity activity;
    private final Ui ui;
    private final Store store;
    private final Editors editors;
    private final LocalDate firstInstallDate;
    private LocalDate selected;

    Review(MainActivity activity, Ui ui, Store store, Editors editors, LocalDate firstInstallDate, Bundle saved) {
        this.activity = activity;
        this.ui = ui;
        this.store = store;
        this.editors = editors;
        this.firstInstallDate = firstInstallDate;
        if (saved != null && saved.containsKey("reviewDate")) selected = LocalDate.parse(saved.getString("reviewDate"));
    }

    void save(Bundle state) {
        if (selected != null) state.putString("reviewDate", selected.toString());
    }

    void show(LinearLayout page, LocalDate today, List<Journal.Weight> weights, List<Journal.Plan> plans,
              List<Journal.WorkoutDraft> drafts) {
        if (selected == null || selected.isAfter(today)) selected = today;
        LocalDate monday = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sunday = monday.plusDays(6);
        List<Journal.Session> checkIns = store.checkIns(monday, sunday.isAfter(today) ? today : sunday);
        ui.add(page, weekCard(today, monday, sunday, checkIns, weights), 0);
        YearMonth month = YearMonth.from(selected);
        List<Journal.Session> history = store.checkIns(month.atDay(1), month.atEndOfMonth());
        ui.add(page, calendar(today, month, plans, history, drafts), 13);

        ui.add(page, ui.heading(DAY.format(selected) + "训练", 18), 17);
        List<Journal.Session> day = Journal.sessionsOn(selected, plans, history, drafts);
        day.sort(Comparator.comparing(session -> session.done));
        if (day.isEmpty()) {
            LinearLayout empty = ui.card();
            ui.add(empty, ui.text("这天没有训练安排或打卡记录。", 14, MUTED, false), 0);
            ui.add(page, empty, 8);
        } else {
            for (Journal.Session session : day) {
                ui.add(page, session.done ? sessionCard(session) : activity.trainingCard(session), 8);
            }
        }
        ui.add(page, waistCard(today), 13);
    }

    private View weekCard(LocalDate today, LocalDate monday, LocalDate sunday, List<Journal.Session> checks,
                          List<Journal.Weight> weights) {
        LinearLayout card = ui.card();
        LinearLayout title = ui.row();
        ui.weighted(title, ui.heading(today.isAfter(sunday) ? "当周复盘" : "本周复盘", 18), 1, 0);
        title.addView(ui.pill(RANGE.format(monday) + "—" + RANGE.format(sunday), TINT, GREEN));
        ui.add(card, title, 0);

        long elapsed = 0;
        int timed = 0;
        for (Journal.Session session : checks) {
            if (session.durationSeconds != null) {
                timed++;
                elapsed += session.durationSeconds;
            }
        }
        LinearLayout stats = ui.row();
        stats.setGravity(Gravity.TOP);
        ui.weighted(stats, metric("完成训练", checks.size() + " 次"), 0.86f, 0);
        ui.weighted(stats, metric("实际训练时长", timed == 0 ? "—" : Ui.duration(elapsed)), 1.14f, 10);
        ui.add(card, stats, 13);
        if (timed < checks.size()) {
            ui.add(card, ui.text(timed + " / " + checks.size() + " 次训练有时长记录", 12, MUTED, false), 8);
        }

        ui.divider(card, 13);
        LocalDate end = sunday.isAfter(today) ? today : sunday;
        LocalDate first = end.minusDays(6);
        LocalDate previous = first.minusDays(1);
        double newer = Journal.average(Journal.between(weights, first, end));
        double older = Journal.average(Journal.between(weights, previous.minusDays(6), previous));
        LinearLayout averages = ui.row();
        averages.setGravity(Gravity.TOP);
        ui.weighted(averages, metric("近 7 天均重", weight(newer)), 1, 0);
        ui.weighted(averages, metric("前 7 天均重", weight(older)), 1, 10);
        ui.add(card, averages, 10);
        if (!Double.isNaN(newer) && !Double.isNaN(older)) {
            double difference = Math.round((newer - older) * 100) / 100.0;
            ui.add(card, ui.text("两段均重变化  " + (difference > 0 ? "+" : "") + MainActivity.kg(difference) + " kg",
                    13, GREEN, true), 6);
        }
        return card;
    }

    private LinearLayout metric(String label, String value) {
        LinearLayout result = ui.column();
        ui.add(result, ui.text(label, 13, MUTED, false), 0);
        ui.add(result, ui.text(value, 20, TEXT, true), 3);
        return result;
    }

    private String weight(double kg) {
        return Double.isNaN(kg) ? "—" : MainActivity.kg(kg) + " kg";
    }

    private View calendar(LocalDate today, YearMonth month, List<Journal.Plan> plans, List<Journal.Session> history,
                          List<Journal.WorkoutDraft> drafts) {
        LinearLayout card = ui.card();
        LinearLayout toolbar = ui.row();
        Button previous = ui.secondary("‹", () -> changeMonth(month.minusMonths(1), today));
        previous.setContentDescription("上个月");
        toolbar.addView(previous, new LinearLayout.LayoutParams(ui.dp(48), WRAP));
        TextView title = ui.heading(MONTH.format(month), 18);
        title.setGravity(Gravity.CENTER);
        ui.weighted(toolbar, title, 1, 0);
        Button next = ui.secondary("›", () -> changeMonth(month.plusMonths(1), today));
        next.setContentDescription("下个月");
        boolean hasNext = month.isBefore(YearMonth.from(today));
        next.setEnabled(hasNext);
        next.setAlpha(hasNext ? 1f : 0.45f);
        toolbar.addView(next, new LinearLayout.LayoutParams(ui.dp(48), WRAP));
        ui.add(card, toolbar, 0);

        LinearLayout titles = ui.row();
        for (String label : MainActivity.DAYS) {
            TextView cell = ui.text(label, 12, MUTED, true);
            cell.setGravity(Gravity.CENTER);
            ui.weighted(titles, cell, 1, 0);
        }
        ui.add(card, titles, 8);
        int offset = month.atDay(1).getDayOfWeek().getValue() - 1;
        for (int rowIndex = 0; rowIndex * 7 < offset + month.lengthOfMonth(); rowIndex++) {
            LinearLayout row = ui.row();
            for (int column = 0; column < 7; column++) {
                int day = rowIndex * 7 + column - offset + 1;
                View cell;
                if (day < 1 || day > month.lengthOfMonth()) cell = new Space(activity);
                else {
                    LocalDate date = month.atDay(day);
                    boolean beforeInstall = date.isBefore(firstInstallDate);
                    List<Journal.Session> sessions = beforeInstall || date.isAfter(today) ? Collections.emptyList()
                            : Journal.sessionsOn(date, plans, history, drafts);
                    cell = dayCell(date, today, beforeInstall, sessions);
                }
                ui.weighted(row, cell, 1, column == 0 ? 0 : 2);
            }
            ui.add(card, row, 3);
        }
        return card;
    }

    private View dayCell(LocalDate date, LocalDate today, boolean beforeInstall, List<Journal.Session> sessions) {
        boolean active = date.equals(selected);
        boolean future = date.isAfter(today);
        int fill = date.equals(today) ? BACKGROUND : WHITE;
        int color = future ? 0xff87978d : TEXT;
        String status = beforeInstall ? "安装前" : future ? "日期尚未到来" : "无训练安排";
        int planned = 0;
        int completed = 0;
        boolean hasTemporary = false;
        for (Journal.Session session : sessions) {
            if (session.plan.temporary) {
                hasTemporary |= session.done;
            } else {
                planned++;
                if (session.done) completed++;
            }
        }
        if (planned > 0) {
            if (completed == planned) {
                fill = GREEN;
                color = WHITE;
                status = "训练计划全部完成";
            } else if (completed == 0) {
                fill = RED;
                color = WHITE;
                status = "训练计划均未完成";
            } else {
                fill = PARTIAL_GREEN;
                status = "部分训练计划已完成";
            }
        }
        if (hasTemporary) status += "，有临时训练记录";

        LinearLayout cell = ui.column();
        cell.setGravity(Gravity.CENTER);
        cell.setMinimumHeight(ui.dp(44));
        cell.setBackground(ui.ripple(fill, active ? FOREST : 0, 10));
        cell.setSelected(active);
        cell.setContentDescription(MainActivity.FULL_DATE.format(date) + "，" + status);
        if (!future) {
            cell.setFocusable(true);
            cell.setOnClickListener(view -> {
                if (!active) {
                    selected = date;
                    activity.refresh();
                }
            });
        }
        TextView number = ui.text(Integer.toString(date.getDayOfMonth()), 13, color, true);
        number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cell.addView(number, new LinearLayout.LayoutParams(WRAP, WRAP));
        View selectionDot = new View(activity);
        if (active || hasTemporary) {
            int dot = active || fill == GREEN || fill == RED ? color : GREEN;
            selectionDot.setBackground(ui.shape(dot, 0, 4));
        }
        LinearLayout.LayoutParams spot = new LinearLayout.LayoutParams(ui.dp(5), ui.dp(5));
        spot.topMargin = ui.dp(1);
        cell.addView(selectionDot, spot);
        return cell;
    }

    private void changeMonth(YearMonth next, LocalDate today) {
        LocalDate date = next.atDay(Math.min(selected.getDayOfMonth(), next.lengthOfMonth()));
        selected = date.isAfter(today) ? today : date;
        activity.refresh();
    }

    private View sessionCard(Journal.Session session) {
        LinearLayout card = ui.card();
        LinearLayout title = ui.row();
        ui.weighted(title, ui.heading(session.plan.name, 17), 1, 0);
        if (session.plan.temporary) title.addView(ui.pill("临时训练", TINT, GREEN));
        ui.add(card, title, 0);
        String duration = session.durationSeconds == null ? "暂无时长记录" : Ui.duration(session.durationSeconds);
        String subtitle = session.sets.isEmpty() ? duration : duration + " · 完成 " + session.sets.size() + " 组";
        ui.add(card, ui.text(subtitle, 14, MUTED, false), 4);
        if (!session.sets.isEmpty()) ui.add(card, ui.secondary("查看逐组记录", () -> sessionDetails(session)), 8);
        return card;
    }

    private void sessionDetails(Journal.Session session) {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = ui.column();
        ui.padding(body, 20, 12, 20, 12);
        scroll.addView(body, new ScrollView.LayoutParams(MATCH, WRAP));
        for (int index = 0; index < session.plan.exercises.size(); index++) {
            int number = 0;
            StringBuilder summary = new StringBuilder();
            for (Journal.SetLog log : session.sets) {
                if (log.exerciseIndex != index) continue;
                if (number > 0) summary.append('\n');
                summary.append("第 ").append(++number).append(" 组    ").append(WorkoutActivity.setSummary(log));
            }
            if (number > 0) {
                ui.add(body, ui.heading(session.plan.exercises.get(index).name, 16), 9);
                TextView sets = ui.text(summary.toString(), 14, MUTED, false);
                sets.setLineSpacing(ui.dp(4), 1f);
                ui.add(body, sets, 6);
            }
        }
        new AlertDialog.Builder(activity).setTitle(session.plan.name).setView(scroll).setPositiveButton("完成", null).show();
    }

    private View waistCard(LocalDate today) {
        List<Journal.Measurement> readings = Journal.between(store.waistReadings(), today.minusDays(29), today);
        LinearLayout card = ui.card();
        LinearLayout title = ui.row();
        ui.weighted(title, ui.heading("近 30 天腰围", 18), 1, 0);
        title.addView(ui.pill("cm", TINT, GREEN));
        ui.add(card, title, 0);
        if (readings.isEmpty()) {
            ui.add(card, ui.text("记录腰围后生成曲线。", 14, MUTED, false), 8);
            return card;
        }
        TextView selection = ui.text("", 14, GREEN, true);
        showWaistValue(selection, readings.get(readings.size() - 1));
        ui.add(card, selection, 8);
        MeasurementChart<Journal.Measurement> chart = new MeasurementChart<>(activity, today, readings, "腰围", "厘米",
                false, value -> showWaistValue(selection, value));
        LinearLayout.LayoutParams chartSize = new LinearLayout.LayoutParams(MATCH, ui.dp(149));
        chartSize.topMargin = ui.dp(4);
        card.addView(chart, chartSize);
        return card;
    }

    private void showWaistValue(TextView label, Journal.Measurement value) {
        label.setText(DAY.format(value.date) + "  ·  " + MainActivity.kg(value.value) + " cm");
        ui.editNumber(label, "编辑腰围", () -> editors.editWaist(value.date));
    }
}
