package com.rinoize.rixenia;

import android.app.*;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.Arrays;

/** UI-only fixtures live exclusively in androidTest. No firmware, native synthesis or fake
 * performance mode in the distributed APK. Exercise real views on Android, not a mock renderer.
 */
public final class UiSmokeInstrumentation extends Instrumentation {
    private Bundle arguments;
    private Activity activity;
    private XeniaEditor editor;
    private final int[] writes={0};
    private final int[] parameters=new int[256];
    private String suffix;
    @Override public void onCreate(Bundle args){super.onCreate(args);arguments=args;start();}
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static Object field(Object obj,String name)throws Exception{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(obj);}
    private static Button button(View v,String text){
        if(v instanceof Button&&text.contentEquals(((Button)v).getText()))return (Button)v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){Button b=button(g.getChildAt(i),text);if(b!=null)return b;}}return null;
    }
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            suffix=arguments==null?"tablet":arguments.getString("label","tablet");
            activity=startActivitySync(new Intent().setClassName(getTargetContext(),"com.rinoize.rixenia.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            runOnMainSync(()->{
                try{
                    Field visible=activity.getClass().getDeclaredField("visible");visible.setAccessible(true);visible.setBoolean(activity,false);
                    FrameLayout body=(FrameLayout)field(activity,"body");body.removeAllViews();
                    Arrays.fill(parameters,64);parameters[25]=0;parameters[26]=16;parameters[36]=40;parameters[64]=0;parameters[74]=1;
                    parameters[160]=0;parameters[162]=0;parameters[167]=1;parameters[169]=0;
                    for(int i=0;i<16;i++){parameters[192+i*3]=1;parameters[193+i*3]=64;parameters[194+i*3]=9;}
                    editor=new XeniaEditor(activity,new XeniaEditor.Listener(){
                        public boolean parameter(int i,int v){check(XtParameters.accepts(i,v),"Invalid emitted parameter");parameters[i]=v;writes[0]++;return true;}
                        public void gain(int db){check(db>=-24&&db<=0,"Gain bounds");}
                        public void testNote(boolean on){}
                    });
                    ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.addView(editor,new ScrollView.LayoutParams(-1,-2));
                    body.addView(scroll,new FrameLayout.LayoutParams(-1,-1));editor.bind(parameters,-6);editor.setAvailable(true,true);
                    ((TextView)field(activity,"display")).setText("PRUEBA UI · SIN AUDIO\nRiXenia · Cyan Studio");
                    ((TextView)field(activity,"stats")).setText("Prueba de interfaz Android · datos de control de ensayo · motor no iniciado");
                    ((TextView)field(activity,"status")).setText("Arrastrá una perilla hacia arriba / abajo · Tocá para introducir el valor MIDI");
                    ((TextView)field(activity,"footer")).setText("Vista del APK de prueba · sin ROM · sin MIDI");
                    check(writes[0]==0,"Binding emitted MIDI");
                }catch(Exception e){throw new RuntimeException(e);}
            });waitForIdleSync();
            runOnMainSync(()->{
                View root=activity.getWindow().getDecorView();check(root.getWidth()>root.getHeight(),"Not landscape");
                TouchKnob cutoff=(TouchKnob)editor.findViewWithTag("param:62");check(cutoff!=null&&cutoff.getWidth()>0&&cutoff.getHeight()>0,"Missing cutoff bounds");
                int count=writes[0];cutoff.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));
                check(parameters[62]==65&&writes[0]==count+1,"Keyboard value not delivered");
                editor.bind(parameters,-6);check(writes[0]==count+1,"Readback echoed a command");
                long t=SystemClock.uptimeMillis();float x=cutoff.getWidth()/2f,y=cutoff.getHeight()/2f;
                MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0);
                MotionEvent move=MotionEvent.obtain(t,t+30,MotionEvent.ACTION_MOVE,x,y-600,0);
                MotionEvent up=MotionEvent.obtain(t,t+40,MotionEvent.ACTION_UP,x,y-600,0);
                cutoff.dispatchTouchEvent(down);cutoff.dispatchTouchEvent(move);cutoff.dispatchTouchEvent(up);
                down.recycle();move.recycle();up.recycle();check(parameters[62]==127,"Drag did not clamp at max");
                cutoff.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT));check(parameters[62]==126,"Key decrement failed");
                int before=writes[0];editor.setAvailable(false,true);cutoff.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));
                check(writes[0]==before,"Disabled editor emitted MIDI");editor.setAvailable(true,true);
                button(editor,"F2").performClick();check(editor.findViewWithTag("param:73")!=null&&editor.findViewWithTag("param:75")!=null,"Filter 2 rebinding");
                button(editor,"LFO 2").performClick();check(editor.findViewWithTag("param:166")!=null,"LFO 2 rebinding");
                button(editor,"FILTRO").performClick();check(editor.findViewWithTag("param:113")!=null,"Filter ADSR rebinding");
                button(editor,"F1").performClick();button(editor,"LFO 1").performClick();button(editor,"AMPLITUD").performClick();
                before=writes[0];button(editor,"MATRIZ").performClick();check(writes[0]==before,"Opening matrix overwrote patch");
                try{((AlertDialog)field(editor,"dialog")).cancel();}catch(Exception e){throw new RuntimeException(e);}
                check(writes[0]==before,"Cancelling matrix overwrote patch");
            });waitForIdleSync();screenshot("editor-"+suffix+".png");
            if(suffix.equals("compact")){
                runOnMainSync(()->((ScrollView)editor.getParent()).fullScroll(View.FOCUS_DOWN));waitForIdleSync();screenshot("editor-compact-bottom.png");
            }
            runOnMainSync(()->button(activity.getWindow().getDecorView(),"CONFIGURACIÓN").performClick());waitForIdleSync();screenshot("config-"+suffix+".png");
            runOnMainSync(()->button(activity.getWindow().getDecorView(),"BANCOS").performClick());waitForIdleSync();
            check(writes[0]>0,"No touch/keyboard test ran");
            runOnMainSync(()->activity.finish());
            result.putString("stream","UI_SMOKE_OK "+suffix+": layout, raw bounds, keyboard, drag, readback isolation, disabled state, filter/LFO/ADSR switching, matrix cancellation and tabs passed\n");
            finish(Activity.RESULT_OK,result);
        }catch(Throwable e){StringWriter s=new StringWriter();e.printStackTrace(new PrintWriter(s));result.putString("stream","UI_SMOKE_FAILED\n"+s);finish(Activity.RESULT_CANCELED,result);}
    }
    private void screenshot(String filename)throws Exception{
        Bitmap b=getUiAutomation().takeScreenshot();check(b!=null,"Screenshot unavailable");
        try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),filename))){b.compress(Bitmap.CompressFormat.PNG,100,out);}finally{b.recycle();}
    }
}
