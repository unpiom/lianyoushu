package com.lianji.fitlog;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import static com.lianji.fitlog.Ui.*;

public final class HealthPrivacyActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui ui = new Ui(this);
        Ui.configureWindow(getWindow());
        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(BACKGROUND);
        Ui.fitInsets(root, false);
        FrameLayout frame = new FrameLayout(this);
        LinearLayout page = ui.constrainedColumn();
        ui.padding(page, 20, 18, 20, 22);
        LinearLayout card = ui.card();
        String name = getApplicationInfo().loadLabel(getPackageManager()).toString();
        ui.add(card, ui.heading(name + " · 步数权限", 23), 0);
        ui.add(card, ui.text("授权后，" + name + "读取手机 Health Connect 中最近 7 天的步数，用于显示每日步数、目标进度和 7 天日均。", 15, TEXT, false), 12);
        ui.add(card, ui.text("系统步数由 Health Connect 在手机上管理。手动步数、体重和训练记录保存在应用的本机存储中，卸载应用会清除这些记录。", 15, TEXT, false), 9);
        ui.add(card, ui.text(name + "可离线使用。你可以在系统的 Health Connect 设置中撤回步数权限。", 15, TEXT, false), 9);
        ui.add(card, ui.primary("返回", this::finish), 15);
        ui.add(page, card, 0);
        frame.addView(page, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL));
        root.addView(frame, new ScrollView.LayoutParams(MATCH, WRAP));
        setContentView(root);
        root.requestApplyInsets();
    }
}
