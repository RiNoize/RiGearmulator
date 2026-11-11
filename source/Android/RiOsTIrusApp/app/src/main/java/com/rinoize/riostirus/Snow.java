package com.rinoize.riostirus;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** "Snow White" skin: white panels like the Virus TI Snow, graphite knobs, Virus red accents
 * and a dark LCD for contrast on stage. Native views only: no bitmaps, fonts or animation loops. */
public final class Snow {
    public static final int BG = 0xffe8eaee, PANEL = 0xffffffff, PANEL_LOW = 0xfff3f4f6, EDGE = 0xffd3d7de;
    public static final int TEXT = 0xff1c2027, MUTED = 0xff6b717c, FAINT = 0xffa3a8b1;
    public static final int RED = 0xffd8232a, RED_DARK = 0xffa8141a, RED_SOFT = 0xfffde7e7;
    public static final int GRAPHITE = 0xff2b2f36, LCD_TEXT = 0xfff4f5f7, LCD_DIM = 0xff8d939d;
    public static final Typeface BOLD = Typeface.create("sans-serif-medium", Typeface.BOLD);
    public static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    public static final Typeface CONDENSED = Typeface.create("sans-serif-condensed", Typeface.BOLD);

    private Snow() {}

    public static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l;
    }
    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l;
    }
    public static TextView text(Context c, String s, float sp, int color) {
        TextView v = new TextView(c); v.setText(s); v.setTextSize(sp); v.setTextColor(color);
        v.setIncludeFontPadding(false); v.setFontFeatureSettings("tnum"); return v;
    }
    public static TextView label(Context c, String s) {
        TextView v = text(c, s.toUpperCase(java.util.Locale.ROOT), 10, MUTED); v.setTypeface(BOLD); v.setLetterSpacing(0.12f); return v;
    }
    public static GradientDrawable surface(Context c, int fill, int edge, float radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(c, radius));
        if (edge != 0) d.setStroke(dp(c, 1), edge);
        return d;
    }
    /** White card with a hairline edge and a red section title. */
    public static LinearLayout card(Context c, String title) {
        LinearLayout l = column(c); l.setPadding(dp(c, 8), dp(c, 6), dp(c, 8), dp(c, 4));
        l.setBackground(surface(c, PANEL, EDGE, 8));
        if (title != null) {
            LinearLayout head = row(c);
            View mark = new View(c); mark.setBackground(surface(c, RED, 0, 2));
            head.addView(mark, new LinearLayout.LayoutParams(dp(c, 3), dp(c, 11)));
            TextView t = label(c, title); t.setTextColor(TEXT); t.setPadding(dp(c, 6), 0, 0, 0); head.addView(t);
            l.addView(head);
        }
        return l;
    }
    public static void style(Button b, boolean primary, boolean selected) {
        Context c = b.getContext();
        int text = primary ? 0xffffffff : selected ? RED : TEXT;
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{FAINT, text}));
        int fill = primary ? RED : selected ? RED_SOFT : PANEL;
        int edge = primary ? RED_DARK : selected ? RED : EDGE;
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33d8232a), surface(c, fill, edge, 6), null));
        b.setStateListAnimator(null); b.setElevation(0);
    }
    public static Button button(Context c, String s, boolean primary, View.OnClickListener listener) {
        Button b = new Button(c); b.setText(s); b.setTextSize(12); b.setAllCaps(false); b.setTypeface(BOLD);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(dp(c, 40)); b.setMinimumHeight(dp(c, 40));
        b.setPadding(dp(c, 12), 0, dp(c, 12), 0); b.setSingleLine(true); b.setEllipsize(TextUtils.TruncateAt.END);
        style(b, primary, false);
        if (listener != null) b.setOnClickListener(listener);
        return b;
    }
    /** Pill used for categories and tags. */
    public static TextView chip(Context c, String s, boolean selected, View.OnClickListener listener) {
        TextView v = text(c, s, 13, selected ? 0xffffffff : TEXT); v.setTypeface(MEDIUM);
        v.setGravity(Gravity.CENTER); v.setSingleLine(true);
        v.setPadding(dp(c, 12), 0, dp(c, 12), 0); v.setMinHeight(dp(c, 34));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33d8232a),
                surface(c, selected ? RED : PANEL, selected ? RED_DARK : EDGE, 17), null));
        v.setClickable(true); v.setFocusable(true); v.setOnClickListener(listener);
        return v;
    }

    /** Dark display with a faint pixel grid, drawn only when invalidated. */
    public static final class Lcd extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private final float density;
        private Shader shader;
        public Lcd(Context c) { density = c.getResources().getDisplayMetrics().density; }
        @Override protected void onBoundsChange(Rect r) {
            bounds.set(r.left + density, r.top + density, r.right - density, r.bottom - density);
            shader = new LinearGradient(0, r.top, 0, r.bottom, 0xff24282f, 0xff15181d, Shader.TileMode.CLAMP);
        }
        @Override public void draw(Canvas c) {
            paint.setStyle(Paint.Style.FILL); paint.setShader(shader);
            c.drawRoundRect(bounds, 6 * density, 6 * density, paint); paint.setShader(null);
            c.save(); c.clipRect(bounds); paint.setColor(0x0affffff); paint.setStrokeWidth(density / 2);
            for (float y = bounds.top; y < bounds.bottom; y += 3 * density) c.drawLine(bounds.left, y, bounds.right, y, paint);
            c.restore();
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density); paint.setColor(0xff3a3f48);
            c.drawRoundRect(bounds, 6 * density, 6 * density, paint);
            paint.setColor(RED); paint.setStyle(Paint.Style.FILL);
            c.drawRect(bounds.left + 6 * density, bounds.bottom - 3 * density, bounds.left + 46 * density, bounds.bottom - 1.5f * density, paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
