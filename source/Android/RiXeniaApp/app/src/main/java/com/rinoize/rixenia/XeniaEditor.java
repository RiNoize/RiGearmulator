package com.rinoize.rixenia;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.IntFunction;

/** Five native synthesis panels matching the approved cyan/LCD concept.
 * Only confirmed Single parameters are editable. No synthetic CPU/voice meters or audio scope.
 */
public final class XeniaEditor extends LinearLayout {
    public interface Listener { boolean parameter(int index,int raw); void gain(int db); void testNote(boolean on); }
    private final Listener listener;
    private final int[] values=new int[256];
    private final List<Binding> knobs=new ArrayList<>();
    private final List<Choice> choices=new ArrayList<>();
    private final List<Diagram> diagrams=new ArrayList<>();
    private final LinearLayout filterCard,envCard,lfoCard;
    private boolean editable,masterAvailable,syncing,noteHeld;
    private int filter,env,lfo,epoch;
    private SeekBar master;
    private TextView masterText;
    private Button test;
    private AlertDialog dialog;
    private static final class Binding { final int index;final TouchKnob knob; Binding(int i,TouchKnob k){index=i;knob=k;} }
    private static final class Choice { final int index;final String prefix;final String[] names;final Button button;
        Choice(int i,String p,String[] n,Button b){index=i;prefix=p;names=n;button=b;} }
    public XeniaEditor(Context c,Listener callback){
        super(c);listener=callback;Arrays.fill(values,-1);setOrientation(VERTICAL);setPadding(0,dp(5),0,0);
        LinearLayout upper=SynthUi.row(c),lower=SynthUi.row(c);upper.setGravity(Gravity.TOP);lower.setGravity(Gravity.TOP);
        addView(upper,new LayoutParams(-1,0,1));addView(lower,new LayoutParams(-1,0,1.14f));
        LinearLayout osc=SynthUi.card(c);filterCard=SynthUi.card(c);
        addCard(upper,osc,.58f);addCard(upper,filterCard,.42f);
        envCard=SynthUi.card(c);lfoCard=SynthUi.card(c);LinearLayout out=SynthUi.card(c);
        addCard(lower,envCard,.38f);addCard(lower,lfoCard,.39f);addCard(lower,out,.23f);
        buildOsc(osc);buildFilter();buildEnvelope();buildLfo();buildOutput(out);
    }
    private int dp(float n){return SynthUi.dp(getContext(),n);}
    private void addCard(LinearLayout row,View view,float weight){LayoutParams p=new LayoutParams(0,-1,weight);p.setMargins(dp(2),dp(3),dp(2),dp(3));row.addView(view,p);}
    @Override protected void onMeasure(int width,int height){
        int h=MeasureSpec.getMode(height)==MeasureSpec.UNSPECIFIED?dp(320):Math.max(dp(320),MeasureSpec.getSize(height));
        super.onMeasure(width,MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY));
    }
    private LinearLayout heading(LinearLayout panel,String title){
        LinearLayout row=SynthUi.row(getContext());TextView t=SynthUi.text(getContext(),title,12,SynthUi.CYAN);
        t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));t.setSingleLine(true);t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(t,new LayoutParams(0,-2,1));panel.addView(row,new LayoutParams(-1,dp(25)));SynthUi.rule(panel);return row;
    }
    private Button small(String title,boolean selected,Runnable run){
        Button b=SynthUi.button(getContext(),title,SynthUi.MUTED,v->run.run());b.setTextSize(10);b.setMinHeight(0);b.setMinimumHeight(0);SynthUi.styleButton(b,SynthUi.MUTED,selected);return b;
    }
    private void tab(LinearLayout row,String label,boolean active,Runnable click){
        Button b=small(label,active,click);LayoutParams p=new LayoutParams(0,dp(30),1);p.setMargins(dp(1),0,dp(1),0);row.addView(b,p);
    }
    private void selector(LinearLayout parent,int index,String prefix,String[] names,float weight){
        Button b=SynthUi.button(getContext(),prefix+" — ▾",SynthUi.TEXT,v->choose(index,prefix,names));b.setTextSize(11);b.setMinHeight(0);b.setMinimumHeight(0);
        LayoutParams p=new LayoutParams(0,dp(32),weight);p.setMargins(dp(2),0,dp(2),0);parent.addView(b,p);choices.add(new Choice(index,prefix,names,b));
    }
    private void choose(int index,String title,String[] names){
        if(!editable)return;final int generation=epoch;
        List<String> labels=new ArrayList<>();List<Integer> raw=new ArrayList<>();int checked=-1;
        for(int i=0;i<names.length;i++)if(!names[i].isEmpty()){
            if(i==values[index])checked=labels.size();labels.add(String.format(Locale.ROOT,"%03d   %s",i,names[i]));raw.add(i);
        }
        dialog=new AlertDialog.Builder(getContext()).setTitle(title).setSingleChoiceItems(labels.toArray(new String[0]),checked,(d,n)->{
            if(editable&&generation==epoch&&isAttachedToWindow())edit(index,raw.get(n));d.dismiss();
        }).setNegativeButton("Cerrar",null).create();dialog.show();
    }
    private TouchKnob knob(LinearLayout row,int index,String title){
        XtParameters.Param p=XtParameters.get(index);
        IntFunction<String> formatter=raw->(index==159||index==166)?XtParameters.rate(raw,values[index+3]):p.format(raw);
        TouchKnob k=new TouchKnob(getContext(),title,p.min,p.max,formatter,raw->edit(index,raw));k.setTag("param:"+index);
        row.addView(k,new LayoutParams(0,-1,1));knobs.add(new Binding(index,k));return k;
    }
    private boolean edit(int index,int raw){
        if(!editable||!XtParameters.accepts(index,raw))return false;
        if(!listener.parameter(index,raw))return false;
        values[index]=raw;refresh();return true;
    }
    private void buildOsc(LinearLayout card){
        LinearLayout head=heading(card,"∿  OSCILADOR / WAVETABLE");
        Button more=small("AFINACIÓN / MIX",false,()->oscillatorDialog());head.addView(more,new LayoutParams(dp(110),-1));
        LinearLayout row=SynthUi.row(getContext());row.setGravity(Gravity.TOP);card.addView(row,new LayoutParams(-1,0,1));
        LinearLayout browse=SynthUi.column(getContext());row.addView(browse,new LayoutParams(0,-1,1.7f));
        TextView label=SynthUi.text(getContext(),"Tabla de ondas",11,SynthUi.MUTED);browse.addView(label);
        LinearLayout select=SynthUi.row(getContext());browse.addView(select);
        Button prev=small("‹",false,()->stepWave(-1));select.addView(prev,new LayoutParams(dp(28),dp(32)));
        selector(select,25,"",XtParameters.WAVES,1);
        Button next=small("›",false,()->stepWave(1));select.addView(next,new LayoutParams(dp(28),dp(32)));
        Diagram positions=new Diagram(getContext(),false);diagrams.add(positions);browse.addView(positions,new LayoutParams(-1,0,1));
        knob(row,26,"Wave 1 inicio");knob(row,36,"Wave 2 inicio");knob(row,7,"FM Amount");
    }
    private void stepWave(int delta){
        if(!editable)return;int n=values[25];
        for(int k=0;k<128;k++){n=(n+delta+128)%128;if(!XtParameters.WAVES[n].isEmpty()){edit(25,n);return;}}
    }
    private void removeBindings(LinearLayout card){
        knobs.removeIf(b->inside(b.knob,card));choices.removeIf(b->inside(b.button,card));diagrams.removeIf(b->inside(b,card));card.removeAllViews();
    }
    private boolean inside(View v,View parent){for(android.view.ViewParent p=v.getParent();p!=null;p=p.getParent())if(p==parent)return true;return false;}
    private void buildFilter(){
        removeBindings(filterCard);LinearLayout head=heading(filterCard,"⊣  FILTROS");
        head.getLayoutParams().height=dp(32);
        selector(head,filter==0?64:74,"",filter==0?XtParameters.FILTER1:XtParameters.FILTER2,1.7f);
        Button b1=small("F1",filter==0,()->{filter=0;buildFilter();refresh();});Button b2=small("F2",filter==1,()->{filter=1;buildFilter();refresh();});
        head.addView(b1,new LayoutParams(dp(32),-1));head.addView(b2,new LayoutParams(dp(32),-1));
        LinearLayout row=SynthUi.row(getContext());filterCard.addView(row,new LayoutParams(-1,0,1));
        if(filter==0){knob(row,62,"Cutoff");knob(row,63,"Resonancia");knob(row,65,"Key Track");}
        else{knob(row,73,"Cutoff F2");knob(row,75,"Key Track F2");}
    }
    private void buildEnvelope(){
        removeBindings(envCard);heading(envCard,"⌁  ENVOLVENTES");
        LinearLayout tabs=SynthUi.row(getContext());envCard.addView(tabs);
        tab(tabs,"AMPLITUD",env==0,()->{env=0;buildEnvelope();refresh();});tab(tabs,"FILTRO",env==1,()->{env=1;buildEnvelope();refresh();});
        LinearLayout row=SynthUi.row(getContext());envCard.addView(row,new LayoutParams(-1,0,1));int start=env==0?119:113;
        knob(row,start,"Ataque");knob(row,start+1,"Decaimiento");knob(row,start+2,"Sostenido");knob(row,start+3,"Liberación");
    }
    private void buildLfo(){
        removeBindings(lfoCard);LinearLayout head=heading(lfoCard,"∿  MODULACIÓN");
        head.addView(small("LFO 1",lfo==0,()->{lfo=0;buildLfo();refresh();}),new LayoutParams(dp(46),-1));
        head.addView(small("LFO 2",lfo==1,()->{lfo=1;buildLfo();refresh();}),new LayoutParams(dp(46),-1));
        head.addView(small("MATRIZ",false,()->matrixDialog()),new LayoutParams(dp(55),-1));
        int base=lfo==0?159:166;LinearLayout choice=SynthUi.row(getContext());lfoCard.addView(choice);
        selector(choice,base+1,"",XtParameters.LFO_SHAPES,1);selector(choice,base+3,"Sync",XtParameters.LFO_SYNC,.8f);
        Diagram shape=new Diagram(getContext(),true);diagrams.add(shape);choice.addView(shape,new LayoutParams(dp(55),dp(32)));
        LinearLayout row=SynthUi.row(getContext());lfoCard.addView(row,new LayoutParams(-1,0,1));
        knob(row,base,"Velocidad");knob(row,base+2,"Delay");knob(row,base+4,"Simetría");knob(row,base+5,"Humanize");
    }
    private void buildOutput(LinearLayout card){
        LinearLayout head=heading(card,"▥  SALIDA");test=small("♪ C4",false,()->{});test.setContentDescription("Nota C4, mantener pulsado");
        test.setOnTouchListener((v,e)->{
            if(!masterAvailable)return false;
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){noteHeld=true;listener.testNote(true);v.setPressed(true);return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){releaseNote();v.setPressed(false);return true;}return true;
        });head.addView(test,new LayoutParams(dp(48),-1));
        LinearLayout row=SynthUi.row(getContext());card.addView(row,new LayoutParams(-1,0,1));knob(row,77,"Vol. patch");knob(row,84,"Panorama");
        masterText=SynthUi.text(getContext(),"MASTER · −6 dB",10,SynthUi.ORANGE);card.addView(masterText);
        master=new SeekBar(getContext());master.setMax(24);master.setProgress(18);master.setProgressTintList(android.content.res.ColorStateList.valueOf(SynthUi.CYAN));
        master.setThumbTintList(android.content.res.ColorStateList.valueOf(SynthUi.CYAN));card.addView(master,new LayoutParams(-1,dp(28)));
        master.setEnabled(false);test.setEnabled(false);
        master.setContentDescription("Volumen general, menos 24 a 0 dB");
        master.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int n,boolean user){if(user&&!syncing&&masterAvailable){masterText.setText("MASTER · "+(n-24)+" dB");listener.gain(n-24);}}
            public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}
        });
    }
    private void releaseNote(){if(noteHeld){noteHeld=false;listener.testNote(false);}}
    public void bind(int[] parameters,int gain){
        if(parameters!=null&&parameters.length==256)System.arraycopy(parameters,0,values,0,256);
        syncing=true;if(!master.isPressed())master.setProgress(Math.max(0,Math.min(24,gain+24)));syncing=false;
        masterText.setText("MASTER · "+gain+" dB");refresh();
    }
    public void setAvailable(boolean allowEdit,boolean allowMaster){
        if(editable!=allowEdit){editable=allowEdit;++epoch;if(!editable&&dialog!=null)dialog.dismiss();refresh();}
        if(masterAvailable!=allowMaster){masterAvailable=allowMaster;master.setEnabled(allowMaster);test.setEnabled(allowMaster);if(!allowMaster)releaseNote();}
    }
    private void refresh(){
        for(Binding b:knobs)b.knob.bind(values[b.index],editable);
        for(Choice c:choices){int v=values[c.index];String label=v<0?"—":XtParameters.choice(c.names,v);
            String title=(c.prefix.isEmpty()?"":c.prefix+" · ")+label+" ▾";
            if(!title.contentEquals(c.button.getText()))c.button.setText(title);c.button.setEnabled(editable&&v>=0);}
        for(Diagram d:diagrams)d.update(values[26],values[36],values[lfo==0?160:167]);
    }
    private void oscillatorDialog(){
        if(!editable)return;LinearLayout content=SynthUi.column(getContext());content.setPadding(dp(12),dp(10),dp(12),dp(6));
        List<Binding> transientKnobs=new ArrayList<>();int generation=epoch;
        String[][] labels={{"O1 semitonos","O1 detune","O2 semitonos","O2 detune"},{"Mix Wave 1","Mix Wave 2","Ring Mod","Ruido"}};
        int[][] ids={{2,3,13,14},{47,48,49,50}};
        for(int r=0;r<ids.length;r++){
            LinearLayout row=SynthUi.row(getContext());content.addView(row,new LayoutParams(dp(480),dp(120)));
            for(int i=0;i<ids[r].length;i++){int id=ids[r][i];XtParameters.Param p=XtParameters.get(id);
                TouchKnob k=new TouchKnob(getContext(),labels[r][i],p.min,p.max,p::format,n->editable&&generation==epoch&&edit(id,n));
                k.bind(values[id],true);row.addView(k,new LayoutParams(0,-1,1));transientKnobs.add(new Binding(id,k));}
        }
        dialog=new AlertDialog.Builder(getContext()).setTitle("Osciladores y mezcla · edición temporal").setView(content).setPositiveButton("Cerrar",null).create();
        dialog.setOnDismissListener(d->{for(Binding b:transientKnobs)b.knob.setEnabled(false);});dialog.show();
    }
    private void matrixDialog(){
        if(!editable)return;int generation=epoch;LinearLayout box=SynthUi.column(getContext());box.setPadding(dp(16),dp(6),dp(16),dp(6));
        Spinner slot=new Spinner(getContext()),source=new Spinner(getContext()),destination=new Spinner(getContext());
        String[] slots=new String[16];for(int i=0;i<16;i++)slots[i]="Ruta "+(i+1);
        slot.setAdapter(new ArrayAdapter<>(getContext(),android.R.layout.simple_spinner_dropdown_item,slots));
        source.setAdapter(new ArrayAdapter<>(getContext(),android.R.layout.simple_spinner_dropdown_item,XtParameters.SOURCES));
        destination.setAdapter(new ArrayAdapter<>(getContext(),android.R.layout.simple_spinner_dropdown_item,XtParameters.DESTINATIONS));
        box.addView(slot);box.addView(SynthUi.text(getContext(),"Fuente",12,SynthUi.CYAN));box.addView(source);
        box.addView(SynthUi.text(getContext(),"Destino",12,SynthUi.CYAN));box.addView(destination);
        TextView amountText=SynthUi.text(getContext(),"Cantidad",12,SynthUi.CYAN);box.addView(amountText);
        SeekBar amount=new SeekBar(getContext());amount.setMax(127);box.addView(amount);
        Runnable populate=()->{int b=192+slot.getSelectedItemPosition()*3;source.setSelection(Math.max(0,Math.min(31,values[b])));
            destination.setSelection(Math.max(0,Math.min(35,values[b+2])));amount.setProgress(Math.max(0,values[b+1]));amountText.setText("Cantidad · "+XtParameters.get(b+1).format(amount.getProgress()));};
        slot.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> a,View v,int n,long id){populate.run();}public void onNothingSelected(AdapterView<?> a){}});
        amount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int n,boolean user){amountText.setText("Cantidad · "+XtParameters.get(193).format(n));}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        box.addView(SynthUi.text(getContext(),"Sólo se modifica la ruta elegida al pulsar Aplicar.",11,SynthUi.MUTED));
        dialog=new AlertDialog.Builder(getContext()).setTitle("Matriz de modulación · 16 rutas").setView(box).setNegativeButton("Cancelar",null)
            .setPositiveButton("Aplicar",(d,n)->{if(editable&&generation==epoch&&isAttachedToWindow()){
                int b=192+slot.getSelectedItemPosition()*3;edit(b,source.getSelectedItemPosition());edit(b+1,amount.getProgress());edit(b+2,destination.getSelectedItemPosition());}}).create();dialog.show();populate.run();
    }
    @Override protected void onDetachedFromWindow(){releaseNote();if(dialog!=null){dialog.dismiss();dialog=null;}super.onDetachedFromWindow();}

    /** Honest schematics: wave start positions, or LFO shape; never a fabricated audio scope. */
    private static final class Diagram extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path line=new Path();private final boolean lfo;
        private static final double[] HOLD={.2,.8,-.3,-.7,.4,.1,-.5,.6};
        private final float d;private int w1=-1,w2=-1,shape=-1;
        Diagram(Context c,boolean lfo){super(c);this.lfo=lfo;d=getResources().getDisplayMetrics().density;
            setContentDescription(lfo?"Forma esquemática del LFO, no osciloscopio":"Posiciones iniciales Wave 1 y Wave 2 en la tabla");}
        void update(int a,int b,int s){if(a==w1&&b==w2&&s==shape)return;w1=a;w2=b;shape=s;rebuild();invalidate();}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){rebuild();}
        private void rebuild(){line.reset();if(!lfo||shape<0)return;float w=getWidth()-8*d,h=getHeight()-8*d;
            for(int i=0;i<=64;i++){double x=i/64.0,y;
                switch(shape){case 0:y=Math.sin(x*2*Math.PI);break;case 1:y=1-4*Math.abs(x-.5);break;case 2:y=x<.5?1:-1;break;case 3:y=2*x-1;break;
                    case 4:y=.6*Math.sin(x*17)+.3*Math.sin(x*39);break;default:y=HOLD[Math.min(7,i/8)];}
                float px=4*d+(float)x*w,py=4*d+(float)(1-y)*h*.5f;if(i==0)line.moveTo(px,py);else line.lineTo(px,py);}
        }
        @Override protected void onDraw(Canvas c){
            p.setStyle(Paint.Style.FILL);p.setColor(0xff071823);c.drawRoundRect(1,2,getWidth()-1,getHeight()-2,3*d,3*d,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d);p.setColor(0xff234455);
            c.drawLine(4*d,getHeight()*.5f,getWidth()-4*d,getHeight()*.5f,p);
            if(lfo){p.setColor(SynthUi.CYAN);p.setStrokeWidth(1.2f*d);c.drawPath(line,p);}
            else{float start=10*d,end=getWidth()-10*d,y=getHeight()*.43f;
                for(int i=0;i<16;i++){float x=start+(end-start)*i/15;c.drawLine(x,y-5*d,x,y+5*d,p);}
                p.setStyle(Paint.Style.FILL);p.setColor(SynthUi.CYAN);if(w1>=0)c.drawCircle(start+(end-start)*w1/63,y,3*d,p);
                p.setColor(SynthUi.ORANGE);if(w2>=0)c.drawCircle(start+(end-start)*w2/63,y,2*d,p);
                p.setColor(SynthUi.MUTED);p.setTextSize(9*d);p.setTextAlign(Paint.Align.CENTER);c.drawText("Posiciones W1 / W2 · 0–63",getWidth()*.5f,getHeight()-5*d,p);}
        }
    }
}
