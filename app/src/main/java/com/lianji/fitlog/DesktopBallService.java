package com.lianji.fitlog;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.Objects;

public final class DesktopBallService extends Service {
    private static final String STOP_ACTION = "com.lianji.fitlog.action.STOP_DESKTOP_BALL";
    private static final String CHANNEL = "desktop_ball";
    private static final int NOTIFICATION_ID = 51;
    private static final int WINDOW_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    private static final int WINDOW_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    private static final long INTERVAL_MS = 300;
    private static final long RESOLVE_INTERVAL_MS = 3_000;
    private static final long HISTORY_MS = 120_000;
    private static final long EVENT_OVERLAP_MS = 5_000;
    private static final long COLLAPSE_DELAY_MS = 10_000;
    private static final long SLIDE_DURATION_MS = 220;
    private static final int BALL_SIZE_DP = 56;
    private static final int EDGE_REVEAL_DP = 20;
    private static final int EDGE_TOUCH_DP = 32;
    private static final String BALL_DESCRIPTION = "桌面悬浮球，点按打开训练菜单，拖动调整位置";
    private static final String EDGE_DESCRIPTION = "展开桌面悬浮球";
    private static final String READY = "在手机桌面可使用，点此打开练有数。";
    private static final String UNKNOWN_HOME = "暂时无法识别当前桌面，悬浮球已隐藏。";
    private static DesktopBallService active;
    private static long hideThrough;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::updateVisibility;
    private final Runnable collapseAfterIdle = this::collapseBall;
    private final BroadcastReceiver screenState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) removeWindow();
            schedulePoll(0);
        }
    };

    private UsageStatsManager usage;
    private NotificationManager notifications;
    private KeyguardManager keyguard;
    private PowerManager power;
    private WindowManager windows;
    private Context windowContext;
    private SharedPreferences position;
    private Ui ui;
    private String notificationStatus;
    private ActivityInfo launcher;
    private long launcherCheckedAt;
    private long queriedThrough;
    private long latestTransition;
    private long homeResumedAt;
    private long retryWindowAfter;

    private FrameLayout overlay;
    private WindowManager.LayoutParams overlayParams;
    private DesktopBallView ball;
    private LinearLayout menu;
    private ValueAnimator ballSlide;
    private float dockProgress;
    private int availableWidth;
    private int availableHeight;
    private int diameter;
    private int ballX;
    private int ballY;
    private int menuWidth;
    private int menuHeight;
    private float initialTouchX;
    private float initialTouchY;
    private int initialBallX;
    private int initialBallY;
    private boolean dragging;

    @Override public void onCreate() {
        super.onCreate();
        usage = getSystemService(UsageStatsManager.class);
        notifications = getSystemService(NotificationManager.class);
        keyguard = getSystemService(KeyguardManager.class);
        power = getSystemService(PowerManager.class);
        position = getSharedPreferences("desktop_ball_position", MODE_PRIVATE);
        DisplayManager displays = getSystemService(DisplayManager.class);
        Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        windowContext = this;
        if (Build.VERSION.SDK_INT >= 30 && display != null) {
            windowContext = createDisplayContext(display).createWindowContext(WINDOW_TYPE, null);
        }
        ui = new Ui(windowContext);
        windows = windowContext.getSystemService(WindowManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "桌面悬浮球", NotificationManager.IMPORTANCE_LOW));

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenState, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenState, filter);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (active == null) {
            Notification notification = notification(READY);
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            notificationStatus = READY;
            active = this;
        }
        if (intent != null && STOP_ACTION.equals(intent.getAction())) {
            disable();
            return START_NOT_STICKY;
        }
        schedulePoll(0);
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        removeWindow();
        schedulePoll(0);
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        handler.removeCallbacks(poll);
        unregisterReceiver(screenState);
        removeWindow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    static void hideForApp() {
        hideThrough = System.currentTimeMillis();
        DesktopBallService service = active;
        if (service != null) service.removeWindow();
    }

    static boolean isRunning() {
        return active != null;
    }

    private Notification notification(String status) {
        Intent showApp = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent open = PendingIntent.getActivity(this, 0, showApp, PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, DesktopBallService.class).setAction(STOP_ACTION);
        PendingIntent turnOff = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("桌面悬浮球已开启")
                .setContentText(status)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "关闭", turnOff).build())
                .build();
    }

    private void showStatus(String status) {
        if (status.equals(notificationStatus)) return;
        notifications.notify(NOTIFICATION_ID, notification(status));
        notificationStatus = status;
    }

    private void disable() {
        removeWindow();
        handler.removeCallbacks(poll);
        DesktopBall.setEnabled(this, false);
        stopSelf();
    }

    private void schedulePoll(long delay) {
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, delay);
    }

    private boolean screenAvailable() {
        return power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked();
    }

    private void updateVisibility() {
        if (!DesktopBall.isEnabled(this) || !DesktopBall.hasPermissions(this)) {
            disable();
            return;
        }
        if (!screenAvailable()) {
            removeWindow();
            schedulePoll(1_000);
            return;
        }
        if (readLauncherState() && homeResumedAt > hideThrough) showWindow();
        else removeWindow();
        schedulePoll(INTERVAL_MS);
    }

    private void checkDefaultLauncher() {
        long elapsed = SystemClock.elapsedRealtime();
        if (launcherCheckedAt != 0 && elapsed - launcherCheckedAt < RESOLVE_INTERVAL_MS) return;
        launcherCheckedAt = elapsed;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        ActivityInfo next = resolved == null ? null : resolved.activityInfo;
        if (next != null && ("android".equals(next.packageName) || next.packageName == null || next.name == null)) next = null;
        if (launcher == null || next == null || !Objects.equals(launcher.packageName, next.packageName)
                || !Objects.equals(launcher.name, next.name) || !Objects.equals(launcher.targetActivity, next.targetActivity)) {
            queriedThrough = 0;
            latestTransition = 0;
            homeResumedAt = 0;
        }
        launcher = next;
    }

    private boolean readLauncherState() {
        checkDefaultLauncher();
        if (launcher == null || usage == null || windows == null) {
            showStatus(UNKNOWN_HOME);
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < queriedThrough) {
            latestTransition = 0;
            queriedThrough = 0;
            homeResumedAt = 0;
            hideThrough = now;
        }
        long begin = queriedThrough == 0 ? now - HISTORY_MS : queriedThrough - EVENT_OVERLAP_MS;
        UsageEvents events;
        try {
            events = usage.queryEvents(begin, now + 1);
        } catch (SecurityException error) {
            events = null;
        }
        if (events == null) {
            homeResumedAt = 0;
            showStatus(UNKNOWN_HOME);
            return false;
        }
        queriedThrough = now;
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.getNextEvent(event)) {
            long time = event.getTimeStamp();
            if (time < latestTransition) continue;
            int type = event.getEventType();
            if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                // App usage is used only as evidence of whether the home Activity is foreground.
                homeResumedAt = fromHome(event) ? time : 0;
                latestTransition = time;
            } else if ((type == UsageEvents.Event.ACTIVITY_PAUSED || type == UsageEvents.Event.ACTIVITY_STOPPED)
                    && fromHome(event)) {
                homeResumedAt = 0;
                latestTransition = time;
            }
        }
        if (latestTransition == 0) showStatus(UNKNOWN_HOME);
        else if (homeResumedAt == 0 || overlay != null) showStatus(READY);
        return true;
    }

    private boolean fromHome(UsageEvents.Event event) {
        if (!launcher.packageName.equals(event.getPackageName())) return false;
        String activity = event.getClassName();
        if (activity == null) return false;
        return activity.equals(launcher.name) || activity.equals(launcher.targetActivity);
    }

    private void showWindow() {
        if (overlay != null || SystemClock.elapsedRealtime() < retryWindowAfter) return;
        updateAvailableArea();
        diameter = ui.dp(BALL_SIZE_DP);
        if (availableWidth < diameter || availableHeight < diameter) return;
        ballX = position.contains("x") ? Math.round(maxBallX() * position.getFloat("x", 1)) : maxBallX() - ui.dp(15);
        ballY = Math.round(maxBallY() * position.getFloat("y", 0.58f));
        ballX = clamp(ballX, maxBallX());
        ballY = clamp(ballY, maxBallY());

        overlay = new FrameLayout(windowContext) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                int action = event.getActionMasked();
                boolean handled = super.dispatchTouchEvent(event);
                if (overlay == this) {
                    if (action == MotionEvent.ACTION_DOWN && handled) handler.removeCallbacks(collapseAfterIdle);
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                            || action == MotionEvent.ACTION_OUTSIDE) restartIdleCountdown();
                }
                return handled;
            }
        };
        overlay.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_OUTSIDE || menu == null) return false;
            closeMenu();
            return true;
        });
        ball = new DesktopBallView(windowContext);
        ball.setContentDescription(BALL_DESCRIPTION);
        ball.setOnClickListener(view -> toggleMenu());
        ball.setOnTouchListener(this::moveBall);
        overlay.addView(ball, frame(diameter, diameter, 0, 0));
        overlayParams = new WindowManager.LayoutParams(diameter, diameter, WINDOW_TYPE, WINDOW_FLAGS, PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 30) {
            overlayParams.setFitInsetsTypes(safeInsets());
            overlayParams.setFitInsetsIgnoringVisibility(true);
        }
        overlayParams.gravity = Gravity.TOP | Gravity.LEFT;
        overlayParams.x = ballX;
        overlayParams.y = ballY;
        try {
            windows.addView(overlay, overlayParams);
            showStatus(READY);
            restartIdleCountdown();
        } catch (WindowManager.BadTokenException | SecurityException error) {
            Log.w("Lianji", "Unable to show the desktop ball", error);
            overlay = null;
            overlayParams = null;
            ball = null;
            retryWindowAfter = SystemClock.elapsedRealtime() + RESOLVE_INTERVAL_MS;
            showStatus("系统暂时无法显示桌面悬浮球。");
        }
    }

    private static int safeInsets() {
        return WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
    }

    private void updateAvailableArea() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = windows.getMaximumWindowMetrics();
            Rect bounds = metrics.getBounds();
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(safeInsets());
            availableWidth = bounds.width() - insets.left - insets.right;
            availableHeight = bounds.height() - insets.top - insets.bottom;
        } else {
            Point size = new Point();
            windows.getDefaultDisplay().getSize(size);
            availableWidth = size.x;
            availableHeight = size.y;
        }
    }

    private void removeWindow() {
        handler.removeCallbacks(collapseAfterIdle);
        cancelBallSlide();
        if (overlay == null) return;
        FrameLayout window = overlay;
        overlay = null;
        try {
            windows.removeViewImmediate(window);
        } catch (IllegalArgumentException alreadyRemoved) {
            // WindowManager may remove application overlays when the permission is revoked.
        }
        overlayParams = null;
        ball = null;
        menu = null;
        dockProgress = 0f;
        dragging = false;
    }

    private void restartIdleCountdown() {
        handler.removeCallbacks(collapseAfterIdle);
        if (overlay != null && ballSlide == null && dockProgress == 0f) {
            handler.postDelayed(collapseAfterIdle, COLLAPSE_DELAY_MS);
        }
    }

    private void collapseBall() {
        closeMenu();
        if (overlay != null) slideBall(true);
    }

    private void cancelBallSlide() {
        ValueAnimator running = ballSlide;
        ballSlide = null;
        if (running != null) running.cancel();
    }

    private void slideBall(boolean toEdge) {
        handler.removeCallbacks(collapseAfterIdle);
        cancelBallSlide();
        ValueAnimator animation = ValueAnimator.ofFloat(dockProgress, toEdge ? 1f : 0f);
        ballSlide = animation;
        animation.setDuration(SLIDE_DURATION_MS);
        animation.addUpdateListener(frame -> {
            dockProgress = (float) frame.getAnimatedValue();
            adjustWindow();
        });
        animation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator ended) {
                if (ballSlide != ended || overlay == null) return;
                ballSlide = null;
                ball.setContentDescription(toEdge ? EDGE_DESCRIPTION : BALL_DESCRIPTION);
                if (!toEdge) showMenu();
            }
        });
        animation.start();
    }

    private boolean moveBall(View view, MotionEvent event) {
        if (overlay == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelBallSlide();
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                initialBallX = clamp(presentedBallX(), maxBallX());
                initialBallY = overlayParams.y + ball.getTop();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float deltaX = event.getRawX() - initialTouchX;
                float deltaY = event.getRawY() - initialTouchY;
                if (!dragging && Math.hypot(deltaX, deltaY) >= ViewConfiguration.get(view.getContext()).getScaledTouchSlop()) {
                    closeMenu();
                    if (overlay == null) return true;
                    dragging = true;
                }
                if (dragging) moveDraggedBall(deltaX, deltaY);
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    moveDraggedBall(event.getRawX() - initialTouchX, event.getRawY() - initialTouchY);
                    rememberPosition();
                } else {
                    view.performClick();
                }
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) rememberPosition();
                else if (dockProgress > 0f && dockProgress < 1f) slideBall(true);
                dragging = false;
                return true;
            default:
                return false;
        }
    }

    private void moveDraggedBall(float deltaX, float deltaY) {
        ballX = clamp(initialBallX + Math.round(deltaX), maxBallX());
        ballY = clamp(initialBallY + Math.round(deltaY), maxBallY());
        boolean atSide = ballX == 0 || ballX == maxBallX();
        if (atSide) {
            if (ballSlide == null && dockProgress < 1f) slideBall(true);
        } else if (ballSlide != null || dockProgress > 0f) {
            cancelBallSlide();
            dockProgress = 0f;
            ball.setContentDescription(BALL_DESCRIPTION);
        }
        adjustWindow();
    }

    private int maxBallX() { return availableWidth - diameter; }

    private int maxBallY() { return availableHeight - diameter; }

    private boolean nearerLeftEdge() {
        return ballX < maxBallX() - ballX;
    }

    private int presentedBallX() {
        int hiddenWidth = diameter - ui.dp(EDGE_REVEAL_DP);
        int edgeX = nearerLeftEdge() ? -hiddenWidth : maxBallX() + hiddenWidth;
        return Math.round(ballX + (edgeX - ballX) * dockProgress);
    }

    private void rememberPosition() {
        position.edit().putFloat("x", (float) ballX / Math.max(1, maxBallX()))
                .putFloat("y", (float) ballY / Math.max(1, maxBallY())).apply();
    }

    private void toggleMenu() {
        if (dockProgress > 0f || ballSlide != null) {
            slideBall(false);
        } else if (menu != null) {
            closeMenu();
            restartIdleCountdown();
        } else {
            showMenu();
        }
    }

    private void showMenu() {
        menu = new LinearLayout(overlay.getContext());
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));
        menu.setBackground(ui.shape(Ui.WHITE, Ui.LINE, 16));
        ui.add(menu, ui.primary("今日训练", () -> openWorkout(DesktopBall.TODAY_ACTION)), 0);
        ui.add(menu, ui.secondary("临时训练", () -> openWorkout(DesktopBall.TEMPORARY_ACTION)), 4);
        ui.add(menu, ui.button("关闭悬浮球", Ui.BACKGROUND, Ui.MUTED, 0, this::disable), 4);
        menuWidth = Math.min(availableWidth, ui.dp(156));
        menu.measure(View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.max(0, availableHeight - diameter - ui.dp(5)), View.MeasureSpec.AT_MOST));
        menuHeight = menu.getMeasuredHeight();
        overlay.addView(menu);
        adjustWindow();
        restartIdleCountdown();
    }

    private void closeMenu() {
        if (menu == null) return;
        overlay.removeView(menu);
        menu = null;
        adjustWindow();
    }

    private void openWorkout(String action) {
        if (DesktopBall.hasPermissions(this) && screenAvailable() && readLauncherState() && homeResumedAt > hideThrough) {
            closeMenu();
            restartIdleCountdown();
            if (overlay != null) DesktopBall.launchTraining(this, action);
        } else {
            removeWindow();
        }
    }

    private void adjustWindow() {
        if (overlay == null) return;
        int sphereLeft = presentedBallX();
        int visibleLeft = Math.max(0, sphereLeft);
        int visibleRight = Math.min(availableWidth, sphereLeft + diameter);
        int edgeTouch = ui.dp(EDGE_TOUCH_DP);
        // Clip the drawing inside a small on-screen window so the hidden part cannot cover the desktop.
        int touchLeft = Math.max(0, Math.min(visibleLeft, visibleRight - edgeTouch));
        int touchRight = Math.min(availableWidth, Math.max(visibleRight, touchLeft + edgeTouch));
        Rect ballBounds = new Rect(touchLeft, ballY, touchRight, ballY + diameter);
        Rect windowBounds = new Rect(ballBounds);
        if (menu != null) {
            Rect menuBounds = placeMenu(ballBounds);
            windowBounds.set(ballBounds);
            windowBounds.union(menuBounds);
            overlay.updateViewLayout(menu, frame(menuWidth, menuHeight,
                    menuBounds.left - windowBounds.left, menuBounds.top - windowBounds.top));
        }
        overlay.updateViewLayout(ball, frame(ballBounds.width(), diameter,
                ballBounds.left - windowBounds.left, ballBounds.top - windowBounds.top));
        int hiddenWidth = diameter - (visibleRight - visibleLeft);
        float hiddenFraction = hiddenWidth / (float) (diameter - ui.dp(EDGE_REVEAL_DP));
        ball.placeSphere(sphereLeft - ballBounds.left, hiddenFraction, nearerLeftEdge());
        overlayParams.x = windowBounds.left;
        overlayParams.y = windowBounds.top;
        overlayParams.width = windowBounds.width();
        overlayParams.height = windowBounds.height();
        overlayParams.flags = menu == null ? WINDOW_FLAGS : WINDOW_FLAGS | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        try {
            windows.updateViewLayout(overlay, overlayParams);
        } catch (IllegalArgumentException | SecurityException error) {
            Log.w("Lianji", "Unable to move the desktop ball", error);
            removeWindow();
            retryWindowAfter = SystemClock.elapsedRealtime() + RESOLVE_INTERVAL_MS;
            showStatus("系统暂时无法显示桌面悬浮球。");
        }
    }

    private Rect placeMenu(Rect ballBounds) {
        int gap = ui.dp(5);
        int leftX = ballBounds.left - gap - menuWidth;
        int rightX = ballBounds.right + gap;
        boolean fitsOnLeft = leftX >= 0;
        boolean fitsOnRight = rightX + menuWidth <= availableWidth;
        int x;
        int y;
        if (fitsOnLeft || fitsOnRight) {
            x = fitsOnRight && (nearerLeftEdge() || !fitsOnLeft) ? rightX : leftX;
            y = clamp(ballY + (diameter - menuHeight) / 2, availableHeight - menuHeight);
        } else {
            int height = diameter + gap + menuHeight;
            boolean above = ballY + height > availableHeight && ballY > maxBallY() / 2;
            int top = clamp(above ? ballY - gap - menuHeight : ballY, availableHeight - height);
            x = clamp(ballX + (diameter - menuWidth) / 2, availableWidth - menuWidth);
            y = above ? top : top + diameter + gap;
            ballBounds.offsetTo(ballBounds.left, above ? top + menuHeight + gap : top);
        }
        return new Rect(x, y, x + menuWidth, y + menuHeight);
    }

    private static FrameLayout.LayoutParams frame(int width, int height, int left, int top) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.LEFT);
        params.leftMargin = left;
        params.topMargin = top;
        return params;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }
}
