package com.lianji.fitlog;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.Layout;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import java.util.Locale;

final class Ui {
    static final int BACKGROUND = 0xfff4f6f1;
    static final int WHITE = 0xffffffff;
    static final int TEXT = 0xff172c24;
    static final int MUTED = 0xff53665b;
    static final int LINE = 0xffdfe6dd;
    static final int FOREST = 0xff153c30;
    static final int GREEN = 0xff20684e;
    static final int TINT = 0xffeaf3e9;
    static final int LIME = 0xffdef29f;
    static final int PALE = 0xffc6dbcf;
    static final int RED = 0xffad3931;
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    enum Icon { HOME, SCALE, TRAINING, CHECK, CALENDAR, TARGET }

    private final Context context;

    Ui(Context context) {
        this.context = context;
    }

    static void configureWindow(Window window) {
        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(light, light);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    static void fitInsets(View root, boolean includeKeyboard) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                int types = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
                if (includeKeyboard) types |= WindowInsets.Type.ime();
                Insets safe = insets.getInsets(types);
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                context.getResources().getDisplayMetrics());
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    LinearLayout constrainedColumn() {
        LinearLayout layout = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = Math.min(MeasureSpec.getSize(widthSpec), dp(608));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
            }
        };
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout card() {
        LinearLayout layout = column();
        layout.setBackground(shape(WHITE, LINE, 20));
        padding(layout, 16, 15, 16, 15);
        return layout;
    }

    TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        return view;
    }

    TextView heading(String value, float size) {
        TextView view = text(value, size, TEXT, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        return view;
    }

    void editNumber(TextView value, String label, Runnable edit) {
        boolean editable = edit != null;
        int flags = value.getPaintFlags();
        value.setPaintFlags(editable ? flags | Paint.UNDERLINE_TEXT_FLAG : flags & ~Paint.UNDERLINE_TEXT_FLAG);
        value.setOnClickListener(editable ? view -> edit.run() : null);
        value.setClickable(editable);
        value.setFocusable(editable);
        value.setOnTouchListener(null);
        value.setAccessibilityDelegate(null);
        if (!editable) return;

        // Keep the standard click action for keyboard and accessibility activation.
        value.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, label));
            }
        });
        GestureDetector detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }

            @Override public boolean onDoubleTapEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) value.performClick();
                return true;
            }
        });
        value.setOnTouchListener((view, event) -> {
            detector.onTouchEvent(event);
            return true;
        });
    }

    GradientDrawable shape(int color, int border, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (border != 0) background.setStroke(dp(1), border);
        return background;
    }

    RippleDrawable ripple(int color, int border, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x2420684e), shape(color, border, radius),
                shape(WHITE, 0, radius));
    }

    Button button(String label, int fill, int color, int border, Runnable click) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(color);
        button.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setGravity(Gravity.CENTER);
        button.setBackground(ripple(fill, border, 13));
        button.setStateListAnimator(null);
        padding(button, 12, 8, 12, 8);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    Button primary(String label, Runnable click) {
        return button(label, GREEN, WHITE, 0, click);
    }

    Button secondary(String label, Runnable click) {
        return button(label, TINT, GREEN, 0, click);
    }

    TextView pill(String value, int fill, int color) {
        TextView view = text(value, 12, color, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(shape(fill, 0, 12));
        padding(view, 9, 4, 9, 4);
        return view;
    }

    void padding(View view, int left, int top, int right, int bottom) {
        view.setPadding(dp(left), dp(top), dp(right), dp(bottom));
    }

    void add(LinearLayout parent, View view, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.topMargin = dp(topMargin);
        parent.addView(view, params);
    }

    void weighted(LinearLayout row, View view, float weight, int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, WRAP, weight);
        params.leftMargin = dp(leftMargin);
        row.addView(view, params);
    }

    void spacer(LinearLayout row) {
        weighted(row, new Space(context), 1, 0);
    }

    void divider(LinearLayout parent, int marginTop) {
        View rule = new View(context);
        rule.setBackgroundColor(LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, dp(1));
        params.topMargin = dp(marginTop);
        parent.addView(rule, params);
    }

    void progress(LinearLayout parent, int done, int total, int marginTop) {
        LinearLayout track = row();
        track.setBackground(shape(TINT, 0, 8));
        track.setClipToOutline(true);
        if (done > 0) {
            View fill = new View(context);
            fill.setBackground(shape(GREEN, 0, 8));
            track.addView(fill, new LinearLayout.LayoutParams(0, MATCH, done));
            if (done < total) track.addView(new Space(context), new LinearLayout.LayoutParams(0, MATCH, total - done));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, dp(8));
        params.topMargin = dp(marginTop);
        parent.addView(track, params);
    }

    static String clock(long seconds) {
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    static String duration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds / 60) % 60;
        if (hours > 0) return hours + " 小时 " + minutes + " 分钟";
        if (minutes > 0) return minutes + " 分钟" + (seconds % 60 == 0 ? "" : " " + seconds % 60 + " 秒");
        return seconds + " 秒";
    }

    View icon(Icon kind, int color, int size) {
        View view = new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();

            @Override protected void onDraw(Canvas canvas) {
                float side = dp(size);
                canvas.save();
                canvas.translate((getWidth() - side) / 2, (getHeight() - side) / 2);
                canvas.scale(side / 24, side / 24);
                paint.setColor(color);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.75f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                switch (kind) {
                    case HOME:
                        lines(canvas, 3, 10, 12, 3, 21, 10);
                        lines(canvas, 5, 9, 5, 20, 10, 20, 10, 15, 14, 15, 14, 20, 19, 20, 19, 9);
                        break;
                    case SCALE:
                        canvas.drawRoundRect(3, 3, 21, 21, 5, 5, paint);
                        canvas.drawArc(7, 7, 17, 16, 205, 130, false, paint);
                        canvas.drawLine(12, 13, 14, 9, paint);
                        break;
                    case TRAINING:
                        canvas.drawLine(2, 12, 4, 12, paint);
                        canvas.drawRoundRect(4, 7, 8, 17, 1, 1, paint);
                        canvas.drawLine(8, 12, 16, 12, paint);
                        canvas.drawRoundRect(16, 7, 20, 17, 1, 1, paint);
                        canvas.drawLine(20, 12, 22, 12, paint);
                        break;
                    case CHECK:
                        lines(canvas, 5, 12, 10, 17, 19, 7);
                        break;
                    case CALENDAR:
                        canvas.drawRoundRect(3, 5, 21, 21, 3, 3, paint);
                        canvas.drawLine(8, 3, 8, 7, paint);
                        canvas.drawLine(16, 3, 16, 7, paint);
                        canvas.drawLine(3, 10, 21, 10, paint);
                        break;
                    case TARGET:
                        canvas.drawCircle(12, 12, 9, paint);
                        canvas.drawCircle(12, 12, 5, paint);
                        canvas.drawCircle(12, 12, 1, paint);
                        break;
                }
                canvas.restore();
            }

            private void lines(Canvas canvas, float... points) {
                path.reset();
                path.moveTo(points[0], points[1]);
                for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
                canvas.drawPath(path, paint);
            }
        };
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return view;
    }
}
