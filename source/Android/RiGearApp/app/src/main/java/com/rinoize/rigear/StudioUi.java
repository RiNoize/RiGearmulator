package com.rinoize.rigear;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.Locale;

/** Native Android skin: no WebView, animation loop, bitmapped mockup or fictitious telemetry. */
public final class StudioUi {
    public static final int BG = 0xff0b1017, PANEL = 0xff141e2a, LINE = 0xff2b4055;
    public static final int TEXT = 0xffe0edfa, MUTED = 0xff9eb3c9, BLUE = 0xff48caff,
        ORANGE = 0xffffa04b, GREEN = 0xff78dc78, PURPLE = 0xffb990ed, RED = 0xffff6677;
    public final Context context;
    public StudioUi(Context context) { this.context = context; }
    public int dp(float n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }
    public LinearLayout row() { LinearLayout v = new LinearLayout(context); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    public LinearLayout column() { LinearLayout v = new LinearLayout(context); v.setOrientation(LinearLayout.VERTICAL); return v; }
    public TextView text(String value, float size, int color) {
        TextView t = new TextView(context); t.setText(value); t.setTextColor(color); t.setTextSize(size); t.setGravity(Gravity.CENTER_VERTICAL); return t;
    }
    public GradientDrawable box(int color, boolean active) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            active ? new int[]{0xff123d59, 0xff152637} : new int[]{PANEL, 0xff0c131c});
        d.setCornerRadius(dp(7)); d.setStroke(dp(1), color); return d;
    }
    public Button button(String label, int accent, View.OnClickListener listener) {
        Button b = new Button(context); b.setText(label); b.setTextSize(11); b.setTextColor(TEXT); b.setAllCaps(false);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(dp(38)); b.setMinimumHeight(dp(38));
        b.setPadding(dp(9), dp(4), dp(9), dp(4));
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_selected}, box(accent, true));
        drawable.addState(new int[]{android.R.attr.state_pressed}, box(accent, true));
        drawable.addState(new int[]{}, box(LINE, false)); b.setBackground(drawable);
        if (listener != null) b.setOnClickListener(listener); return b;
    }
    public LinearLayout card(String title, int accent) {
        LinearLayout card = column(); card.setBackground(box(accent, false)); card.setPadding(dp(7), dp(4), dp(7), dp(4));
        TextView heading = text(title, 12, accent); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD); card.addView(heading); return card;
    }
    public void equal(LinearLayout row, View view) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1); p.setMargins(dp(3), dp(2), dp(3), dp(2)); row.addView(view, p);
    }
    public void space(LinearLayout row, View view) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.setMargins(dp(3), dp(2), dp(3), dp(2)); row.addView(view, p);
    }
    public interface Change { int apply(int value); }
    public interface Format { String display(int value); }
    public final class Dial extends View {
        public final String label;
        private final int min, max, accent;
        private final Change change;
        private final Format format;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int value;
        private boolean known, moved;
        private float downY; private int downValue;
        public Dial(String label, int min, int max, int accent, Change change, Format format) {
            super(context); this.label = label; this.min = min; this.max = max; this.accent = accent; this.change = change; this.format = format;
            value = min; setClickable(true); setFocusable(true); setMinimumHeight(dp(90)); setContentDescription(label);
        }
        public void show(int next) {
            if (isPressed()) return;
            boolean valid = next >= min && next <= max;
            if (known == valid && (!valid || value == next)) return;
            known = valid; if (known) value = next; invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), x = w / 2, y = h * .40f;
            float r = Math.max(4, Math.min(w * .35f, (h - dp(36)) * .44f));
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xff05080c); canvas.drawCircle(x, y + dp(3), r + dp(2), paint);
            paint.setColor(isEnabled() ? 0xff28323e : 0xff1a232d); canvas.drawCircle(x, y, r, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(0xff4b5b6c); canvas.drawCircle(x, y, r * .88f, paint);
            rect.set(x-r, y-r, x+r, y+r); paint.setStrokeWidth(dp(3)); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(LINE); canvas.drawArc(rect, 135, 270, false, paint);
            float sweep = 270f * (value - min) / Math.max(1, max - min);
            paint.setColor(isEnabled() ? accent : MUTED); if (known) canvas.drawArc(rect, 135, sweep, false, paint);
            double a = Math.toRadians(135 + sweep); paint.setColor(TEXT); paint.setStrokeWidth(dp(2));
            canvas.drawLine(x+(float)Math.cos(a)*r*.3f, y+(float)Math.sin(a)*r*.3f,
                            x+(float)Math.cos(a)*r*.70f, y+(float)Math.sin(a)*r*.70f, paint);
            paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
            float font = context.getResources().getDisplayMetrics().scaledDensity;
            paint.setTextSize(11*font); paint.setColor(TEXT);
            // Fit labels to their actual cell, not to a mockup's fixed pixel width.
            float measure = paint.measureText(label); if (measure > w-dp(4)) paint.setTextSize(paint.getTextSize()*(w-dp(4))/measure);
            canvas.drawText(label, x, h-dp(21), paint);
            paint.setTextSize(12*font); paint.setColor(accent); canvas.drawText(known ? format.display(value) : "—", x, h-dp(5), paint);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getY(); downValue = value; moved = false; setPressed(true);
                    getParent().requestDisallowInterceptTouchEvent(true); return true;
                case MotionEvent.ACTION_MOVE:
                    float delta = (downY-e.getY()) / context.getResources().getDisplayMetrics().density;
                    if (Math.abs(delta) > 3) moved = true;
                    int next = Math.max(min, Math.min(max, downValue + Math.round(delta / 1.6f)));
                    if (next != value) { value = change.apply(next); known = true; invalidate(); } return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    setPressed(false); getParent().requestDisallowInterceptTouchEvent(false);
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && !moved) performClick(); return true;
                default: return true;
            }
        }
        @Override public boolean performClick() {
            super.performClick(); if (!isEnabled()) return true;
            EditText input = new EditText(context); input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
            input.setText(Integer.toString(value)); input.selectAll();
            AlertDialog dialog = new AlertDialog.Builder(context).setTitle(label + " · " + min + "–" + max)
                .setView(input).setNegativeButton("Cancelar", null).setPositiveButton("Aplicar", null).create();
            dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
                try { int n = Integer.parseInt(input.getText().toString()); if (n < min || n > max) throw new NumberFormatException();
                    value = change.apply(n); known = true; invalidate(); dialog.dismiss();
                } catch (NumberFormatException ex) { input.setError("Valor entre " + min + " y " + max); }
            })); dialog.show(); return true;
        }
    }
}
