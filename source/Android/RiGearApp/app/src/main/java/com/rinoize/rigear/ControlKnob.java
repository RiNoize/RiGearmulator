package com.rinoize.rigear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Ranged controls: enum parameters are never sent values beyond their real range. */
public final class ControlKnob extends View {
    public interface Change { void changed(int value); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final String label;
    private final int min, max;
    private final Change change;
    private int value;
    private boolean known;
    private float lastY, remainder;
    private final float density, textScale;
    public ControlKnob(Context context, String label, int min, int max, Change change) {
        super(context);
        this.label = label; this.min = min; this.max = max; this.change = change;
        density = getResources().getDisplayMetrics().density;
        textScale = getResources().getDisplayMetrics().scaledDensity;
        value = min; setClickable(true); setFocusable(true);
        setContentDescription(label);
    }
    public void showValue(int next) {
        if (isPressed()) return;
        known = next >= min && next <= max;
        if (known) value = next;
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() * .5f, cy = getHeight() * .39f;
        float radius = Math.min(getWidth() * .31f, getHeight() * .29f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(isEnabled() ? Color.rgb(47, 51, 56) : Color.rgb(30, 32, 35));
        canvas.drawCircle(cx, cy, radius, paint);
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3 * density);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setColor(Color.DKGRAY);
        canvas.drawArc(arc, 135, 270, false, paint);
        float sweep = 270f * (value - min) / Math.max(1, max - min);
        paint.setColor(isEnabled() ? 0xffffb34d : Color.GRAY);
        if (known) canvas.drawArc(arc, 135, sweep, false, paint);
        double angle = Math.toRadians(135 + sweep);
        paint.setStrokeWidth(2 * density);
        canvas.drawLine(cx, cy, cx + (float)Math.cos(angle) * radius * .7f,
                cy + (float)Math.sin(angle) * radius * .7f, paint);
        paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(11 * textScale); paint.setColor(Color.LTGRAY);
        canvas.drawText(label, cx, getHeight() - 23 * density, paint);
        paint.setColor(0xffffb34d); paint.setTextSize(12 * textScale);
        canvas.drawText(known ? Integer.toString(value) : "—", cx, getHeight() - 7 * density, paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastY = event.getY(); remainder = 0; setPressed(true);
                getParent().requestDisallowInterceptTouchEvent(true); return true;
            case MotionEvent.ACTION_MOVE:
                remainder += (lastY - event.getY()) / density;
                lastY = event.getY();
                int steps = (int)(remainder / 2.5f);
                if (steps != 0) {
                    remainder -= steps * 2.5f;
                    int next = Math.max(min, Math.min(max, value + steps));
                    if (next != value || !known) {
                        value = next; known = true; change.changed(value); invalidate();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                setPressed(false); getParent().requestDisallowInterceptTouchEvent(false);
                if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
