package com.ffps.fksk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Круглая пиктограмма жеста: обновить (две стрелки по кругу) или домой. */
public class GestureIcon extends View {
    static final int RELOAD = 0;
    static final int HOME = 1;

    private static final int BG_COLOR = 0xFF6A1B9A;

    private final int type;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    public GestureIcon(Context context, int type) {
        super(context);
        this.type = type;
        bg.setColor(BG_COLOR);
        bg.setStyle(Paint.Style.FILL);
        stroke.setColor(0xFFFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) / 2f;
        canvas.drawCircle(cx, cy, radius, bg);
        if (type == RELOAD) {
            drawReload(canvas, cx, cy, radius);
        } else {
            drawHome(canvas, cx, cy, radius);
        }
    }

    private void drawReload(Canvas canvas, float cx, float cy, float radius) {
        float r = radius * 0.46f;
        stroke.setStrokeWidth(radius * 0.13f);
        oval.set(cx - r, cy - r, cx + r, cy + r);
        arrowArc(canvas, cx, cy, r, radius, 200f, 140f);
        arrowArc(canvas, cx, cy, r, radius, 20f, 140f);
    }

    private void arrowArc(Canvas canvas, float cx, float cy, float r, float radius, float start, float sweep) {
        canvas.drawArc(oval, start, sweep, false, stroke);
        double a = Math.toRadians(start + sweep);
        float px = cx + r * (float) Math.cos(a);
        float py = cy + r * (float) Math.sin(a);
        float tx = -(float) Math.sin(a); // направление движения по часовой стрелке
        float ty = (float) Math.cos(a);
        float nx = (float) Math.cos(a);  // от центра наружу
        float ny = (float) Math.sin(a);
        float s = radius * 0.28f;
        path.reset();
        path.moveTo(px + tx * s, py + ty * s);
        path.lineTo(px + nx * s * 0.75f, py + ny * s * 0.75f);
        path.lineTo(px - nx * s * 0.75f, py - ny * s * 0.75f);
        path.close();
        canvas.drawPath(path, fill);
    }

    private void drawHome(Canvas canvas, float cx, float cy, float radius) {
        float s = radius * 0.52f;
        // крыша
        path.reset();
        path.moveTo(cx, cy - s);
        path.lineTo(cx + s * 1.15f, cy - s * 0.05f);
        path.lineTo(cx - s * 1.15f, cy - s * 0.05f);
        path.close();
        canvas.drawPath(path, fill);
        // стены
        canvas.drawRect(cx - s * 0.8f, cy - s * 0.05f, cx + s * 0.8f, cy + s * 0.9f, fill);
        // дверь
        canvas.drawRect(cx - s * 0.25f, cy + s * 0.35f, cx + s * 0.25f, cy + s * 0.9f, bg);
    }
}
