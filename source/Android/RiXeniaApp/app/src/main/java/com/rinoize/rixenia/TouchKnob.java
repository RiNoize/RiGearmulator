package com.rinoize.rixenia;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.SeekBar;
import java.util.function.IntPredicate;
import java.util.function.IntFunction;

/** A native rotary control: vertical drag, tap for exact raw value, keyboard and accessibility.
 * Refreshing state never emits MIDI. Drawing uses cached objects and has no animation loop.
 */
public final class TouchKnob extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    private final RectF rim=new RectF(),body=new RectF();
    private final float d,sp;
    private final int min,max;
    private final String label;
    private final IntFunction<String> format;
    private final IntPredicate change;
    private int raw=-1,startValue,pointer=-1;
    private float downY,downX,cx,cy,radius;
    private boolean moved;
    private String valueText="—";
    private Shader face;
    private AlertDialog dialog;

    public TouchKnob(Context c,String name,int low,int high,IntFunction<String> formatter,IntPredicate callback){
        super(c);if(low>high)throw new IllegalArgumentException("Invalid knob range");
        label=name;min=low;max=high;format=formatter;change=callback;
        d=getResources().getDisplayMetrics().density;sp=getResources().getDisplayMetrics().scaledDensity;
        setFocusable(true);setClickable(true);setContentDescription(name);setMinimumHeight((int)(78*d));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    public int value(){return raw;}
    public void bind(int value,boolean enabled){
        boolean known=value>=min&&value<=max;
        if(pointer<0 || !enabled){
            int next=known?value:-1;
            if(next!=raw){raw=next;updateText();invalidate();}
            else if(raw>=0){String s=format.apply(raw);if(!s.equals(valueText)){valueText=s;invalidate();}}
        }
        setEnabled(enabled&&known);
    }
    @Override public void setEnabled(boolean enabled){
        if(isEnabled()!=enabled){super.setEnabled(enabled);if(!enabled){pointer=-1;setPressed(false);if(dialog!=null)dialog.dismiss();}invalidate();}
    }
    private void updateText(){valueText=raw<0?"—":format.apply(raw);setContentDescription(label+", "+valueText);}
    private void userValue(int v){
        int next=Math.max(min,Math.min(max,v));
        if(!isEnabled()||next==raw)return;
        int old=raw;raw=next;updateText();invalidate();
        if(!change.test(next)){raw=old;updateText();invalidate();}
    }
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){
        cx=w*.5f;cy=(h+4*d)*.5f;
        radius=Math.max(8*d,Math.min(w*.40f,(h-40*d)*.48f));
        rim.set(cx-radius,cy-radius,cx+radius,cy+radius);
        body.set(cx-radius+5*d,cy-radius+5*d,cx+radius-5*d,cy+radius-5*d);
        face=new LinearGradient(cx,body.top,cx,body.bottom,0xff354151,0xff111922,Shader.TileMode.CLAMP);
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(typeface);
        p.setTextSize(Math.min(11*sp,getWidth()/Math.max(1,label.length())*1.65f));p.setColor(SynthUi.TEXT);
        c.drawText(label,cx,14*d,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3*d);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(0xff3e4d60);
        c.drawArc(rim,135,270,false,p);
        if(raw>=0){p.setColor(isEnabled()?SynthUi.CYAN:0xff40717e);c.drawArc(rim,135,270f*(raw-min)/Math.max(1,max-min),false,p);}
        p.setStyle(Paint.Style.FILL);p.setShader(face);c.drawOval(body,p);p.setShader(null);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d);p.setColor(0xff596579);c.drawOval(body,p);
        if(raw>=0){double angle=Math.toRadians(135+270.0*(raw-min)/Math.max(1,max-min));
            p.setStrokeWidth(2.5f*d);p.setColor(isEnabled()?SynthUi.CYAN:0xff40717e);
            c.drawLine(cx+(float)Math.cos(angle)*radius*.45f,cy+(float)Math.sin(angle)*radius*.45f,
                cx+(float)Math.cos(angle)*radius*.74f,cy+(float)Math.sin(angle)*radius*.74f,p);}
        p.setStyle(Paint.Style.FILL);p.setColor(SynthUi.TEXT);p.setTextSize(Math.min(12*sp,getWidth()/Math.max(1,valueText.length())*1.6f));
        c.drawText(valueText,cx,getHeight()-5*d,p);
        if(isFocused()||isPressed()){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d);p.setColor(SynthUi.CYAN);c.drawRoundRect(1,1,getWidth()-1,getHeight()-1,4*d,4*d,p);}
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(!isEnabled())return false;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                pointer=e.getPointerId(0);downX=e.getX();downY=e.getY();startValue=raw;moved=false;setPressed(true);
                getParent().requestDisallowInterceptTouchEvent(true);return true;
            case MotionEvent.ACTION_MOVE:
                int i=e.findPointerIndex(pointer);if(i<0)return true;
                float dy=downY-e.getY(i);
                if(Math.abs(dy)>4*d||Math.abs(e.getX(i)-downX)>6*d)moved=true;
                if(moved)userValue(startValue+Math.round(dy/(180*d)*(max-min)));return true;
            case MotionEvent.ACTION_POINTER_UP:
                if(e.getPointerId(e.getActionIndex())==pointer){pointer=-1;setPressed(false);getParent().requestDisallowInterceptTouchEvent(false);}return true;
            case MotionEvent.ACTION_UP:
                boolean tap=pointer>=0&&!moved;pointer=-1;setPressed(false);getParent().requestDisallowInterceptTouchEvent(false);
                if(tap)performClick();return true;
            case MotionEvent.ACTION_CANCEL:
                pointer=-1;setPressed(false);getParent().requestDisallowInterceptTouchEvent(false);return true;
            default:return true;
        }
    }
    @Override public boolean performClick(){
        super.performClick();if(!isEnabled()||raw<0)return true;
        EditText input=new EditText(getContext());input.setInputType(InputType.TYPE_CLASS_NUMBER);input.setSingleLine();input.setText(Integer.toString(raw));input.selectAll();
        dialog=new AlertDialog.Builder(getContext()).setTitle(label+" · valor MIDI "+min+"–"+max)
            .setView(input).setNegativeButton("Cancelar",null).setPositiveButton("Aplicar",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b->{
            try{int n=Integer.parseInt(input.getText().toString().trim());if(n<min||n>max)throw new NumberFormatException();
                if(isEnabled()&&isAttachedToWindow())userValue(n);dialog.dismiss();}
            catch(NumberFormatException ex){input.setError("Valor entre "+min+" y "+max);}
        }));dialog.show();return true;
    }
    @Override public boolean onKeyDown(int code,KeyEvent e){
        if(isEnabled()){
            if(code==KeyEvent.KEYCODE_DPAD_UP||code==KeyEvent.KEYCODE_DPAD_RIGHT){userValue(raw+1);return true;}
            if(code==KeyEvent.KEYCODE_DPAD_DOWN||code==KeyEvent.KEYCODE_DPAD_LEFT){userValue(raw-1);return true;}
            if(code==KeyEvent.KEYCODE_ENTER||code==KeyEvent.KEYCODE_DPAD_CENTER){performClick();return true;}
        }return super.onKeyDown(code,e);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);info.setClassName(SeekBar.class.getName());
        if(raw>=0)info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,min,max,raw));
        if(isEnabled()){info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);}
    }
    @Override public boolean performAccessibilityAction(int action,Bundle args){
        if(isEnabled()){
            if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD){userValue(raw+1);return true;}
            if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){userValue(raw-1);return true;}
            if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()&&args!=null){userValue(Math.round(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)));return true;}
        }return super.performAccessibilityAction(action,args);
    }
    @Override protected void onDetachedFromWindow(){pointer=-1;if(dialog!=null){dialog.dismiss();dialog=null;}super.onDetachedFromWindow();}
}
