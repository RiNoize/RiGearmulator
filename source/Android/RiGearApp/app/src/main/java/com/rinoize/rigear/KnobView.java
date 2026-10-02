package com.rinoize.rigear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class KnobView extends View {
    public interface OnValueChangedListener {
        void onValueChanged(KnobView knob, int value, boolean fromUser);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private String label = "";
    private int value = 0;
    private float lastY;
    private float dragAccumulator;

    private OnValueChangedListener listener;

    public KnobView(Context context) {
        super(context);
        init();
    }

    public KnobView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setFocusable(true);
        setClickable(true);
        setBackgroundColor(Color.TRANSPARENT);
    }

    public void setLabel(String label) {
        this.label = label == null ? "" : label;
        invalidate();
    }

    public String getLabel() {
        return label;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        setValue(value, false);
    }

    public void setValue(int value, boolean fromUser) {
        int clipped = Math.max(0, Math.min(127, value));
        if (this.value == clipped && !fromUser)
            return;

        this.value = clipped;
        invalidate();

        if (listener != null)
            listener.onValueChanged(this, clipped, fromUser);
    }

    public void setOnValueChangedListener(OnValueChangedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();

        float labelArea = Math.max(30f, h * 0.24f);
        float knobSize = Math.min(w * 0.70f, h - labelArea - 8f);
        float cx = w * 0.5f;
        float cy = Math.max(knobSize * 0.58f + 4f, h * 0.40f);
        float r = knobSize * 0.48f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(44, 47, 52));
        canvas.drawCircle(cx, cy, r, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(3f, r * 0.10f));
        paint.setStrokeCap(Paint.Cap.ROUND);

        arcRect.set(cx - r * 0.86f, cy - r * 0.86f,
                    cx + r * 0.86f, cy + r * 0.86f);

        paint.setColor(Color.rgb(83, 88, 95));
        canvas.drawArc(arcRect, 135f, 270f, false, paint);

        float sweep = 270f * value / 127f;
        paint.setColor(Color.rgb(226, 159, 55));
        canvas.drawArc(arcRect, 135f, sweep, false, paint);

        double angle = Math.toRadians(135.0 + sweep);
        float x2 = cx + (float)Math.cos(angle) * r * 0.65f;
        float y2 = cy + (float)Math.sin(angle) * r * 0.65f;

        paint.setStrokeWidth(Math.max(2f, r * 0.07f));
        paint.setColor(Color.WHITE);
        canvas.drawLine(cx, cy, x2, y2, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);

        paint.setColor(Color.WHITE);
        paint.setTextSize(Math.max(11f, h * 0.105f));
        canvas.drawText(label, cx, h - 17f, paint);

        paint.setColor(Color.rgb(226, 159, 55));
        paint.setTextSize(Math.max(11f, h * 0.105f));
        canvas.drawText(Integer.toString(value), cx, h - 3f, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled())
            return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastY = event.getY();
                dragAccumulator = 0f;
                getParent().requestDisallowInterceptTouchEvent(true);
                setPressed(true);
                return true;

            case MotionEvent.ACTION_MOVE:
                float y = event.getY();
                dragAccumulator += lastY - y;
                lastY = y;

                while (dragAccumulator >= 3.0f) {
                    setValue(value + 1, true);
                    dragAccumulator -= 3.0f;
                }

                while (dragAccumulator <= -3.0f) {
                    setValue(value - 1, true);
                    dragAccumulator += 3.0f;
                }

                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                setPressed(false);
                performClick();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
