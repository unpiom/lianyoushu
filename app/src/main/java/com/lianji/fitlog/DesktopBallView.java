package com.lianji.fitlog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

final class DesktopBallView extends View {
    private static final float ART_SIZE = 100f;
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shell = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevron = new Path();
    private final Drawable mascot;
    private int sphereLeft;
    private float hiddenFraction;
    private boolean leftEdge;

    DesktopBallView(Context context) {
        super(context);
        mascot = context.getDrawable(R.drawable.ic_launcher_foreground).mutate();
        mascot.setBounds(-16, -16, 116, 116);
        shadow.setShader(new RadialGradient(53f, 58f, 46f,
                new int[]{0x8202130c, 0x4002130c, 0x0002130c}, new float[]{0.54f, 0.82f, 1f}, Shader.TileMode.CLAMP));
        shell.setShader(new RadialGradient(31f, 21f, 77f,
                new int[]{0xff71c394, 0xff367f57, 0xff1e573b, 0xff09291f},
                new float[]{0f, 0.34f, 0.66f, 1f}, Shader.TileMode.CLAMP));
        highlight.setShader(new RadialGradient(32f, 24f, 16f,
                new int[]{0xaafffff4, 0x32e3ffd9, 0x00e3ffd9}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        rim.setColor(0x397cf3ba);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.1f);
        arrow.setColor(0xffe9f6d0);
        arrow.setStyle(Paint.Style.STROKE);
        arrow.setStrokeCap(Paint.Cap.ROUND);
        arrow.setStrokeJoin(Paint.Join.ROUND);
        arrow.setStrokeWidth(3.6f);
    }

    void placeSphere(int left, float fraction, boolean atLeftEdge) {
        sphereLeft = left;
        hiddenFraction = fraction;
        leftEdge = atLeftEdge;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = getHeight() / ART_SIZE;
        int save = canvas.save();
        canvas.translate(sphereLeft, 0f);
        canvas.scale(scale, scale);
        canvas.drawCircle(53f, 58f, 46f, shadow);
        canvas.drawCircle(50f, 47f, 44f, shell);
        canvas.drawCircle(50f, 47f, 43.4f, rim);
        int highlightSave = canvas.save();
        canvas.rotate(-28f, 32f, 24f);
        canvas.drawOval(15f, 16f, 49f, 32f, highlight);
        canvas.restoreToCount(highlightSave);
        mascot.setAlpha(Math.round(255f * (1f - hiddenFraction)));
        mascot.draw(canvas);

        float arrowOpacity = Math.max(0f, (hiddenFraction - 0.55f) / 0.45f);
        if (arrowOpacity > 0f) {
            float visibleLeft = Math.max(0, -sphereLeft) / scale;
            float visibleRight = Math.min(getHeight(), getWidth() - sphereLeft) / scale;
            float center = (visibleLeft + visibleRight) / 2f;
            float direction = leftEdge ? 1f : -1f;
            chevron.reset();
            chevron.moveTo(center - direction * 3f, 40f);
            chevron.lineTo(center + direction * 4f, 47f);
            chevron.lineTo(center - direction * 3f, 54f);
            arrow.setAlpha(Math.round(255f * arrowOpacity));
            canvas.drawPath(chevron, arrow);
        }
        canvas.restoreToCount(save);
    }
}
