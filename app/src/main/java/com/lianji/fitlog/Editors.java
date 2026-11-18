package com.lianji.fitlog;

import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static com.lianji.fitlog.Ui.*;

final class Editors {
    private static final int DECIMAL = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
    private static final int INTEGER = InputType.TYPE_CLASS_NUMBER;
    private final MainActivity activity;
    private final Ui ui;
    private final Store store;

    Editors(MainActivity activity, Ui ui, Store store) {
        this.activity = activity;
        this.ui = ui;
        this.store = store;
    }

    void pickWeightDate() {
        pickDate(LocalDate.now().minusDays(1), this::editWeight);
    }

    void pickDate(LocalDate latest, Consumer<LocalDate> selected) {
        DatePickerDialog picker = new DatePickerDialog(activity,
                (view, year, month, day) -> selected.accept(LocalDate.of(year, month + 1, day)),
                latest.getYear(), latest.getMonthValue() - 1, latest.getDayOfMonth());
        picker.getDatePicker().setFirstDayOfWeek(Calendar.MONDAY);
        picker.getDatePicker().setMaxDate(latest.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        picker.setButton(DialogInterface.BUTTON_POSITIVE, "确定", picker);
        picker.setButton(DialogInterface.BUTTON_NEGATIVE, "取消", picker);
        picker.show();
    }

    void editWeight(LocalDate date) {
        boolean today = date.equals(LocalDate.now());
        Journal.Weight current = store.weight(date);
        if (current != null && (current.locked || today)) {
            Panel locked = new Panel("体重已确认");
            ui.add(locked.form, ui.text(MainActivity.FULL_DATE.format(date), 14, MUTED, false), 0);
            ui.add(locked.form, ui.text(MainActivity.kg(current.value) + " kg", 25, GREEN, true), 5);
            ui.add(locked.actions, ui.primary("完成", locked.dialog::dismiss), 0);
            locked.show();
            return;
        }
        Panel panel = new Panel(current == null ? "记录体重" : "编辑体重");
        LinearLayout dateRow = ui.row();
        dateRow.addView(ui.icon(Icon.CALENDAR, GREEN, 18));
        ui.weighted(dateRow, ui.text(MainActivity.FULL_DATE.format(date) + " · " + MainActivity.weekday(date), 14, MUTED, false), 1, 8);
        ui.add(panel.form, dateRow, 0);
        EditText weight = input(panel, "例如 72.5", current == null ? "" : MainActivity.kg(current.value), DECIMAL, 6);
        field(panel.form, "体重（kg）", weight, 14);
        if (current != null) {
            ui.add(panel.form, ui.button("删除这条记录", WHITE, RED, LINE, () -> confirm("删除体重记录",
                    MainActivity.FULL_DATE.format(date) + "的体重记录将被删除。", "删除", RED,
                    () -> save(panel, () -> store.deleteWeight(date), "体重记录已删除"))), 11);
        }
        panel.actions(today ? "下一步" : "保存", GREEN, () -> {
            Double kg = decimal(weight, panel, "kg");
            if (kg == null) return;
            if (date.isAfter(LocalDate.now())) {
                panel.error("请选择今天或之前的日期。");
                return;
            }
            if (today || date.equals(LocalDate.now())) {
                confirm("确认今日体重", MainActivity.FULL_DATE.format(date) + " · " + MainActivity.kg(kg)
                        + " kg\n确认后将锁定这条体重记录。", "确认记录", GREEN,
                        () -> save(panel, () -> store.saveWeight(date, kg, true), "今日体重已确认"));
            } else {
                save(panel, () -> store.saveWeight(date, kg, false), "体重已记录");
            }
        });
        panel.show();
    }

    void editGoal() {
        Double current = store.goal();
        Panel panel = new Panel(current == null ? "设置目标体重" : "编辑目标体重");
        EditText weight = input(panel, "例如 68.0", current == null ? "" : MainActivity.kg(current), DECIMAL, 6);
        field(panel.form, "目标体重（kg）", weight, 0);
        if (current != null) {
            ui.add(panel.form, ui.button("移除目标", WHITE, RED, LINE, () -> confirm("移除目标体重",
                    "移除后会停止显示达标预估。", "移除", RED,
                    () -> save(panel, store::deleteGoal, "目标已移除"))), 11);
        }
        panel.actions("保存", GREEN, () -> {
            Double kg = decimal(weight, panel, "kg");
            if (kg != null) save(panel, () -> store.saveGoal(kg), "目标已保存");
        });
        panel.show();
    }

    void editWaist(LocalDate date) {
        Double current = store.waist(date);
        Panel panel = new Panel(current == null ? "记录腰围" : "编辑腰围");
        ui.add(panel.form, ui.text(MainActivity.FULL_DATE.format(date), 14, MUTED, false), 0);
        EditText value = input(panel, "例如 80.5", current == null ? "" : MainActivity.kg(current), DECIMAL, 6);
        field(panel.form, "腰围（cm）", value, 10);
        if (current != null) ui.add(panel.form, ui.button("删除腰围记录", WHITE, RED, LINE, () -> confirm("删除腰围记录",
                MainActivity.FULL_DATE.format(date) + "的腰围记录将被删除。", "删除", RED,
                () -> save(panel, () -> store.deleteWaist(date), "腰围记录已删除"))), 11);
        panel.actions("保存", GREEN, () -> {
            Double cm = decimal(value, panel, "cm");
            if (cm != null && !date.isAfter(LocalDate.now())) save(panel, () -> store.saveWaist(date, cm), "腰围已记录");
        });
        panel.show();
    }

    void editSteps(LocalDate date) {
        Long count = store.manualSteps(date);
        Panel panel = new Panel("手动记步");
        ui.add(panel.form, ui.text(MainActivity.FULL_DATE.format(date), 14, MUTED, false), 0);
        EditText steps = input(panel, "例如 6500", count == null ? "" : count.toString(), INTEGER, 6);
        field(panel.form, "当天步数", steps, 10);
        if (count != null) ui.add(panel.form, ui.button("移除手动记录", WHITE, RED, LINE, () -> confirm("移除手动步数",
                "移除后会显示安卓健康中已有的当天步数。", "移除", RED,
                () -> save(panel, () -> store.deleteSteps(date), "手动步数已移除"))), 11);
        panel.actions("保存", GREEN, () -> {
            int value = integer(steps, 999999);
            if (value < 0) panel.error("请输入 0 至 999999 之间的整数。");
            else if (date.isAfter(LocalDate.now())) panel.error("请选择今天或之前的日期。");
            else save(panel, () -> store.saveSteps(date, value), "步数已记录");
        });
        panel.show();
    }

    void editStepGoal() {
        Long current = store.stepGoal();
        Panel panel = new Panel("每日步数目标");
        EditText target = input(panel, "例如 7000", current == null ? "" : current.toString(), INTEGER, 6);
        field(panel.form, "目标步数", target, 0);
        if (current != null) ui.add(panel.form, ui.button("清除步数目标", WHITE, RED, LINE,
                () -> save(panel, store::deleteStepGoal, "步数目标已清除")), 11);
        panel.actions("保存", GREEN, () -> {
            int value = integer(target, 999999);
            if (value < 1) panel.error("请输入 1 至 999999 之间的整数。");
            else save(panel, () -> store.saveStepGoal(value), "步数目标已保存");
        });
        panel.show();
    }

    void quickCheckIn(Journal.Session session) {
        Panel panel = new Panel(session.date.equals(LocalDate.now()) ? "训练打卡" : "补打卡");
        ui.add(panel.form, ui.text(session.plan.name + " · " + MainActivity.FULL_DATE.format(session.date), 14, MUTED, false), 0);
        EditText minutes = input(panel, "可选填", "", INTEGER, 4);
        field(panel.form, "实际训练时长（分钟）", minutes, 11);
        panel.actions("确认打卡", GREEN, () -> {
            boolean hasMinutes = !minutes.getText().toString().trim().isEmpty();
            int duration = integer(minutes, 1440);
            if (hasMinutes && duration < 1) {
                panel.error("请输入 1 至 1440 分钟。");
            } else if (session.date.isAfter(LocalDate.now())) {
                panel.error("训练日期尚未到来。");
            } else if (activity.write(() -> store.checkIn(session, hasMinutes ? duration * 60L : null))) {
                panel.dialog.dismiss();
                activity.trainingSaved(session.date);
            }
        });
        panel.show();
    }

    void editPlan(Journal.Plan plan) {
        Panel panel = new Panel(plan == null ? "新建训练计划" : "编辑训练计划");
        EditText name = input(panel, "例如 全身力量", plan == null ? "" : plan.name, InputType.TYPE_CLASS_TEXT, 40);
        field(panel.form, "计划名称", name, 0);
        ui.add(panel.form, ui.text("训练日（每周重复）", 14, TEXT, true), 12);
        LinearLayout days = ui.row();
        List<Button> dayButtons = new ArrayList<>();
        int initialDays = plan == null ? Journal.dayBit(LocalDate.now().getDayOfWeek()) : plan.days;
        for (DayOfWeek day : DayOfWeek.values()) {
            int index = day.getValue() - 1;
            Button button = ui.button("周" + MainActivity.DAYS[index], BACKGROUND, MUTED, 0, () -> {});
            button.setTextSize(12);
            ui.padding(button, 0, 6, 0, 6);
            setDay(button, (initialDays & Journal.dayBit(day)) != 0);
            button.setOnClickListener(view -> {
                setDay(button, !button.isSelected());
                panel.error("");
            });
            ui.weighted(days, button, 1, index == 0 ? 0 : 3);
            dayButtons.add(button);
        }
        ui.add(panel.form, days, 5);
        EditText minutes = input(panel, "例如 35", plan == null ? "" : Integer.toString(plan.minutes), INTEGER, 4);
        field(panel.form, "训练时长（分钟）", minutes, 12);

        LinearLayout exerciseHeading = ui.row();
        ui.weighted(exerciseHeading, ui.text("动作与组数", 14, TEXT, true), 1, 0);
        LinearLayout exerciseList = ui.column();
        List<ExerciseInput> fields = new ArrayList<>();
        exerciseHeading.addView(ui.secondary("＋ 添加动作", () -> {
            ExerciseInput added = addExercise(panel, exerciseList, fields, null);
            panel.error("");
            added.name.requestFocus();
        }));
        ui.add(panel.form, exerciseHeading, 8);
        ui.add(panel.form, exerciseList, 0);
        if (plan == null) addExercise(panel, exerciseList, fields, null);
        else for (Journal.Exercise exercise : plan.exercises) addExercise(panel, exerciseList, fields, exercise);

        if (plan != null) {
            ui.add(panel.form, ui.button("删除计划", WHITE, RED, LINE, () -> confirm("删除训练计划",
                    "每周计划和进行中的训练草稿将被删除。已完成的训练可在复盘查看。", "删除", RED,
                    () -> save(panel, () -> store.deletePlan(plan.id), "计划已删除"))), 11);
        }
        panel.actions("保存计划", GREEN, () -> {
            panel.error("");
            String title = name.getText().toString().trim();
            if (title.isEmpty()) {
                panel.error("请输入计划名称。");
                name.requestFocus();
                return;
            }
            int mask = 0;
            for (int i = 0; i < dayButtons.size(); i++) {
                if (dayButtons.get(i).isSelected()) mask |= Journal.dayBit(DayOfWeek.of(i + 1));
            }
            if (mask == 0) {
                panel.error("请选择至少一个训练日。");
                return;
            }
            int duration = integer(minutes, 1440);
            if (duration < 1) {
                panel.error("请输入 1–1440 分钟的训练时长。");
                minutes.requestFocus();
                return;
            }
            if (fields.isEmpty()) {
                panel.error("请添加至少一个训练动作。");
                return;
            }
            List<Journal.Exercise> exercises = new ArrayList<>();
            for (ExerciseInput entry : fields) {
                String exercise = entry.name.getText().toString().trim();
                if (exercise.isEmpty()) {
                    panel.error("请填写动作名称。");
                    entry.name.requestFocus();
                    return;
                }
                int sets = integer(entry.sets, 999);
                if (sets < 1) {
                    panel.error("请输入 1–999 组。");
                    entry.sets.requestFocus();
                    return;
                }
                exercises.add(new Journal.Exercise(exercise, sets));
            }
            Journal.Plan updated = new Journal.Plan(plan == null ? 0 : plan.id, title, mask, duration, exercises);
            save(panel, () -> store.savePlan(updated), "训练计划已保存");
        });
        panel.show();
    }

    private ExerciseInput addExercise(Panel panel, LinearLayout list, List<ExerciseInput> fields, Journal.Exercise exercise) {
        LinearLayout row = ui.row();
        EditText name = input(panel, "动作名称", exercise == null ? "" : exercise.name, InputType.TYPE_CLASS_TEXT, 40);
        EditText sets = input(panel, "组数", exercise == null ? "" : Integer.toString(exercise.sets), INTEGER, 3);
        ExerciseInput fieldsForRow = new ExerciseInput(name, sets);
        fields.add(fieldsForRow);
        ui.weighted(row, name, 1, 0);
        LinearLayout.LayoutParams setsSize = new LinearLayout.LayoutParams(ui.dp(62), WRAP);
        setsSize.leftMargin = ui.dp(7);
        row.addView(sets, setsSize);
        Button remove = ui.button("×", BACKGROUND, MUTED, 0, () -> {
            list.removeView(row);
            fields.remove(fieldsForRow);
            panel.error("");
        });
        remove.setTextSize(19);
        remove.setContentDescription("移除这个动作");
        LinearLayout.LayoutParams removeSize = new LinearLayout.LayoutParams(ui.dp(48), WRAP);
        removeSize.leftMargin = ui.dp(6);
        row.addView(remove, removeSize);
        ui.add(list, row, 7);
        return fieldsForRow;
    }

    private static final class ExerciseInput {
        final EditText name;
        final EditText sets;

        ExerciseInput(EditText name, EditText sets) {
            this.name = name;
            this.sets = sets;
        }
    }

    private void setDay(Button button, boolean active) {
        button.setSelected(active);
        button.setBackground(ui.ripple(active ? FOREST : BACKGROUND, 0, 9));
        button.setTextColor(active ? WHITE : MUTED);
    }

    void undo(Journal.Session session) {
        confirm("撤销打卡", "将撤销「" + session.plan.name + "」在 " + MainActivity.FULL_DATE.format(session.date)
                + "的打卡。" + (session.sets.isEmpty() ? "" : "本次逐组记录也会删除。"), "撤销", GREEN,
                () -> save(null, () -> store.undo(session), "已撤销打卡"));
    }

    void showCompleted(int count, int total) {
        Panel panel = new Panel("打卡完成");
        LinearLayout summary = ui.row();
        FrameLayout badge = new FrameLayout(activity);
        badge.setBackground(ui.shape(LIME, 0, 15));
        badge.addView(ui.icon(Icon.CHECK, FOREST, 26), new FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER));
        summary.addView(badge, new LinearLayout.LayoutParams(ui.dp(50), ui.dp(50)));
        String encouragement = total > 0 && count == total ? "本周训练全部完成！" : "又完成了一次训练！";
        ui.weighted(summary, ui.heading(encouragement, 17), 1, 13);
        ui.add(panel.form, summary, 4);
        ui.add(panel.form, ui.text("本周完成 " + count + " / " + total + " 次", 15, GREEN, true), 12);
        ui.progress(panel.form, count, total, 7);
        ui.add(panel.actions, ui.primary("完成", panel.dialog::dismiss), 0);
        panel.show();
    }

