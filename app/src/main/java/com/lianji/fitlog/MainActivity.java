package com.lianji.fitlog;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.LocaleList;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;

import static com.lianji.fitlog.Ui.*;

public final class MainActivity extends Activity {
    static final String[] DAYS = {"一", "二", "三", "四", "五", "六", "日"};
    static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("M/d", Locale.SIMPLIFIED_CHINESE);
    private static final DateTimeFormatter HEADER_DATE = DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE);
    private static final DecimalFormat KG = new DecimalFormat("0.0#", DecimalFormatSymbols.getInstance(Locale.US));
    private static final int TODAY = 0;
    private static final int WEIGHT = 1;
    private static final int TRAINING = 2;
    private static final int REVIEW = 3;
    private static final int WORKOUT_REQUEST = 42;
    private static final int DEFAULT_FORECAST_DAYS = 28;
    private static final String FORECAST_DEADLINE = "forecast_deadline";

    private Ui ui;
    private Store store;
    private Editors editors;
    private Review review;
    private PlanFiles planFiles;
    private Updates updates;
    private ScrollView scroll;
    private LocalDate today;
    private LocalDate selectedDay;
    private List<Journal.Weight> weights;
    private List<Journal.Plan> plans;
    private List<Journal.WorkoutDraft> drafts;
    private Journal.Week week;
    private Double todayWeight;
    private Double goal;
    private double weekAverage;
    private int tab = TODAY;
    private int historyLimit = 12;
    private boolean returning;

    @Override protected void attachBaseContext(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocales(new LocaleList(Locale.SIMPLIFIED_CHINESE));
        super.attachBaseContext(base.createConfigurationContext(config));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        store = new Store(this);
        editors = new Editors(this, ui, store);
        updates = new Updates(this, state);
        review = new Review(this, ui, store, editors, updates.firstInstallDate(), state);
        planFiles = new PlanFiles(this);
        int previousScroll = 0;
        if (state != null) {
            tab = state.getInt("tab", TODAY);
            selectedDay = LocalDate.parse(state.getString("day", LocalDate.now().toString()));
            historyLimit = state.getInt("historyLimit", 12);
            previousScroll = state.getInt("scroll", 0);
        }
        Ui.configureWindow(getWindow());
        render(previousScroll);
    }

    @Override protected void onResume() {
        super.onResume();
        if (returning || !LocalDate.now().equals(today)) refresh();
        returning = false;
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        updates.showNewVersionAnnouncement();
    }

    @Override protected void onPause() {
        returning = true;
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("tab", tab);
        state.putString("day", selectedDay.toString());
        state.putInt("historyLimit", historyLimit);
        state.putInt("scroll", scroll.getScrollY());
        review.save(state);
        updates.save(state);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        updates.close();
        store.close();
        super.onDestroy();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WORKOUT_REQUEST && resultCode == RESULT_OK && data != null) {
            trainingSaved(LocalDate.parse(data.getStringExtra(WorkoutActivity.DATE)));
        }
        if (requestCode == Updates.REQUEST_INSTALL_PERMISSION) updates.onInstallPermissionResult();
        planFiles.onActivityResult(requestCode, resultCode, data);
    }

    void refresh() {
        render(scroll.getScrollY());
    }

    void reveal(View view) {
        ScrollView current = scroll;
        Runnable scrollToView = () -> {
            if (current != scroll) return;
            Rect bounds = new Rect();
            view.getDrawingRect(bounds);
            current.offsetDescendantRectToMyCoords(view, bounds);
            current.smoothScrollTo(0, bounds.top - ui.dp(6));
        };
        if (view.isLaidOut()) scrollToView.run();
        else view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View child, int left, int top, int right, int bottom,
                                                 int oldLeft, int oldTop, int oldRight, int oldBottom) {
                child.removeOnLayoutChangeListener(this);
                current.post(scrollToView);
            }
        });
    }

    boolean write(Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException error) {
            Log.e("Lianji", "Unable to write journal", error);
            Toast.makeText(this, "未能保存记录，请检查存储空间后重试。", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void render(int previousScroll) {
        today = LocalDate.now();
        weights = store.weights();
        Journal.Weight daily = store.weight(today);
        todayWeight = daily == null ? null : daily.value;
        goal = store.goal();
        plans = store.plans();
        drafts = store.drafts();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        week = new Journal.Week(today, plans, store.checkIns(monday, monday.plusDays(6)));
        if (selectedDay == null || selectedDay.isBefore(monday) || selectedDay.isAfter(monday.plusDays(6))) {
            selectedDay = today;
        }
        weekAverage = Journal.average(Journal.between(weights, today.minusDays(6), today));

        LinearLayout root = ui.column();
        root.setBackgroundColor(BACKGROUND);
        Ui.fitInsets(root, false);
        addCentered(root, header(), WRAP, 0);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        FrameLayout pageFrame = new FrameLayout(this);
        LinearLayout page = ui.constrainedColumn();
        ui.padding(page, 20, 5, 20, 22);
        pageFrame.addView(page, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addView(pageFrame, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        ui.divider(root, 0);
        FrameLayout navFrame = addCentered(root, navigation(), WRAP, 0);
        navFrame.setBackgroundColor(WHITE);
        if (tab == TODAY) showToday(page);
        else if (tab == WEIGHT) showWeight(page);
        else if (tab == TRAINING) showTraining(page);
        else review.show(page, today, weights, plans, drafts);

        setContentView(root);
        root.requestApplyInsets();
        ScrollView currentScroll = scroll;
        if (previousScroll > 0) currentScroll.post(() -> currentScroll.scrollTo(0, previousScroll));
    }

    private FrameLayout addCentered(LinearLayout parent, View child, int height, int marginTop) {
        FrameLayout frame = new FrameLayout(this);
        frame.addView(child, new FrameLayout.LayoutParams(MATCH, height, Gravity.CENTER_HORIZONTAL));
        ui.add(parent, frame, marginTop);
        return frame;
    }

    private View header() {
        LinearLayout holder = ui.constrainedColumn();
        ui.padding(holder, 20, 9, 20, 8);
        LinearLayout header = ui.row();
        header.setMinimumHeight(ui.dp(49));
        ui.add(holder, header, 0);
        if (tab == TODAY) {
            ImageView mark = new ImageView(this);
            mark.setImageResource(getApplicationInfo().icon);
            mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            header.addView(mark, new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));
            String name = getApplicationInfo().loadLabel(getPackageManager()).toString();
            ui.weighted(header, ui.heading(name, 25), 1, 9);
            header.addView(ui.pill(HEADER_DATE.format(today) + " · " + weekday(today), TINT, GREEN));
        } else if (tab == WEIGHT) {
            ui.weighted(header, ui.heading("体重", 26), 1, 0);
            header.addView(ui.button("补记", TINT, GREEN, 0, editors::pickWeightDate));
        } else if (tab == TRAINING) {
            ui.weighted(header, ui.heading("训练", 26), 1, 0);
            header.addView(ui.primary("＋ 新建计划", () -> editors.editPlan(null)));
        } else {
            ui.weighted(header, ui.heading("复盘", 26), 1, 0);
            header.addView(ui.secondary("记录腰围", () -> editors.pickDate(LocalDate.now(), editors::editWaist)));
        }
        return holder;
    }

    private LinearLayout navigation() {
        LinearLayout holder = ui.constrainedColumn();
        LinearLayout row = ui.row();
        ui.padding(row, 14, 5, 14, 4);
        ui.add(holder, row, 0);
        String[] destinations = {"今天", "体重", "训练", "复盘"};
        Icon[] icons = {Icon.HOME, Icon.SCALE, Icon.TRAINING, Icon.CALENDAR};
        for (int i = 0; i < destinations.length; i++) {
            final int destination = i;
            boolean active = tab == i;
            LinearLayout item = ui.column();
            item.setGravity(Gravity.CENTER);
            item.setMinimumHeight(ui.dp(54));
            item.setBackground(ui.ripple(WHITE, 0, 14));
            item.setSelected(active);
            item.setFocusable(true);
            item.setContentDescription(destinations[i]);
            item.setOnClickListener(view -> switchTab(destination));
            FrameLayout symbol = new FrameLayout(this);
            symbol.setBackground(ui.shape(active ? TINT : WHITE, 0, 12));
            symbol.addView(ui.icon(icons[i], active ? GREEN : MUTED, 21),
                    new FrameLayout.LayoutParams(ui.dp(21), ui.dp(21), Gravity.CENTER));
            item.addView(symbol, new LinearLayout.LayoutParams(ui.dp(51), ui.dp(29)));
            TextView label = ui.text(destinations[i], 12, active ? GREEN : MUTED, active);
            label.setGravity(Gravity.CENTER);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ui.add(item, label, 0);
            ui.weighted(row, item, 1, 0);
        }
        return holder;
    }

    private void switchTab(int next) {
        if (tab != next) {
            tab = next;
            render(0);
        }
    }

    private void showToday(LinearLayout page) {
        LinearLayout hero = ui.column();
        hero.setBackground(ui.shape(FOREST, 0, 24));
        ui.padding(hero, 18, 16, 18, 16);
        LinearLayout labelRow = ui.row();
        ui.weighted(labelRow, ui.text("今日体重", 15, PALE, true), 1, 0);
        labelRow.addView(ui.pill(todayWeight == null ? "待记录" : "已确认", 0xff2d5345, LIME));
        ui.add(hero, labelRow, 0);
        ui.add(hero, weightValue(todayWeight == null ? Double.NaN : todayWeight, 42, WHITE, PALE), 2);
        String average = Double.isNaN(weekAverage) ? "暂无" : kg(weekAverage) + " kg";
        ui.add(hero, ui.text("7 天均重  " + average, 14, PALE, false), 2);

        ui.add(hero, ui.text("(建议早晨起床，空腹称重!)", 12, PALE, true), 9);

        LinearLayout actions = ui.row();
        if (todayWeight == null) {
            ui.weighted(actions, ui.button("记录体重", LIME, FOREST, 0, () -> editors.editWeight(LocalDate.now())), 2, 0);
        }
        ui.weighted(actions, ui.button(todayWeight == null ? "补记" : "补记历史体重", 0xff285344, WHITE, 0xff426e5b,
                editors::pickWeightDate), 1, todayWeight == null ? 9 : 0);
        ui.add(hero, actions, 12);
        ui.add(page, hero, 0);

        Journal.Forecast forecast = goal == null ? null
                : Journal.forecast(weights, today, goal, preferredForecastDeadline(today));
        ui.add(page, forecastCard(forecast), 13);
        ui.add(page, probabilityCard(forecast), 10);
        LinearLayout version = ui.row();
        ui.weighted(version, ui.text("版本 " + updates.currentVersion(), 13, MUTED, false), 1, 0);
        version.addView(ui.secondary("检查更新", updates::check));
        ui.add(page, version, 13);
        LinearLayout project = ui.row();
        Button projectLink = ui.secondary("GitHub 项目主页", updates::openProject);
        ui.padding(projectLink, 8, 8, 8, 8);
        project.addView(projectLink);
        TextView starHint = ui.text("觉得好用，欢迎给项目点个 Star。", 13, MUTED, false);
        starHint.setMaxLines(1);
        starHint.setEllipsize(TextUtils.TruncateAt.END);
        starHint.setGravity(Gravity.CENTER_VERTICAL);
        starHint.setAutoSizeTextTypeUniformWithConfiguration(8, 13, 1, TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(0, ui.dp(48), 1);
        hintParams.leftMargin = ui.dp(6);
        project.addView(starHint, hintParams);
        ui.add(page, project, 8);
        TextView privacy = ui.text("卸载会清除应用中的记录", 12, MUTED, false);
        privacy.setGravity(Gravity.CENTER);
        ui.add(page, privacy, 16);
    }

    private View weightValue(double value, int size, int color, int unitColor) {
        LinearLayout row = ui.row();
        row.setBaselineAligned(true);
        row.setGravity(Gravity.BOTTOM);
        TextView number = ui.text(Double.isNaN(value) ? "—" : kg(value), size, color, true);
        number.setIncludeFontPadding(false);
        row.addView(number);
        TextView unit = ui.text("kg", 14, unitColor, true);
        unit.setIncludeFontPadding(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP, WRAP);
        params.leftMargin = ui.dp(6);
        row.addView(unit, params);
        return row;
    }

    private View forecastCard(Journal.Forecast forecast) {
        LinearLayout card = ui.card();
        LinearLayout bar = ui.row();
        bar.addView(ui.icon(Icon.TARGET, GREEN, 20));
        ui.weighted(bar, ui.heading(getString(R.string.forecast_title), 18), 1, 8);
        if (forecast == null) bar.addView(ui.secondary(getString(R.string.forecast_set_goal), editors::editGoal));
        ui.add(card, bar, 0);
        if (forecast == null) {
            ui.add(card, ui.text(getString(R.string.forecast_setup_hint), 14, MUTED, false), 8);
            return card;
        }

        LinearLayout target = ui.row();
        ui.weighted(target, ui.text(getString(R.string.forecast_goal_weight), 14, MUTED, false), 1, 0);
        TextView goalValue = ui.pill(kg(goal) + " kg", TINT, GREEN);
        goalValue.setContentDescription(getString(R.string.forecast_goal_weight_a11y, kg(goal)));
        ui.editNumber(goalValue, getString(R.string.forecast_edit_goal), editors::editGoal);
        target.addView(goalValue);
        ui.add(card, target, 6);
        boolean estimated = forecast.status == Journal.ForecastStatus.ESTIMATED;
        boolean reached = forecast.status == Journal.ForecastStatus.REACHED;
        if (estimated || reached) {
            boolean limited = forecast.date == null;
            LocalDate date = limited ? today.plusDays(forecast.maxDays) : forecast.date;
            int label = reached ? R.string.forecast_recorded_date
                    : limited ? R.string.forecast_latest_date : R.string.forecast_estimated_date;
            ui.add(card, ui.text(getString(label), 13, MUTED, false), 10);
            ui.add(card, ui.text(FULL_DATE.format(date), 22, GREEN, true), 3);
            if (estimated) {
                String detail = getString(limited ? R.string.forecast_below_half : R.string.forecast_first_half);
                String trend = getString(R.string.forecast_weight_trend, forecast.kgPerWeek);
                ui.add(card, ui.text(detail  + trend, 13, MUTED, false), 5);
            }
        }
        return card;
    }

    private LocalDate preferredForecastDeadline(LocalDate asOf) {
        LocalDate defaultDate = asOf.plusDays(DEFAULT_FORECAST_DAYS);
        long saved = getPreferences(MODE_PRIVATE).getLong(FORECAST_DEADLINE, defaultDate.toEpochDay());
        long days = saved - asOf.toEpochDay();
        return days >= 0 && days <= Journal.MAX_FORECAST_DAYS ? LocalDate.ofEpochDay(saved) : defaultDate;
    }

    private void pickForecastDeadline(Journal.Forecast displayed) {
        LocalDate now = LocalDate.now();
        Journal.Forecast forecast = now.equals(today) ? displayed
                : Journal.forecast(weights, now, goal, preferredForecastDeadline(now));
        editors.pickDate(forecast.deadline, now, now.plusDays(forecast.maxDays), deadline -> {
            getPreferences(MODE_PRIVATE).edit().putLong(FORECAST_DEADLINE, deadline.toEpochDay()).apply();
            refresh();
        });
    }

    private View probabilityCard(Journal.Forecast forecast) {
        LinearLayout card = ui.row();
        card.setBackground(ui.shape(0xfff1f7f0, 0xffdce9df, 20));
        ui.padding(card, 16, 12, 13, 12);
        LinearLayout detail = ui.column();
        ui.add(detail, ui.heading(getString(R.string.forecast_probability_title), 18), 0);
        if (forecast != null) {
            Button selectDate = ui.secondary(getString(R.string.forecast_deadline_button, HEADER_DATE.format(forecast.deadline)),
                    () -> pickForecastDeadline(forecast));
            selectDate.setContentDescription(getString(R.string.forecast_deadline_a11y, FULL_DATE.format(forecast.deadline)));
            ui.add(detail, selectDate, 5);
            if (forecast.status == Journal.ForecastStatus.ESTIMATED) {
                String range = getString(R.string.forecast_range, Journal.FORECAST_WINDOW_DAYS, forecast.maxDays);
                ui.add(detail, ui.text(range, 12, MUTED, false), 5);
            }
        }
        ui.weighted(card, detail, 1, 0);
        LinearLayout.LayoutParams gaugeSize = new LinearLayout.LayoutParams(ui.dp(102), ui.dp(102));
        gaugeSize.leftMargin = ui.dp(8);
        card.addView(new ProbabilityGauge(this, forecast), gaugeSize);
        return card;
    }

    private void showWeight(LinearLayout page) {
        if (todayWeight == null) ui.add(page, ui.primary("记录今日体重", () -> editors.editWeight(LocalDate.now())), 0);
        List<Journal.Weight> recent = Journal.between(weights, today.minusDays(29), today);
        LinearLayout chartCard = ui.card();
        LinearLayout chartTitle = ui.row();
        ui.weighted(chartTitle, ui.heading("近 30 天体重", 18), 1, 0);
        chartTitle.addView(ui.pill("kg", TINT, GREEN));
        ui.add(chartCard, chartTitle, 0);

        FrameLayout chartArea = new FrameLayout(this);
        TextView selection = ui.text("", 14, MUTED, true);
        if (!recent.isEmpty()) {
            showChartValue(selection, recent.get(recent.size() - 1));
            ui.add(chartCard, selection, 7);
        }
        chartArea.addView(new MeasurementChart<>(this, today, recent, "体重", "公斤", true,
                        value -> showChartValue(selection, value)),
                new FrameLayout.LayoutParams(MATCH, MATCH));
        if (recent.isEmpty()) {
            TextView empty = ui.pill("记录体重后生成曲线", BACKGROUND, MUTED);
            chartArea.addView(empty, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        }
        LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(MATCH, ui.dp(164));
        chartParams.topMargin = ui.dp(5);
        chartCard.addView(chartArea, chartParams);
        ui.divider(chartCard, 7);

        LinearLayout stats = ui.row();
        ui.weighted(stats, ui.text("7 天均重", 15, TEXT, true), 1, 0);
        stats.addView(weightValue(weekAverage, 21, GREEN, MUTED));
        ui.add(chartCard, stats, 10);
        ui.add(chartCard, ui.text("按最近 7 个自然日内的已记录体重计算", 12, MUTED, false), 5);
        ui.add(page, chartCard, todayWeight == null ? 12 : 0);

        ui.add(page, ui.heading("记录历史", 18), 19);
        LinearLayout history = ui.card();
        if (weights.isEmpty()) ui.add(history, ui.text("暂无体重记录。", 14, MUTED, false), 0);
        for (int i = weights.size() - 1; i >= 0 && i >= weights.size() - historyLimit; i--) {
            Journal.Weight weight = weights.get(i);
            boolean editable = canEdit(weight);
            LinearLayout row = ui.row();
            row.setMinimumHeight(ui.dp(48));
            TextView date = ui.text(FULL_DATE.format(weight.date), 14, TEXT, false);
            date.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ui.weighted(row, date, 1, 0);
            TextView value = ui.text(kg(weight.value) + " kg", 15, editable ? GREEN : TEXT, true);
            value.setContentDescription(FULL_DATE.format(weight.date) + "，" + kg(weight.value) + " 公斤"
                    + (editable ? "" : "，已确认"));
            if (editable) {
                ui.padding(value, 8, 8, 0, 8);
                ui.editNumber(value, "编辑体重", () -> editors.editWeight(weight.date));
            }
            row.addView(value);
            if (i < weights.size() - 1) ui.divider(history, 0);
            ui.add(history, row, 0);
        }
        if (weights.size() > historyLimit) ui.add(history, ui.secondary("查看更早记录", () -> {
            historyLimit += 12;
            refresh();
        }), 8);
        ui.add(page, history, 8);
    }

    private boolean canEdit(Journal.Weight weight) {
        return !weight.locked && weight.date.isBefore(today);
    }

    private void showChartValue(TextView label, Journal.Weight weight) {
        label.setText(HEADER_DATE.format(weight.date) + "  ·  " + kg(weight.value) + " kg");
        label.setTextColor(canEdit(weight) ? GREEN : MUTED);
        ui.editNumber(label, "编辑体重", canEdit(weight) ? () -> editors.editWeight(weight.date) : null);
    }

    private void showTraining(LinearLayout page) {
        LinearLayout overview = ui.card();
        showProgress(overview, "本周进度");
        ui.add(overview, ui.text(SHORT_DATE.format(week.start) + "—" + SHORT_DATE.format(week.start.plusDays(6)),
                12, MUTED, false), 9);
        LinearLayout days = ui.row();
        for (int i = 0; i < 7; i++) {
            LocalDate date = week.start.plusDays(i);
            ui.weighted(days, dayButton(date, week.on(date)), 1, i == 0 ? 0 : 3);
        }
        ui.add(overview, days, 7);
        ui.add(page, overview, 0);

        for (Journal.WorkoutDraft draft : drafts) {
            boolean listed = false;
            for (Journal.Session session : week.on(selectedDay)) {
                if (session.date.equals(draft.date) && session.plan.id == draft.plan.id) listed = true;
            }
            if (!listed) {
                LinearLayout pending = ui.card();
                ui.add(pending, ui.heading(draft.plan.name, 16), 0);
                ui.add(pending, ui.text(FULL_DATE.format(draft.date) + " · 训练草稿", 13, MUTED, false), 3);
                ui.add(pending, ui.secondary("继续训练", () -> openWorkout(draft.date, draft.plan.id)), 7);
                ui.add(page, pending, 8);
            }
        }

        String dayTitle = selectedDay.equals(today) ? "今日训练" : weekday(selectedDay) + "训练 · " + SHORT_DATE.format(selectedDay);
        ui.add(page, ui.heading(dayTitle, 18), 18);
        List<Journal.Session> sessions = week.on(selectedDay);
        if (sessions.isEmpty()) {
            LinearLayout empty = ui.card();
            ui.add(empty, ui.text("这天没有训练安排。", 14, MUTED, false), 0);
            ui.add(page, empty, 8);
        }
        for (Journal.Session session : sessions) ui.add(page, trainingCard(session), 8);

        ui.add(page, ui.heading("每周计划", 18), 18);
        LinearLayout files = ui.row();
        ui.weighted(files, ui.secondary("导入计划", planFiles::openImport), 1, 0);
        ui.weighted(files, ui.secondary("导出计划", planFiles::openExport), 1, 8);
        ui.add(page, files, 8);
        LinearLayout rules = ui.card();
        if (plans.isEmpty()) ui.add(rules, ui.text("新建计划后，会按指定星期每周重复。", 14, MUTED, false), 0);
        for (Journal.Plan plan : plans) {
            LinearLayout row = ui.row();
            LinearLayout info = ui.column();
            ui.add(info, ui.text(plan.name, 16, TEXT, true), 0);
            ui.add(info, ui.text(dayLabels(plan.days), 13, MUTED, false), 3);
            ui.weighted(row, info, 1, 0);
            TextView minutes = ui.text(plan.minutes + " 分钟", 13, GREEN, true);
            ui.padding(minutes, 8, 8, 8, 8);
            ui.editNumber(minutes, "编辑训练时长", () -> editors.editPlan(plan, Editors.PLAN_MINUTES));
            row.addView(minutes);
            Button edit = ui.secondary("编辑", () -> editors.editPlan(plan));
            edit.setContentDescription("编辑" + plan.name);
            row.addView(edit);
            if (plan != plans.get(0)) ui.divider(rules, 8);
            ui.add(rules, row, plan == plans.get(0) ? 0 : 8);
        }
        ui.add(page, rules, 8);
    }

    private View dayButton(LocalDate date, List<Journal.Session> sessions) {
        boolean active = date.equals(selectedDay);
        boolean allDone = !sessions.isEmpty();
        for (Journal.Session session : sessions) allDone &= session.done;
        LinearLayout day = ui.column();
        day.setGravity(Gravity.CENTER);
        day.setMinimumHeight(ui.dp(63));
        day.setBackground(ui.ripple(active ? FOREST : (date.equals(today) ? TINT : BACKGROUND), 0, 11));
        day.setSelected(active);
        day.setFocusable(true);
        day.setContentDescription(weekday(date) + " " + HEADER_DATE.format(date) + "，"
                + (sessions.isEmpty() ? "无安排" : allDone ? "训练已完成" : "有训练安排"));
        day.setOnClickListener(view -> {
            if (!date.equals(selectedDay)) {
                selectedDay = date;
                refresh();
            }
        });
        TextView name = ui.text(DAYS[date.getDayOfWeek().getValue() - 1], 12, active ? PALE : MUTED, false);
        TextView number = ui.text(Integer.toString(date.getDayOfMonth()), 16, active ? WHITE : TEXT, true);
        name.setGravity(Gravity.CENTER);
        number.setGravity(Gravity.CENTER);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        day.addView(name);
        day.addView(number);
        View dot = new View(this);
        int dotColor = sessions.isEmpty() ? 0 : allDone ? (active ? LIME : GREEN) : (active ? PALE : 0xff799b86);
        dot.setBackground(ui.shape(dotColor, 0, 4));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(ui.dp(5), ui.dp(5));
        dotParams.topMargin = ui.dp(3);
        day.addView(dot, dotParams);
        return day;
    }

    private void showProgress(LinearLayout card, String title) {
        LinearLayout row = ui.row();
        ui.weighted(row, ui.heading(title, 18), 1, 0);
        row.addView(ui.pill(week.completed() + " / " + week.sessions.size() + " 次", TINT, GREEN));
        ui.add(card, row, 0);
        ui.progress(card, week.completed(), week.sessions.size(), 10);
    }

    View trainingCard(Journal.Session session) {
        Journal.WorkoutDraft draft = null;
        if (!session.done) {
            for (Journal.WorkoutDraft candidate : drafts) {
                if (candidate.date.equals(session.date) && candidate.plan.id == session.plan.id) {
                    draft = candidate;
                    break;
                }
            }
        }
        Journal.Plan plan = draft == null ? session.plan : draft.plan;
        boolean planEditable = !session.done && draft == null;
        boolean future = session.date.isAfter(today);
        LinearLayout card = ui.card();
        LinearLayout title = ui.row();
        ui.weighted(title, ui.heading(plan.name, 16), 1, 0);
        if (session.done) title.addView(ui.pill("已打卡", TINT, GREEN));
        else title.addView(ui.pill(draft != null ? "训练草稿" : future ? "待训练" : "未打卡", BACKGROUND, MUTED));
        ui.add(card, title, 0);
        String length = session.durationSeconds == null ? plan.minutes + " 分钟计划" : Ui.duration(session.durationSeconds);
        LinearLayout summary = ui.row();
        TextView duration = ui.text(length, 13, planEditable ? GREEN : MUTED, false);
        if (planEditable) ui.editNumber(duration, "编辑训练时长", () -> editors.editPlan(plan, Editors.PLAN_MINUTES));
        summary.addView(duration);
        ui.weighted(summary, ui.text("· " + plan.exercises.size() + " 个动作", 13, MUTED, false), 1, 5);
        ui.add(card, summary, 2);
        ui.divider(card, 9);
        for (int i = 0; i < plan.exercises.size(); i++) {
            Journal.Exercise exercise = plan.exercises.get(i);
            LinearLayout row = ui.row();
            ui.weighted(row, ui.text(exercise.name, 15, TEXT, false), 1, 0);
            String sets = draft == null ? exercise.sets + " 组" : draft.completedSets(i) + " / " + exercise.sets + " 组";
            TextView count = ui.text(sets, 14, GREEN, true);
            if (planEditable) {
                int exerciseIndex = i;
                ui.editNumber(count, "编辑" + exercise.name + "的组数", () -> editors.editPlan(plan, exerciseIndex));
            }
            row.addView(count);
            ui.add(card, row, 6);
        }
        if (session.done || future) return card;
        if (draft != null) {
            ui.add(card, ui.primary("继续训练", () -> openWorkout(session.date, plan.id)), 10);
        } else if (session.date.equals(today)) {
            LinearLayout actions = ui.row();
            ui.weighted(actions, ui.primary("开始训练", () -> startWorkout(session)), 1.3f, 0);
            ui.weighted(actions, ui.secondary("快速打卡", () -> editors.quickCheckIn(session)), 1, 8);
            ui.add(card, actions, 10);
        } else {
            ui.add(card, ui.primary("补打卡", () -> editors.quickCheckIn(session)), 10);
        }
        return card;
    }

    private void startWorkout(Journal.Session session) {
        if (!session.date.equals(LocalDate.now()) || !write(() -> store.startWorkout(session))) return;
        openWorkout(session.date, session.plan.id);
    }

    private void openWorkout(LocalDate date, long planId) {
        Intent intent = new Intent(this, WorkoutActivity.class).putExtra(WorkoutActivity.DATE, date.toString())
                .putExtra(WorkoutActivity.PLAN, planId);
        startActivityForResult(intent, WORKOUT_REQUEST);
    }

    void trainingSaved(LocalDate date) {
        refresh();
        editors.showCompleted(date, week);
    }

    static String kg(double value) {
        return KG.format(value);
    }

    static String weekday(LocalDate date) {
        return "周" + DAYS[date.getDayOfWeek().getValue() - 1];
    }

    private static String dayLabels(int days) {
        StringBuilder result = new StringBuilder();
        for (DayOfWeek day : DayOfWeek.values()) {
            if ((days & Journal.dayBit(day)) == 0) continue;
            if (result.length() > 0) result.append('、');
            result.append('周').append(DAYS[day.getValue() - 1]);
        }
        return result.toString();
    }
}
