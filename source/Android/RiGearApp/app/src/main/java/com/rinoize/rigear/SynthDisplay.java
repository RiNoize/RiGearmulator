package com.rinoize.rigear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

/** Blue simulated synthesizer LCD with a real, low-cost view of RiGear's output. */
public final class SynthDisplay extends TextView {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private final RectF outer = new RectF(), inner = new RectF();
    private final Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    private int lastScopeRevision = -1;

    public SynthDisplay(Context context) { this(context, null); }
    public SynthDisplay(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setMinimumHeight(dp(94));
        setMinWidth(dp(390));
        setPadding(dp(12), dp(8), dp(12), dp(8));
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        setContentDescription("Display de patch y osciloscopio de salida");
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    @Override protected void onDraw(Canvas canvas) {
        final float w = getWidth(), h = getHeight();
        if (w <= 1 || h <= 1) return;

        outer.set(1, 1, w - 1, h - 1);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xff05070a, 0xff1b2530, 0xff05070a}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(outer, dp(8), dp(8), paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0xff4c6071);
        canvas.drawRoundRect(outer, dp(8), dp(8), paint);

        inner.set(dp(12), dp(9), w - dp(12), h - dp(9));
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, inner.top, 0, inner.bottom,
                new int[]{0xff075ad8, 0xff003caa, 0xff021d68}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(inner, dp(4), dp(4), paint);
        paint.setShader(null);

        // Subtle LCD cell/grid texture.
        paint.setStrokeWidth(1f);
        paint.setColor(0x2200d7ff);
        float gridX = Math.max(dp(9), inner.width() / 32f);
        for (float x = inner.left + gridX; x < inner.right; x += gridX)
            canvas.drawLine(x, inner.top, x, inner.bottom, paint);
        float gridY = Math.max(dp(8), inner.height() / 8f);
        for (float y = inner.top + gridY; y < inner.bottom; y += gridY)
            canvas.drawLine(inner.left, y, inner.right, y, paint);

        String raw = getText() == null ? "" : getText().toString().trim();
        boolean dirty = raw.endsWith("*");
        if (dirty) raw = raw.substring(0, raw.length() - 1).trim();
        String mode = raw.startsWith("P") && raw.contains("·") ? "MULTI" : "SINGLE";
        if (raw.contains("·")) raw = raw.substring(raw.indexOf('·') + 1).trim();
        String slot = "---";
        String name = raw.isEmpty() ? "Cargar ROM" : raw;
        int split = raw.indexOf("  ");
        if (split > 0) {
            String candidate = raw.substring(0, split).trim();
            if (candidate.matches("[A-H][0-9]{3}")) {
                slot = candidate;
                name = raw.substring(split).trim();
            }
        } else if (raw.matches("[A-H][0-9]{3}.*")) {
            slot = raw.substring(0, 4);
            name = raw.substring(4).trim();
        }

        paint.setTypeface(mono);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xfff4fbff);
        paint.setShadowLayer(dp(4), 0, 0, 0xff76ddff);
        paint.setTextSize(sp(18));
        float topBaseline = inner.top + sp(21);
        canvas.drawText(slot, inner.left + dp(13), topBaseline, paint);
        float slotWidth = paint.measureText(slot + "  ");
        float availableName = inner.right - dp(105) - (inner.left + dp(13) + slotWidth);
        float nameSize = sp(18);
        paint.setTextSize(nameSize);
        while (paint.measureText(name) > availableName && nameSize > sp(11)) {
            nameSize -= 1f;
            paint.setTextSize(nameSize);
        }
        canvas.drawText(name, inner.left + dp(13) + slotWidth, topBaseline, paint);
        paint.setTextSize(sp(11));
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(mode + (dirty ? "  EDIT" : ""), inner.right - dp(12), topBaseline, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.clearShadowLayer();

        final float left = inner.left + dp(14), right = inner.right - dp(14);
        final float top = inner.top + sp(29), bottom = inner.bottom - dp(8);
        final float center = (top + bottom) * .5f;
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x5536c8ff);
        canvas.drawLine(left, center, right, center, paint);

        float[] samples = ScopeBus.snapshot();
        float peak = .02f;
        for (float sample : samples) peak = Math.max(peak, Math.abs(sample));
        float gain = Math.min(1f / peak, 5f);
        wave.reset();
        for (int i = 0; i < samples.length; ++i) {
            float x = left + (right - left) * i / Math.max(1, samples.length - 1);
            float y = center - samples[i] * gain * (bottom - top) * .43f;
            if (i == 0) wave.moveTo(x, y); else wave.lineTo(x, y);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3.2f));
        paint.setColor(0x6619d8ff);
        paint.setShadowLayer(dp(5), 0, 0, 0xff00bfff);
        canvas.drawPath(wave, paint);
        paint.setStrokeWidth(dp(1.4f));
        paint.setColor(0xffffffff);
        canvas.drawPath(wave, paint);
        paint.clearShadowLayer();

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(sp(8));
        paint.setColor(0xff79dcff);
        canvas.drawText(ScopeBus.active ? "WAVE OUT" : "WAVE HOLD", left, bottom, paint);

        int revision = ScopeBus.revision;
        long delay = ScopeBus.active ? 66L : (revision == lastScopeRevision ? 500L : 100L);
        lastScopeRevision = revision;
        if (isAttachedToWindow() && getVisibility() == View.VISIBLE) postInvalidateDelayed(delay);
    }
}
