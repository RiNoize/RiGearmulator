package com.rinoize.rigear;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.view.*;
import android.widget.*;

/** Native black/graphite skin with bright blue hardware-style controls. */
public final class StudioUi {
    public static final int BG = 0xff05080d, PANEL = 0xff111820, PANEL_DARK = 0xff090d12, LINE = 0xff314354;
    public static final int TEXT = 0xffeef7ff, MUTED = 0xff9bb0c5, BLUE = 0xff00a8ff,
        ORANGE = 0xffffa342, GREEN = 0xff55db8a, PURPLE = 0xff9e8cff, RED = 0xffff5468;
    public final Context context;
    private final Typeface condensed = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    private final Typeface condensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD);

    public StudioUi(Context context) { this.context = context; }
    public int dp(float n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }
    public LinearLayout row() { LinearLayout v = new LinearLayout(context); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    public LinearLayout column() { LinearLayout v = new LinearLayout(context); v.setOrientation(LinearLayout.VERTICAL); return v; }

    public TextView text(String value, float size, int color) {
        if ("Cargar ROM en CONFIG".equals(value)) {
            SynthDisplay display = new SynthDisplay(context);
            display.setText(value);
            return display;
        }
        TextView t = new TextView(context);
        t.setText(value); t.setTextColor(color); t.setTextSize(size); t.setGravity(Gravity.CENTER_VERTICAL);
        t.setTypeface(condensed);
        return t;
    }

    public GradientDrawable box(int color, boolean active) {
        int top = active ? 0xff123958 : 0xff17212b;
        int bottom = active ? 0xff071a2a : 0xff080c11;
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        d.setCornerRadius(dp(7)); d.setStroke(dp(active ? 2 : 1), active ? color : LINE); return d;
    }

    public Button button(String label, int accent, View.OnClickListener listener) {
        Button b = new Button(context) {
            @Override protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                ViewParent parent = getParent();
                if (parent instanceof ViewGroup && parent.getParent() instanceof AbsListView) {
                    ((ViewGroup) parent).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                    setFocusable(false); setFocusableInTouchMode(false);
                }
            }
        };
        b.setText(label); b.setTextSize(label.length() > 12 ? 10 : 11); b.setTextColor(TEXT); b.setAllCaps(false);
        b.setTypeface(condensedBold); b.setGravity(Gravity.CENTER);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(dp(40)); b.setMinimumHeight(dp(40));
        b.setPadding(dp(9), dp(4), dp(9), dp(4));
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_selected}, box(accent, true));
        drawable.addState(new int[]{android.R.attr.state_pressed}, box(accent, true));
        drawable.addState(new int[]{-android.R.attr.state_enabled}, box(0xff34404b, false));
        drawable.addState(new int[]{}, box(accent, false)); b.setBackground(drawable);
        if (listener != null) b.setOnClickListener(listener); return b;
    }

    public LinearLayout card(String title, int accent) {
        LinearLayout card = column(); card.setBackground(box(accent, false)); card.setPadding(dp(9), dp(6), dp(9), dp(6));
        TextView heading = text(title, 14, accent); heading.setTypeface(condensedBold); heading.setLetterSpacing(.05f);
        card.addView(heading, new LinearLayout.LayoutParams(-1, dp(28)));
        return card;
    }

    public void field(EditText field) {
        field.setTextColor(TEXT); field.setHintTextColor(0xff6e8499); field.setTextSize(14); field.setSingleLine(true);
        field.setTypeface(condensed); field.setPadding(dp(14), 0, dp(14), 0); field.setBackground(box(BLUE, false));
        if (android.os.Build.VERSION.SDK_INT >= 21) field.setBackgroundTintList(ColorStateList.valueOf(0xffffffff));
    }

    public void styleSpinner(Spinner spinner) {
        spinner.setPadding(dp(8), 0, dp(8), 0); spinner.setBackground(box(BLUE, false));
        if (android.os.Build.VERSION.SDK_INT >= 21) spinner.setPopupBackgroundDrawable(box(BLUE, false));
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
            value = min; setClickable(true); setFocusable(true); setMinimumHeight(dp(104)); setContentDescription(label);
        }
        public void show(int next) {
            if (isPressed()) return;
            boolean valid = next >= min && next <= max;
            if (known == valid && (!valid || value == next)) return;
            known = valid; if (known) value = next; invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), x = w / 2, y = h * .41f;
            float r = Math.max(dp(15), Math.min(w * .34f, (h - dp(39)) * .43f));

            // Hardware tick marks.
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i <= 20; ++i) {
                double a = Math.toRadians(135 + 270.0 * i / 20.0);
                float r1 = r * .91f, r2 = r * (i % 5 == 0 ? 1.04f : .99f);
                paint.setStrokeWidth(i % 5 == 0 ? dp(1.3f) : dp(.8f)); paint.setColor(0xff71869a);
                canvas.drawLine(x+(float)Math.cos(a)*r1, y+(float)Math.sin(a)*r1,
                        x+(float)Math.cos(a)*r2, y+(float)Math.sin(a)*r2, paint);
            }

            paint.setStyle(Paint.Style.FILL); paint.setColor(0x55000000); canvas.drawCircle(x, y + dp(5), r * .83f, paint);
            paint.setShader(new RadialGradient(x-r*.22f, y-r*.28f, r*1.15f,
                    new int[]{0xff53606b, 0xff202831, 0xff05070a}, new float[]{0f,.46f,1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, r * .78f, paint); paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(0xff71808e); canvas.drawCircle(x, y, r*.78f, paint);

            rect.set(x-r, y-r, x+r, y+r); paint.setStrokeWidth(dp(5)); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xff1d2c39); canvas.drawArc(rect, 135, 270, false, paint);
            float sweep = 270f * (value - min) / Math.max(1, max - min);
            if (known) {
                paint.setShadowLayer(dp(6), 0, 0, accent); paint.setColor(isEnabled() ? accent : MUTED);
                canvas.drawArc(rect, 135, sweep, false, paint); paint.clearShadowLayer();
            }

            double a = Math.toRadians(135 + sweep); paint.setColor(0xfff6fbff); paint.setStrokeWidth(dp(2.5f));
            canvas.drawLine(x+(float)Math.cos(a)*r*.18f, y+(float)Math.sin(a)*r*.18f,
                    x+(float)Math.cos(a)*r*.62f, y+(float)Math.sin(a)*r*.62f, paint);

            paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER); paint.setTypeface(condensedBold);
            paint.setTextSize(context.getResources().getDisplayMetrics().scaledDensity * 11); paint.setColor(TEXT);
            float measure = paint.measureText(label); if (measure > w-dp(4)) paint.setTextSize(paint.getTextSize()*(w-dp(4))/measure);
            canvas.drawText(label, x, h-dp(22), paint);
            paint.setTextSize(context.getResources().getDisplayMetrics().scaledDensity * 12); paint.setColor(accent);
            canvas.drawText(known ? format.display(value) : "—", x, h-dp(5), paint);
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
