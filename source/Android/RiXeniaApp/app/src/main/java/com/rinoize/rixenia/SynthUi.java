package com.rinoize.rixenia;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Native, event-driven skin. No bitmaps, external fonts, timers or render-thread work. */
public final class SynthUi {
    public static final int BG=0xff0b1119, PANEL=0xff121e28, EDGE=0xff304555;
    public static final int TEXT=0xffe0edf7, MUTED=0xff97adbf, CYAN=0xff32e1f3, ORANGE=0xffffa237;
    private SynthUi() {}
    public static int dp(Context c,float n) { return Math.round(n*c.getResources().getDisplayMetrics().density); }
    public static LinearLayout row(Context c) {
        LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;
    }
    public static LinearLayout column(Context c) {
        LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;
    }
    public static TextView text(Context c,String s,int sp,int color) {
        TextView v=new TextView(c);v.setText(s);v.setTextSize(sp);v.setTextColor(color);
        v.setFontFeatureSettings("tnum");v.setIncludeFontPadding(false);return v;
    }
    public static GradientDrawable surface(Context c,int top,int bottom,int edge,float radius) {
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,bottom});
        d.setCornerRadius(dp(c,radius));d.setStroke(dp(c,1),edge);return d;
    }
    public static void styleButton(Button b,int accent,boolean selected) {
        Context c=b.getContext();
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},
            new int[]{0xff6e8291,selected?CYAN:accent}));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x5545ddeb),
            surface(c,selected?0xff123b4d:0xff26333f,selected?0xff10232e:0xff17212b,selected?CYAN:EDGE,4),null));
        b.setStateListAnimator(null);b.setElevation(0);
    }
    public static Button button(Context c,String s,int color,View.OnClickListener listener) {
        Button b=new Button(c);b.setText(s);b.setTextSize(11);b.setAllCaps(false);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(c,36));b.setMinimumHeight(dp(c,36));
        b.setPadding(dp(c,6),0,dp(c,6),0);b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);styleButton(b,color,false);
        if(listener!=null)b.setOnClickListener(listener);return b;
    }
    public static LinearLayout card(Context c) {
        LinearLayout l=column(c);l.setPadding(dp(c,7),dp(c,5),dp(c,7),dp(c,5));
        l.setBackground(surface(c,0xff15222d,0xff0e1821,EDGE,6));return l;
    }
    public static void rule(LinearLayout box) {
        View v=new View(box.getContext());v.setBackgroundColor(EDGE);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(box.getContext(),1));
        p.topMargin=dp(box.getContext(),3);p.bottomMargin=dp(box.getContext(),4);box.addView(v,p);
    }
    /** Faint LCD grid is drawn only when invalidated, not animated. */
    public static final class LcdBackground extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds=new RectF();
        private final float density;
        private Shader shader;
        public LcdBackground(Context c){density=c.getResources().getDisplayMetrics().density;}
        @Override protected void onBoundsChange(Rect r) {
            bounds.set(r.left+2*density,r.top+2*density,r.right-2*density,r.bottom-2*density);
            shader=new LinearGradient(0,r.top,0,r.bottom,0xff064fd5,0xff063b9f,Shader.TileMode.CLAMP);
        }
        @Override public void draw(Canvas c) {
            paint.setStyle(Paint.Style.FILL);paint.setShader(shader);c.drawRoundRect(bounds,4*density,4*density,paint);paint.setShader(null);
            c.save();c.clipRect(bounds);paint.setColor(0x14052067);paint.setStrokeWidth(density/2);
            for(float x=bounds.left;x<bounds.right;x+=4*density)c.drawLine(x,bounds.top,x,bounds.bottom,paint);
            for(float y=bounds.top;y<bounds.bottom;y+=4*density)c.drawLine(bounds.left,y,bounds.right,y,paint);
            c.restore();paint.setColor(0xff349bff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(density);
            c.drawRoundRect(bounds,4*density,4*density,paint);paint.setStyle(Paint.Style.FILL);
        }
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter f){paint.setColorFilter(f);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