    private void confirm(String title, String detail, String action, int color, BooleanSupplier change) {
        Panel dialog = new Panel(title);
        ui.add(dialog.form, ui.text(detail, 15, MUTED, false), 0);
        dialog.actions(action, color, () -> {
            if (change.getAsBoolean()) dialog.dialog.dismiss();
        });
        dialog.show();
    }

    private boolean save(Panel panel, Runnable write, String status) {
        if (!activity.write(write)) return false;
        if (panel != null) panel.dialog.dismiss();
        activity.refresh();
        Toast.makeText(activity, status, Toast.LENGTH_SHORT).show();
        return true;
    }

    private Double decimal(EditText value, Panel panel, String unit) {
        String text = value.getText().toString().trim().replace(',', '.');
        if (text.matches("[0-9]{1,3}(\\.[0-9]{0,2})?")) {
            double kg = Double.parseDouble(text);
            if (kg > 0) return kg;
        }
        panel.error("请输入 0.01–999.99 " + unit + "，最多保留两位小数。");
        value.requestFocus();
        return null;
    }

    static int integer(EditText field, int max) {
        try {
            int number = Integer.parseInt(field.getText().toString().trim());
            return number <= max ? number : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private EditText input(Panel panel, String hint, String value, int inputType, int maxLength) {
        EditText edit = new EditText(activity);
        edit.setId(View.generateViewId());
        edit.setTextColor(TEXT);
        edit.setHintTextColor(MUTED);
        edit.setTextSize(16);
        edit.setSingleLine();
        edit.setInputType(inputType);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        edit.setHint(hint);
        edit.setText(value);
        edit.setSelectAllOnFocus(true);
        edit.setMinHeight(ui.dp(48));
        edit.setBackground(ui.shape(BACKGROUND, LINE, 11));
        edit.setOnFocusChangeListener((view, focused) -> edit.setBackground(ui.shape(BACKGROUND, focused ? GREEN : LINE, 11)));
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) {
                if (panel.error.getVisibility() == View.VISIBLE) panel.error("");
            }
        });
        ui.padding(edit, 11, 8, 11, 8);
        return edit;
    }

    private void field(LinearLayout form, String heading, EditText field, int margin) {
        TextView label = ui.text(heading, 14, TEXT, true);
        label.setLabelFor(field.getId());
        ui.add(form, label, margin);
        ui.add(form, field, 5);
    }

    private final class Panel {
        final Dialog dialog = new Dialog(activity);
        final LinearLayout root = ui.column();
        final LinearLayout form = ui.column();
        final TextView error = ui.text("", 13, RED, false);
        final LinearLayout actions = ui.column();

        Panel(String title) {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            root.setBackground(ui.shape(WHITE, 0, 23));
            root.setFocusableInTouchMode(true);
            ui.padding(root, 18, 17, 18, 16);
            ui.add(root, ui.heading(title, 22), 0);
            ScrollView scroll = new ScrollView(activity);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(form, new ScrollView.LayoutParams(MATCH, WRAP));
            LinearLayout.LayoutParams scrollSpace = new LinearLayout.LayoutParams(MATCH, 0, 1);
            scrollSpace.topMargin = ui.dp(12);
            root.addView(scroll, scrollSpace);
            error.setVisibility(View.GONE);
            error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            ui.add(root, error, 6);
            ui.add(root, actions, 13);
            dialog.setContentView(root);
            dialog.setCanceledOnTouchOutside(true);
        }

        void error(String message) {
            error.setText(message);
            error.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        }

        void actions(String label, int color, Runnable onSave) {
            LinearLayout bar = ui.row();
            ui.weighted(bar, ui.secondary("取消", dialog::dismiss), 1, 0);
            ui.weighted(bar, ui.button(label, color, WHITE, 0, onSave), 1.35f, 8);
            ui.add(actions, bar, 0);
        }

        void show() {
            Window window = dialog.getWindow();
            if (window == null) return;
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.42f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            root.requestFocus();
            dialog.show();
            int width = activity.getResources().getDisplayMetrics().widthPixels - ui.dp(40);
            window.setLayout(Math.min(width, ui.dp(508)), WRAP);
        }
    }
}
