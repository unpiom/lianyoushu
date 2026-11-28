package com.lianji.fitlog;

import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.UUID;

final class DesktopBall {
    static final String TODAY_ACTION = "com.lianji.fitlog.action.TODAY_WORKOUT";
    static final String TEMPORARY_ACTION = "com.lianji.fitlog.action.TEMPORARY_WORKOUT";
    private static final String TOKEN = "desktop_ball_launch_token";
    private static final int REQUEST_SETTINGS = 462;
    private static String pendingToken;

    private DesktopBall() { }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("desktop_ball", Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean("enabled", false);
    }

    private static boolean hasUsageAccess(Context context) {
        AppOpsManager apps = context.getSystemService(AppOpsManager.class);
        return apps != null && apps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    static boolean hasPermissions(Context context) {
        return Settings.canDrawOverlays(context) && hasUsageAccess(context);
    }

    static boolean setEnabled(Context context, boolean enabled) {
        Intent service = new Intent(context, DesktopBallService.class);
        if (!enabled) {
            preferences(context).edit().putBoolean("enabled", false).apply();
            context.stopService(service);
            return true;
        }
        if (!hasPermissions(context)) return false;
        preferences(context).edit().putBoolean("enabled", true).apply();
        try {
            context.startForegroundService(service);
            return true;
        } catch (IllegalStateException | SecurityException error) {
            Log.w("Lianji", "Unable to start desktop ball", error);
            preferences(context).edit().putBoolean("enabled", false).apply();
            return false;
        }
    }

    static void showSettings(MainActivity activity) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle("桌面悬浮球");
        if (!Settings.canDrawOverlays(activity)) {
            dialog.setMessage("请在系统设置中允许「练有数」显示悬浮窗。开启后可将训练入口浮在手机桌面上。")
                    .setPositiveButton("设置悬浮窗", (window, which) -> openPermission(activity,
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        } else if (!hasUsageAccess(activity)) {
            dialog.setMessage("请在系统设置中允许「练有数」访问使用情况。应用仅用这项权限识别手机桌面，不保存应用使用记录。")
                    .setPositiveButton("设置使用情况访问", (window, which) -> openPermission(activity,
                            Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } else if (isEnabled(activity) && DesktopBallService.isRunning()) {
            dialog.setMessage("返回手机桌面可拖动悬浮球，点一下可选择今日训练或临时训练。")
                    .setPositiveButton("完成", null)
                    .setNegativeButton("关闭悬浮球", (window, which) -> setEnabled(activity, false))
                    .show();
            return;
        } else {
            dialog.setMessage("开启后，返回手机桌面即可拖动悬浮球，点一下可选择今日训练或临时训练。")
                    .setPositiveButton("开启悬浮球", (window, which) -> {
                        if (!setEnabled(activity, true)) {
                            Toast.makeText(activity, "悬浮球启动失败，请检查系统权限后重试。", Toast.LENGTH_LONG).show();
                        }
                    });
        }
        dialog.setNegativeButton("取消", null).show();
    }

    private static void openPermission(MainActivity activity, String action) {
        Uri app = Uri.fromParts("package", activity.getPackageName(), null);
        if (openSettings(activity, new Intent(action, app)) || openSettings(activity, new Intent(action))) return;
        Toast.makeText(activity, "请在系统设置中为练有数开启此权限。", Toast.LENGTH_LONG).show();
    }

    private static boolean openSettings(MainActivity activity, Intent intent) {
        try {
            activity.startActivityForResult(intent, REQUEST_SETTINGS);
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.w("Lianji", "Unable to open desktop ball permission settings", error);
            return false;
        }
    }

    static boolean onSettingsResult(MainActivity activity, int requestCode) {
        if (requestCode != REQUEST_SETTINGS) return false;
        showSettings(activity);
        return true;
    }

    static void launchTraining(Context context, String action) {
        if (!TODAY_ACTION.equals(action) && !TEMPORARY_ACTION.equals(action)) return;
        pendingToken = UUID.randomUUID().toString();
        Intent intent = new Intent(context, MainActivity.class).setAction(action).putExtra(TOKEN, pendingToken)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            pendingToken = null;
            Log.w("Lianji", "Unable to open workout from the desktop ball", error);
            Toast.makeText(context, "未能打开训练，请从应用进入。", Toast.LENGTH_SHORT).show();
        }
    }

    static String consumeAction(Intent intent) {
        if (intent == null || pendingToken == null || !pendingToken.equals(intent.getStringExtra(TOKEN))) return null;
        pendingToken = null;
        return intent.getAction();
    }
}
