package com.lianji.fitlog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.lianji.fitlog.Ui.*;

public final class WorkoutActivity extends Activity {
    static final String DATE = "workout_date";
    static final String PLAN = "workout_plan";
    static final String TEMPORARY = "workout_temporary";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClocks();
            handler.postDelayed(this, 250);
        }
    };
    private Ui ui;
    private Store store;
    private Journal.WorkoutDraft draft;
    private int exerciseIndex;
    private LinearLayout exerciseCard;
    private TextView elapsed;
    private TextView groupClock;
    private Button skip;
    private ScrollView scroll;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        String day = getIntent().getStringExtra(DATE);
        if (day != null) draft = store.draft(LocalDate.parse(day), getIntent().getLongExtra(PLAN, 0));
        if (draft == null) {
            finish();
            return;
        }
        exerciseIndex = defaultExercise(draft);
        if (!draft.setRunning() && state != null) {
            int savedExercise = state.getInt("exercise", exerciseIndex);
            if (savedExercise >= 0 && savedExercise < draft.plan.exercises.size()) exerciseIndex = savedExercise;
        }
        ui = new Ui(this);
        Ui.configureWindow(getWindow());
        render(0);
    }

    @Override protected void onResume() {
        super.onResume();
        DesktopBallService.hideForApp();
        if (draft == null) return;
        Journal.WorkoutDraft latest = store.draft(draft.date, draft.plan.id);
        if (latest == null) {
            finish();
            return;
        }
        boolean timerChanged = latest.setStartedAt != draft.setStartedAt || latest.activeExerciseIndex != draft.activeExerciseIndex
                || latest.restUntil != draft.restUntil || latest.restSeconds != draft.restSeconds
                || latest.sets.size() != draft.sets.size();
        draft = latest;
        if (timerChanged) {
            exerciseIndex = defaultExercise(draft);
            render(scroll.getScrollY());
        }
        handler.post(tick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("exercise", exerciseIndex);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        store.close();
        super.onDestroy();
    }

    private static int firstIncomplete(Journal.WorkoutDraft draft) {
        for (int i = 0; i < draft.plan.exercises.size(); i++) {
            if (draft.hasSetRemaining(i)) return i;
        }
        return -1;
    }

    private static int defaultExercise(Journal.WorkoutDraft draft) {
        if (draft.setRunning() || draft.hasSetRemaining(draft.activeExerciseIndex)) return draft.activeExerciseIndex;
        int next = firstIncomplete(draft);
        return next < 0 ? draft.activeExerciseIndex : next;
    }

    private void render(int position) {
        LinearLayout root = ui.column();
        root.setBackgroundColor(BACKGROUND);
        root.setFocusableInTouchMode(true);
        Ui.fitInsets(root, true);

        LinearLayout header = ui.constrainedColumn();
        ui.padding(header, 20, 9, 20, 9);
        LinearLayout title = ui.row();
        ui.weighted(title, ui.heading("训练中", 25), 1, 0);
        title.addView(ui.secondary("返回", this::finish));
        ui.add(header, title, 0);
        center(root, header);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        FrameLayout frame = new FrameLayout(this);
        LinearLayout page = ui.constrainedColumn();
        ui.padding(page, 20, 5, 20, 18);
        frame.addView(page, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addView(frame, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));

        ui.add(page, progressCard(), 0);
        ui.add(page, groupClockCard(), 12);
        exerciseCard = ui.card();
        ui.add(page, exerciseCard, 12);
        showExercise();
        if (!draft.sets.isEmpty()) ui.add(page, ui.secondary("撤销上一组", this::undoSet), 9);
        ui.add(page, ui.button("放弃本次训练", BACKGROUND, RED, 0, this::confirmDiscard), 4);

        ui.divider(root, 0);
        LinearLayout footer = ui.constrainedColumn();
        ui.padding(footer, 20, 8, 20, 8);
        ui.add(footer, ui.primary(draft.plan.temporary ? "结束并保存" : "结束并打卡", this::confirmFinish), 0);
        center(root, footer).setBackgroundColor(WHITE);
        setContentView(root);
        root.requestApplyInsets();
        root.requestFocus();
        ScrollView current = scroll;
        if (position > 0) current.post(() -> current.scrollTo(0, position));
        updateClocks();
    }

    private FrameLayout center(LinearLayout host, View child) {
        FrameLayout frame = new FrameLayout(this);
        frame.addView(child, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL));
        ui.add(host, frame, 0);
        return frame;
    }

    private View progressCard() {
        LinearLayout card = ui.column();
        card.setBackground(ui.shape(FOREST, 0, 21));
        ui.padding(card, 16, 15, 16, 15);
        ui.add(card, ui.text(draft.plan.name, 22, WHITE, true), 0);
        String type = draft.plan.temporary ? "临时训练" : draft.plan.minutes + " 分钟计划";
        ui.add(card, ui.text(MainActivity.FULL_DATE.format(draft.date) + " · " + type, 13, PALE, false), 4);
        LinearLayout timing = ui.row();
        String sets = Integer.toString(draft.sets.size());
        if (!draft.plan.temporary) sets += " / " + draft.plannedSets();
        ui.weighted(timing, ui.text("已完成 " + sets + " 组", 15, LIME, true), 1, 0);
        elapsed = ui.text("", 19, WHITE, true);
        elapsed.setContentDescription("训练用时");
        timing.addView(elapsed);
        ui.add(card, timing, 12);
        return card;
    }

    private View groupClockCard() {
        LinearLayout card = ui.card();
        LinearLayout row = ui.row();
        LinearLayout label = ui.column();
        ui.add(label, ui.heading(draft.setRunning() ? "本组用时" : "组间休息", 17), 0);
        if (!draft.setRunning()) {
            TextView duration = ui.text("每组 " + draft.restSeconds + " 秒", 14, GREEN, true);
            ui.editNumber(duration, "编辑组间休息时长", this::chooseRest);
            ui.add(label, duration, 3);
        }
        ui.weighted(row, label, 1, 0);
        groupClock = ui.text("", 26, GREEN, true);
        row.addView(groupClock);
        ui.add(card, row, 0);
        if (!draft.setRunning() && draft.restUntil > 0) {
            skip = ui.secondary("跳过休息", () -> save(draft.withRest(draft.restSeconds, 0)));
            ui.add(card, skip, 8);
        } else {
            skip = null;
        }
        return card;
    }

    private void updateClocks() {
        long now = System.currentTimeMillis();
        setText(elapsed, Ui.clock(draft.elapsedSeconds(now)));
        if (draft.setRunning()) {
            setText(groupClock, Ui.clock(draft.setElapsedSeconds(now)));
            return;
        }
        long seconds = Math.max(0, (draft.restUntil - now + 999) / 1000);
        setText(groupClock, seconds > 0 ? Ui.clock(seconds) : draft.restUntil == 0 ? "待开始" : "休息结束");
        boolean active = seconds > 0;
        if (skip != null && skip.isEnabled() != active) {
            skip.setEnabled(active);
            skip.setAlpha(active ? 1f : 0.44f);
        }
    }

    private void setText(TextView label, String value) {
        if (!value.contentEquals(label.getText())) label.setText(value);
    }

    private void chooseRest() {
        int[] values = {15, 30, 45, 60, 90, 120, 180, 300};
        String[] labels = new String[values.length];
        int current = -1;
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] + " 秒";
            if (draft.restSeconds == values[i]) current = i;
        }
        new AlertDialog.Builder(this).setTitle("组间休息时长").setSingleChoiceItems(labels, current, (dialog, index) -> {
            long now = System.currentTimeMillis();
            long until = draft.restUntil > now ? now + values[index] * 1000L : draft.restUntil;
            if (save(draft.withRest(values[index], until))) dialog.dismiss();
        }).setNegativeButton("取消", null).show();
    }

    private void showExercise() {
        exerciseCard.removeAllViews();
        if (draft.plan.exercises.isEmpty()) {
            ui.add(exerciseCard, ui.text("这项训练没有安排动作。", 14, MUTED, false), 0);
            return;
        }
        if (draft.plan.exercises.size() > 1) addMovementSelector();
        Journal.Exercise exercise = draft.plan.exercises.get(exerciseIndex);
        int completed = draft.completedSets(exerciseIndex);
        LinearLayout heading = ui.row();
        ui.weighted(heading, ui.heading(exercise.name, 19), 1, 0);
        String groups = draft.plan.temporary ? completed + " 组" : completed + " / " + exercise.sets + " 组";
        heading.addView(ui.pill(groups, TINT, GREEN));
        ui.add(exerciseCard, heading, draft.plan.exercises.size() > 1 ? 9 : 0);

        List<Journal.SetLog> previous = store.previousSets(exercise.name, draft.date);
        if (!previous.isEmpty()) {
            int next = Math.min(completed, previous.size() - 1);
            ui.add(exerciseCard, ui.text("上次第 " + (next + 1) + " 组  " + setSummary(previous.get(next)), 14, MUTED, false), 7);
        }
        if (!draft.hasSetRemaining(exerciseIndex)) {
            ui.add(exerciseCard, ui.text("该动作已完成。", 14, GREEN, true), 9);
            int pending = firstIncomplete(draft);
            if (pending >= 0) {
                ui.add(exerciseCard, ui.primary("下一动作", () -> {
                    exerciseIndex = pending;
                    showExercise();
                }), 10);
            }
            return;
        }
        ui.add(exerciseCard, ui.text("第 " + (completed + 1) + " 组", 14, TEXT, true), 9);
        if (draft.setRunning()) {
            addSetInput();
        } else {
            String startLabel = draft.sets.isEmpty() ? "开始第一组" : "开始下一组";
            ui.add(exerciseCard, ui.primary(startLabel, () -> save(draft.startSet(exerciseIndex, System.currentTimeMillis()))), 10);
        }
    }

    private void addMovementSelector() {
        Spinner movement = new Spinner(this, Spinner.MODE_DROPDOWN);
        movement.setContentDescription("选择训练动作");
        movement.setMinimumHeight(ui.dp(46));
        List<String> names = new ArrayList<>();
        for (int i = 0; i < draft.plan.exercises.size(); i++) {
            Journal.Exercise exercise = draft.plan.exercises.get(i);
            names.add(exercise.name + " · " + draft.completedSets(i) + "/" + exercise.sets + " 组");
        }
        ArrayAdapter<String> values = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        values.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        movement.setAdapter(values);
        movement.setSelection(exerciseIndex);
        movement.setEnabled(!draft.setRunning());
        movement.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> view, View item, int position, long id) {
                if (!draft.setRunning() && movement.getParent() == exerciseCard && exerciseIndex != position) {
                    exerciseIndex = position;
                    showExercise();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> view) { }
        });
        ui.add(exerciseCard, movement, 0);
    }

    private void addSetInput() {
        int numeric = InputType.TYPE_CLASS_NUMBER;
        LinearLayout inputs = ui.row();
        EditText reps = numberField("次数", numeric, 4);
        EditText load = numberField("重量（kg）", numeric | InputType.TYPE_NUMBER_FLAG_DECIMAL, 8);
        ui.weighted(inputs, reps, 1, 0);
        ui.weighted(inputs, load, 1, 8);
        ui.add(exerciseCard, ui.text("实际次数和重量可选填", 13, MUTED, false), 9);
        ui.add(exerciseCard, inputs, 5);
        TextView error = ui.text("", 13, RED, false);
        error.setVisibility(View.GONE);
        ui.add(exerciseCard, error, 5);
        ui.add(exerciseCard, ui.primary("完成本组", () -> {
            long completedAt = System.currentTimeMillis();
            String repsText = reps.getText().toString().trim();
            String loadText = load.getText().toString().trim().replace(',', '.');
            Integer performed = repsText.isEmpty() ? null : Editors.integer(reps, 9999);
            if ((performed != null && performed < 1)
                    || (!loadText.isEmpty() && !loadText.matches("[0-9]{1,4}(\\.[0-9]{0,2})?"))) {
                error.setText("请填写正整数次数和有效重量，或留空。");
                error.setVisibility(View.VISIBLE);
                return;
            }
            Double kg = loadText.isEmpty() ? null : Double.valueOf(loadText);
            hideKeyboard();
            Journal.WorkoutDraft completed = draft.completeSet(performed, kg, completedAt);
            save(completed, defaultExercise(completed));
        }), 10);
    }

    private EditText numberField(String hint, int type, int length) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setContentDescription(hint);
        field.setTextSize(15);
        field.setSingleLine();
        field.setInputType(type);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(length)});
        field.setBackground(ui.shape(BACKGROUND, LINE, 11));
        field.setMinimumHeight(ui.dp(48));
        ui.padding(field, 10, 8, 10, 8);
        return field;
    }

    private boolean save(Journal.WorkoutDraft update) {
        return save(update, exerciseIndex);
    }

    private boolean save(Journal.WorkoutDraft update, int selection) {
        if (update == draft) return true;
        if (!write(() -> store.saveDraft(update))) return false;
        draft = update;
        exerciseIndex = selection;
        render(scroll.getScrollY());
        return true;
    }

    private void undoSet() {
        Journal.WorkoutDraft undone = draft.undoSet();
        save(undone, defaultExercise(undone));
    }

    private void confirmDiscard() {
        new AlertDialog.Builder(this).setTitle("放弃本次训练？").setMessage("本次计时和已录入的组数将被清除。")
                .setNegativeButton("取消", null).setPositiveButton("放弃", (dialog, which) -> {
                    if (write(() -> store.discardDraft(draft))) finish();
                }).show();
    }

    private void confirmFinish() {
        long measured = draft.elapsedSeconds(System.currentTimeMillis());
        hideKeyboard();
        LinearLayout body = ui.column();
        body.setFocusableInTouchMode(true);
        ui.padding(body, 22, 11, 22, 4);
        ui.add(body, ui.text("完成 " + draft.sets.size() + " 组 · 计时 " + Ui.duration(measured), 15, TEXT, true), 0);
        if (draft.setRunning()) {
            ui.add(body, ui.text("当前组仍在计时。可以返回完成本组，或保存已完成的组。", 14, MUTED, false), 7);
        }
        ui.add(body, ui.text("可填入实际训练分钟数来调整计时。", 14, MUTED, false), 7);
        EditText adjusted = numberField("实际时长（分钟，可选）", InputType.TYPE_CLASS_NUMBER, 4);
        ui.add(body, adjusted, 9);
        ui.add(body, ui.text(getString(R.string.check_in_lock_hint), 14, MUTED, false), 9);
        String title = draft.plan.temporary ? "确认保存临时训练" : "确认训练打卡";
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(body)
                .setNegativeButton(R.string.check_in_undo, null).setPositiveButton(R.string.check_in_confirm, null).create();
        dialog.show();
        body.requestFocus();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String text = adjusted.getText().toString().trim();
            long seconds = measured;
            if (!text.isEmpty()) {
                int minutes = Editors.integer(adjusted, 1440);
                if (minutes < 1) {
                    adjusted.setError("请输入 1 至 1440 分钟");
                    return;
                }
                seconds = minutes * 60L;
            }
            long duration = seconds;
            if (!write(() -> store.finishWorkout(draft, duration))) return;
            dialog.dismiss();
            setResult(RESULT_OK, new Intent().putExtra(DATE, draft.date.toString()).putExtra(TEMPORARY, draft.plan.temporary));
            finish();
        });
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (focused != null && keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }

    private boolean write(Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException error) {
            Log.e("Lianji", "Unable to save workout", error);
            Toast.makeText(this, "训练记录保存失败，请稍后重试。", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    static String setSummary(Journal.SetLog set) {
        StringBuilder summary = new StringBuilder();
        if (set.reps != null) summary.append(set.reps).append(" 次 · ");
        if (set.loadKg != null) summary.append(MainActivity.kg(set.loadKg)).append(" kg · ");
        return summary.append(set.durationSeconds == null ? "未计时" : "用时 " + Ui.duration(set.durationSeconds)).toString();
    }
}
