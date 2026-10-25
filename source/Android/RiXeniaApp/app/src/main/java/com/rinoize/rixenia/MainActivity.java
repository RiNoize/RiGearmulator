package com.rinoize.rixenia;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** First standalone Xenia instrument. The process owns one serialized native engine. */
public final class MainActivity extends Activity {
    private static final ExecutorService ENGINE=Executors.newSingleThreadExecutor();
    private static volatile boolean engineUnsafe;
    private static final int PICK_ROM=201,PICK_BANK=202;
    private static final int BG=0xff11151b,PANEL=0xff202731,FG=0xffeaf1f7,MUTED=0xffa9b9c8,ORANGE=0xffff9b3f,BLUE=0xff77caff;
    private static final int[] BLOCKS={256,512,1024},RATES={48000,0},OUTPUTS={512,768,1024,1536,2048,4096};
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final RealtimeAudio audio=new RealtimeAudio();
    private final MeterWindow renderWindow=new MeterWindow();
    private final List<Button> needsEngine=new ArrayList<>();
    private final List<Slider> sliders=new ArrayList<>();
    private volatile boolean ready,destroyed,visible,midiGate;
    private boolean busy,nativeOk,syncing,currentKnown;
    private int page,bank,program,revision=-1,gainDb=-6;
    private long cpuBefore,wallBefore;
    private String romName="Sin ROM",lastError="",liveName="";
    private volatile PatchLibrary library=new PatchLibrary();
    private MidiInput midi;
    private SharedPreferences prefs;
    private TextView display,stats,status,footer,diagnostics;
    private Button play,stop,romButton,bankButton;
    private FrameLayout body;
    private Spinner blockChoice,rateChoice,modeChoice,outputChoice,midiChoice;
    private List<MidiInput.Choice> ports=new ArrayList<>();
    private RealtimeAudio.Session measured;
    private interface Job { void run() throws Exception; }
    private final Runnable tick=new Runnable(){public void run(){if(!visible||destroyed)return;refreshMeters();ui.postDelayed(this,1000);}};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences("rixenia-v1",MODE_PRIVATE);
        bank=Math.max(0,Math.min(1,prefs.getInt("bank",0)));program=Math.max(0,Math.min(127,prefs.getInt("program",0)));
        gainDb=Math.max(-24,Math.min(0,prefs.getInt("gain",-6)));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout root=column();root.setBackgroundColor(BG);root.setPadding(dp(10),dp(6),dp(10),dp(5));
        LinearLayout header=row();TextView title=text("RiXenia",22,ORANGE);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);header.addView(title);
        TextView sub=text("  Microwave II / XT  ·  0.1.0 α",12,MUTED);header.addView(sub,new LinearLayout.LayoutParams(0,-2,1));
        play=button("▶ INICIAR",ORANGE,v->startAudio());stop=button("■ DETENER",BLUE,v->stopAudio());
        header.addView(play);header.addView(stop);header.addView(button("PANIC",0xffff7787,v->panic()));root.addView(header);
        stats=text("Motor detenido",11,MUTED);root.addView(stats);
        LinearLayout patchBar=row();patchBar.setPadding(0,dp(6),0,dp(6));
        addEngineButton(patchBar,"BANK −",v->stepBank());
        addEngineButton(patchBar,"PATCH −",v->stepPatch(-1));
        display=text("Cargar ROM\nImportar banco de sonidos",19,0xffd7edff);display.setTypeface(Typeface.MONOSPACE);display.setGravity(Gravity.CENTER);display.setMinHeight(dp(60));display.setMaxLines(2);display.setBackground(shape(0xff07388c,dp(4)));display.setPadding(dp(5),dp(3),dp(5),dp(3));
        display.setOnClickListener(v->showPage(1));patchBar.addView(display,new LinearLayout.LayoutParams(0,dp(63),1));
        addEngineButton(patchBar,"PATCH +",v->stepPatch(1));addEngineButton(patchBar,"BANK +",v->stepBank());root.addView(patchBar);
        LinearLayout tabs=row();for(int i=0;i<3;++i){final int selected=i;Button b=button(new String[]{"TOCAR / EDITAR","BANCOS","CONFIGURACIÓN"}[i],BLUE,v->showPage(selected));tabs.addView(b,new LinearLayout.LayoutParams(0,dp(38),1));}root.addView(tabs);
        body=new FrameLayout(this);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        status=text("Cargá tu ROM BIN/ZIP, pulsá INICIAR y mantené NOTA C4 para la primera prueba.",12,FG);status.setMaxLines(2);root.addView(status);
        footer=text("MIDI desconectado",11,MUTED);footer.setMaxLines(2);root.addView(footer);setContentView(root);
        try{nativeOk=NativeBridge.nativeSelfTest()==1;}catch(RuntimeException|LinkageError e){message("No se pudo cargar el motor: "+e.getMessage());}
        midi=new MidiInput(this,ui,()->midiGate&&audio.isRunning()&&!destroyed,()->{if(page==2)refreshPorts();});
        showPage(0);controls();
        if(nativeOk)work("Buscando ROM y bancos guardados…",()->{
            File saved=new File(getFilesDir(),"banks.syx");
            if(saved.isFile())library=library.merged(BankFile.parse(AppFiles.read(saved,BankFile.MAX_BYTES),bank,program));
            File rom=new File(getFilesDir(),"firmware.bin");
            if(rom.isFile())boot(AppFiles.read(rom,262144),prefs.getString("romName","ROM guardada"),false);
            else post(()->message("Cargá la ROM completa .bin de 256 KiB desde CONFIGURACIÓN."));
        });
    }
    private void post(Runnable task){ui.post(()->{if(!destroyed)task.run();});}
    private void message(String text){if(status!=null)status.setText(text==null?"Operación fallida":text);}
    private void work(String label,Job job){
        if(busy||destroyed||!nativeOk||engineUnsafe){if(engineUnsafe)message("El motor no pudo detenerse. Usá Forzar detención en los ajustes de Android y volvé a abrir RiXenia.");return;}
        busy=true;message(label);controls();
        ENGINE.execute(()->{try{if(!destroyed)job.run();}catch(Exception|LinkageError e){post(()->message(e.getMessage()));}
            finally{post(()->{busy=false;controls();refreshDisplay();if(page==1)showPage(1);});}});
    }
    private void stopWorker() throws Exception {
        midiGate=false;
        try{audio.stop();}catch(Exception e){engineUnsafe=true;throw e;}
    }
    private void boot(byte[] rom,String name,boolean persist)throws Exception {
        AppFiles.validateRom(rom);stopWorker();ready=false;
        String result=NativeBridge.nativeLoadRom(rom,name);
        if(NativeBridge.nativeGetDeviceSampleRate()!=40000)throw new IllegalStateException("El motor Xenia no informó su frecuencia de 40000 Hz");
        byte[] banks=library.bytes();if(banks.length>0)NativeBridge.nativeImportSysex(banks);
        NativeBridge.nativeSelectPatch(bank,program);RuntimeBridge.requestPanel();
        romName=name;ready=true;liveName="";currentKnown=false;
        if(persist){AppFiles.atomic(new File(getFilesDir(),"firmware.bin"),rom);prefs.edit().putString("romName",name).apply();}
        post(()->{revision=-1;message(result+" · Pulsá INICIAR para tocar.");});
    }
    private void startAudio(){
        if(!ready){message("Primero cargá tu ROM en CONFIGURACIÓN.");return;}
        final int frames=BLOCKS[bounded(prefs.getInt("block",1),BLOCKS.length)],rate=RATES[bounded(prefs.getInt("rate",0),RATES.length)];
        final int mode=bounded(prefs.getInt("mode",0),2),output=OUTPUTS[bounded(prefs.getInt("output",2),OUTPUTS.length)],gain=gainDb;
        work("Iniciando audio…",()->{if(!visible)return;audio.start(frames,100,0,gain,mode,output,rate);midiGate=true;lastError="";post(()->message("Audio activo. Canal MIDI 1 por defecto; sustain, modulación y pitch bend habilitados."));});
    }
    private void stopAudio(){work("Deteniendo audio…",()->{stopWorker();RuntimeBridge.panic();post(()->message("Audio detenido"));});}
    private void panic(){
        if(!nativeOk||!ready||busy)return;
        try{RuntimeBridge.panic();midi.telemetry.reset(false);message("PANIC: notas y pedal liberados");}catch(RuntimeException e){message(e.getMessage());}
    }
    private void selectPatch(int targetBank,int targetProgram){
        if(!ready||busy)return;
        try{
            liveName="";currentKnown=false;String name=NativeBridge.nativeSelectPatch(targetBank,targetProgram);bank=targetBank;program=targetProgram;liveName=name==null?"":name;
            prefs.edit().putInt("bank",bank).putInt("program",program).apply();RuntimeBridge.requestPanel();revision=-1;
            refreshDisplay();message(library.has(bank,program)?"Sonido seleccionado":"Posición vacía elegida para importar. Podés seguir tocando el sonido actual; su nombre aparece como ACTUAL.");
        }catch(RuntimeException e){message(e.getMessage());}
    }
    private void stepPatch(int delta){
        List<Integer> slots=library.slots();
        if(slots.isEmpty()){message("Biblioteca vacía: importá un banco .syx o .mid desde BANCOS.");return;}
        int current=bank*128+program,next=delta>0?slots.get(0):slots.get(slots.size()-1);
        if(delta>0){for(int s:slots)if(s>current){next=s;break;}}
        else{for(int i=slots.size()-1;i>=0;--i)if(slots.get(i)<current){next=slots.get(i);break;}}
        selectPatch(next/128,next%128);
    }
    private void stepBank(){
        int target=(bank+1)%2;
        if(library.has(target,program)){selectPatch(target,program);return;}
        for(int slot:library.slots())if(slot/128==target){selectPatch(target,slot%128);return;}
        selectPatch(target,0);message("Banco "+(target==0?"A":"B")+" vacío: destino para importar un patch. El motor conserva el sonido ACTUAL.");
    }
    private void pick(int request){
        if(busy)return;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try{startActivityForResult(i,request);}catch(RuntimeException e){message("No se pudo abrir el selector: "+e.getMessage());}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        if(request==PICK_ROM)work("Cargando ROM y arrancando Microwave XT…",()->{
            RomFile imported=RomFile.parse(AppFiles.read(this,uri,RomFile.MAX_ARCHIVE_BYTES),AppFiles.name(this,uri));
            boot(imported.bytes,imported.name,true);post(()->message(imported.notice+" · Pulsá INICIAR para tocar."));
        });
        else if(request==PICK_BANK){final int destBank=bank,destProgram=program;
            work("Validando banco…",()->{
                BankFile imported=BankFile.parse(AppFiles.read(this,uri,BankFile.MAX_BYTES),destBank,destProgram);
                PatchLibrary next=library.merged(imported);
                stopWorker();
                // A 4 MB SMF may repeat dumps. Deduplicate its fixed address space before
                // JNI; valid singles/waves/tables occupy < 256 KiB, below native's 1 MiB cap.
                if(ready)NativeBridge.nativeImportSysex(new PatchLibrary().merged(imported).bytes());
                AppFiles.atomic(new File(getFilesDir(),"banks.syx"),next.bytes());library=next;
                List<Integer> slots=library.slots();
                if(!slots.isEmpty()&&!library.has(bank,program)){int first=slots.get(0);bank=first/128;program=first%128;}
                if(ready){NativeBridge.nativeSelectPatch(bank,program);RuntimeBridge.requestPanel();}
                prefs.edit().putInt("bank",bank).putInt("program",program).apply();
                post(()->{revision=-1;liveName="";message(imported.messages.size()+" dumps importados · "+library.slots().size()+" sonidos disponibles"+(imported.skipped>0?" · "+imported.skipped+" mensajes no compatibles omitidos":"")+". Pulsá INICIAR.");});
            });
        }
    }
    private void controls(){
        boolean available=nativeOk&&!busy&&!engineUnsafe;
        play.setEnabled(available&&ready&&!audio.hasLiveSession());stop.setEnabled(available&&audio.hasLiveSession());
        for(Button b:needsEngine)b.setEnabled(available&&ready);
        if(romButton!=null)romButton.setEnabled(available);if(bankButton!=null)bankButton.setEnabled(available);
        for(Slider s:sliders)s.bar.setEnabled(available&&ready&&s.known);
    }
    private void refreshDisplay(){
        boolean imported=library.has(bank,program);
        String name=liveName;
        if(name.isEmpty())name=library.name(bank,program);
        if(name.isEmpty())name=ready?(currentKnown?"(nombre vacío)":"Iniciá para consultar"):"Cargar ROM BIN / ZIP";
        String slot=String.format(Locale.ROOT,"%s%03d",bank==0?"A":"B",program+1);
        String heading=imported?slot+"  ·  SINGLE":currentKnown?"ACTUAL · destino "+slot:"DESTINO "+slot;
        display.setText(heading+"\n"+name);
    }
    private void showPage(int selected){
        page=selected;sliders.clear();romButton=bankButton=null;diagnostics=null;body.removeAllViews();
        LinearLayout content=column();content.setPadding(0,dp(8),0,dp(6));
        if(page==0)buildPlay(content);else if(page==1)buildBanks(content);else buildConfig(content);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(content);body.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        refreshDisplay();controls();
    }
    private void buildPlay(LinearLayout content){
        LinearLayout intro=row();TextView hint=text("WAVETABLE SYNTHESIZER",12,ORANGE);intro.addView(hint,new LinearLayout.LayoutParams(0,-2,1));
        Button test=button("NOTA C4  ·  mantener",BLUE,null);test.setOnTouchListener((v,e)->{
            if(!ready||busy||!audio.isRunning())return false;
            try{if(e.getActionMasked()==MotionEvent.ACTION_DOWN){NativeBridge.nativeNoteOn(60,100);return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){NativeBridge.nativeNoteOff(60);if(e.getActionMasked()==MotionEvent.ACTION_UP)v.performClick();return true;}}
            catch(RuntimeException error){message(error.getMessage());}return true;
        });intro.addView(test);content.addView(intro);
        // Indices/ranges are from upstream parameterDescriptions_xt.json, never Virus mappings.
        addSliderRow(content,new String[]{"Wavetable","Wave 1 inicio","Wave 2 inicio"},new int[]{25,26,36},new int[]{127,63,63});
        addSliderRow(content,new String[]{"Filtro 1 cutoff","Resonancia","Filtro 2 cutoff"},new int[]{62,63,73},new int[]{127,127,127});
        addSliderRow(content,new String[]{"Amp ataque","Amp sustain","Amp release"},new int[]{119,121,122},new int[]{127,127,127});
        LinearLayout volume=row();TextView level=text("Volumen  "+gainDb+" dB",13,FG);volume.addView(level,new LinearLayout.LayoutParams(dp(135),-2));
        SeekBar slider=new SeekBar(this);slider.setMax(24);slider.setProgress(gainDb+24);volume.addView(slider,new LinearLayout.LayoutParams(0,dp(44),1));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int value,boolean user){if(!user)return;gainDb=value-24;level.setText("Volumen  "+gainDb+" dB");prefs.edit().putInt("gain",gainDb).apply();try{if(nativeOk)RuntimeBridge.setGainDb(gainDb);}catch(RuntimeException|LinkageError e){message(e.getMessage());}}
            public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}
        });content.addView(volume);content.addView(text("Los controles editan el sonido actual. Cambiar de patch recupera el sonido importado.",11,MUTED));
        revision=-1;
    }
    private static final class Slider { final int index;final SeekBar bar;final TextView value;boolean known;Slider(int i,SeekBar b,TextView v){index=i;bar=b;value=v;} }
    private void addSliderRow(LinearLayout content,String[] names,int[] indices,int[] max){
        LinearLayout row=row();
        for(int i=0;i<names.length;++i){final int index=indices[i];LinearLayout card=column();card.setPadding(dp(7),dp(5),dp(7),dp(3));card.setBackground(shape(PANEL,dp(4)));
            LinearLayout label=row();label.addView(text(names[i],12,FG),new LinearLayout.LayoutParams(0,-2,1));TextView value=text("—",12,ORANGE);label.addView(value);card.addView(label);
            SeekBar bar=new SeekBar(this);bar.setMax(max[i]);bar.setEnabled(false);card.addView(bar,new LinearLayout.LayoutParams(-1,dp(36)));Slider bind=new Slider(index,bar,value);sliders.add(bind);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(SeekBar b,int n,boolean user){if(!user||syncing||!ready||busy)return;try{RuntimeBridge.setParameter(index,n);value.setText(Integer.toString(n));message("Edición temporal del sonido actual");}catch(RuntimeException e){message(e.getMessage());}}
                public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}
            });LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(2),dp(2),dp(2),dp(2));row.addView(card,lp);
        }content.addView(row);
    }
    private void buildBanks(LinearLayout content){
        LinearLayout top=row();bankButton=button("IMPORTAR .SYX / .MID",ORANGE,v->pick(PICK_BANK));top.addView(bankButton);top.addView(text("  "+library.slots().size()+" / 256 sonidos",13,FG));content.addView(top);
        content.addView(text("Los bancos A y B contienen tus sonidos importados. Un patch de edit buffer se guarda en la posición seleccionada. Se conservan sus waves y wavetables.",12,MUTED));
        List<Integer> slots=library.slots();
        if(slots.isEmpty())content.addView(text("La biblioteca A/B está vacía. Podés probar el sonido actual de la ROM con INICIAR y NOTA C4. Importá tus bancos para recorrer otros sonidos.",16,FG));
        for(int slot:slots){final int b=slot/128,p=slot%128;String name=library.name(b,p);Button item=button(String.format(Locale.ROOT,"%s %03d     %s",b==0?"A":"B",p+1,name.isEmpty()?"(nombre vacío)":name),slot==bank*128+program?ORANGE:FG,v->{selectPatch(b,p);showPage(0);});item.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);content.addView(item,new LinearLayout.LayoutParams(-1,dp(42)));}
        content.addView(text("Esta versión importa sonidos Single de Microwave II/XT. Los mensajes Multi, Global, Mode y de otros sintetizadores se omiten y se informa su cantidad.",11,MUTED));
    }
    private void buildConfig(LinearLayout content){
        LinearLayout first=row();romButton=button("CARGAR ROM BIN / ZIP",ORANGE,v->pick(PICK_ROM));first.addView(romButton);first.addView(text("  "+romName,13,FG),new LinearLayout.LayoutParams(0,-2,1));content.addView(first);
        content.addView(text("ROM completa de 256 KiB, o ZIP con las dos mitades de 128 KiB. Se combinan automáticamente. El update MIDI solo no alcanza; si está dentro del ZIP se conserva el sistema de la ROM BIN.",12,MUTED));
        LinearLayout config=row();blockChoice=setting(config,"Buffer motor",new String[]{"256","512","1024"},"block",1);
        rateChoice=setting(config,"Salida",new String[]{"48000 Hz","Nativa 40000 Hz"},"rate",0);
        modeChoice=setting(config,"Modo",new String[]{"Conservador","Baja latencia + AUTO"},"mode",0);
        outputChoice=setting(config,"Buffer salida",new String[]{"512","768","1024","1536","2048","4096"},"output",2);content.addView(config);
        content.addView(text("Los cambios de audio se aplican al detener e iniciar. 512 / 48000 Hz / Conservador es el punto de partida. Motor a 100% y expansión de voces desactivada.",11,MUTED));
        LinearLayout connection=row();midiChoice=new Spinner(this);connection.addView(midiChoice,new LinearLayout.LayoutParams(0,dp(44),1));
        connection.addView(button("CONECTAR MIDI",BLUE,v->{if(ports.isEmpty()){message("No hay puertos MIDI disponibles");return;}try{midi.connect(ports.get(Math.max(0,midiChoice.getSelectedItemPosition())));}catch(RuntimeException e){message(e.getMessage());}}));
        connection.addView(button("DESCONECTAR",BLUE,v->midi.disconnect()));content.addView(connection);refreshPorts();
        diagnostics=text("",12,MUTED);content.addView(diagnostics);content.addView(text("La pantalla permanece encendida. Al salir de RiXenia se detiene el audio; ambas posiciones horizontales están habilitadas.",11,MUTED));
    }
    private Spinner setting(LinearLayout parent,String label,String[] choices,String key,int fallback){
        LinearLayout box=column();box.addView(text(label,12,FG));Spinner spinner=new Spinner(this);spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices));spinner.setSelection(bounded(prefs.getInt(key,fallback),choices.length));box.addView(spinner,new LinearLayout.LayoutParams(-1,dp(42)));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> a,View v,int p,long id){prefs.edit().putInt(key,p).apply();}public void onNothingSelected(AdapterView<?> a){}});parent.addView(box,new LinearLayout.LayoutParams(0,-2,1));return spinner;
    }
    private void refreshPorts(){
        if(midiChoice==null||midi==null)return;ports=midi.choices();List<String> names=new ArrayList<>();for(MidiInput.Choice p:ports)names.add(p.toString());if(names.isEmpty())names.add("Sin puertos MIDI — conectá el teclado USB");midiChoice.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
    }
    private void refreshMeters(){
        try{
            long now=SystemClock.elapsedRealtime(),cpu=android.os.Process.getElapsedCpuTime();double appCpu=wallBefore>0&&now>wallBefore?100.0*(cpu-cpuBefore)/(now-wallBefore):0;wallBefore=now;cpuBefore=cpu;
            RealtimeAudio.Session session=audio.session();
            if(session!=measured){measured=session;renderWindow.reset();}
            if(session!=null&&audio.isRunning()){
                long[] totals=session.meter.snapshot();double[] window=renderWindow.sample(System.nanoTime(),totals,session.deviceSampleRate);
                stats.setText(String.format(Locale.ROOT,"Render %.1f%%  ·  CPU app %.1f%%  ·  OV %d (%.1f/s)  ·  underruns %d  ·  %d → %d Hz",window[0],appCpu,totals[4],window[1],session.underruns,session.deviceSampleRate,session.sampleRate));
                if(diagnostics!=null){RealtimeAudio.OutputState s=session.output;diagnostics.setText(session.configuration+"\nSalida: "+s.effectiveFrames+" frames / capacidad "+s.capacityFrames+" · "+RealtimeAudio.performanceName(s.performanceMode)+"\n"+s.route+(session.monitorNote.isEmpty()?"":"\n"+session.monitorNote));}
            }else stats.setText(String.format(Locale.ROOT,"%s  ·  CPU app %.1f%%",busy?"Preparando motor…":"Motor detenido",appCpu));
            if(session!=null&&!session.error.isEmpty()&&!session.error.equals(lastError)){lastError=session.error;message(session.error);midiGate=false;}
            if(ready&&!busy){int[] state=RuntimeBridge.panel();if(state!=null&&state.length==259&&state[0]!=revision){revision=state[0];if(state[1]>=0&&state[1]<2&&state[2]>=0&&state[2]<128){bank=state[1];program=state[2];prefs.edit().putInt("bank",bank).putInt("program",program).apply();}
                    currentKnown=true;for(int i=0;i<256;++i)if(state[3+i]<0){currentKnown=false;break;}
                    StringBuilder n=new StringBuilder();if(currentKnown)for(int i=240;i<256;++i){int c=state[3+i];if(c>=32&&c<127)n.append((char)c);else n.append(' ');}liveName=n.toString().trim();
                    syncing=true;for(Slider s:sliders){int value=state[3+s.index];s.known=currentKnown&&value>=0;s.bar.setEnabled(s.known&&!busy);if(s.known&&!s.bar.isPressed()){s.bar.setProgress(value);s.value.setText(Integer.toString(value));}else if(!s.known)s.value.setText("—");}syncing=false;refreshDisplay();}}
            if(midi!=null){long[] telemetry=midi.telemetry.snapshot();footer.setText(midi.status+" · msgs "+telemetry[0]+" · teclas "+telemetry[3]+" · pedal "+(telemetry[4]==0?"OFF":"ON"));}controls();
        }catch(RuntimeException|LinkageError e){message(e.getMessage());}
    }
    @Override protected void onResume(){super.onResume();visible=true;cpuBefore=android.os.Process.getElapsedCpuTime();wallBefore=SystemClock.elapsedRealtime();ui.removeCallbacks(tick);ui.post(tick);}
    @Override protected void onStop(){
        visible=false;midiGate=false;ui.removeCallbacks(tick);super.onStop();
        ENGINE.execute(()->{try{stopWorker();if(ready&&nativeOk)RuntimeBridge.panic();}catch(Exception|LinkageError e){post(()->message(e.getMessage()));}});
    }
    @Override protected void onDestroy(){
        destroyed=true;visible=false;midiGate=false;ui.removeCallbacksAndMessages(null);if(midi!=null)midi.close();
        ENGINE.execute(()->{try{stopWorker();if(nativeOk&&!engineUnsafe)NativeBridge.nativeRelease();ready=false;}catch(Exception|LinkageError ignored){engineUnsafe=true;}});super.onDestroy();
    }
    private static int bounded(int n,int size){return Math.max(0,Math.min(size-1,n));}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String value,int sp,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(sp);v.setTextColor(color);return v;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private Button button(String label,int color,View.OnClickListener listener){Button b=new Button(this);b.setText(label);b.setTextSize(11);b.setTextColor(color);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(36));b.setMinimumHeight(dp(36));b.setPadding(dp(8),0,dp(8),0);b.setAllCaps(false);if(listener!=null)b.setOnClickListener(listener);return b;}
    private void addEngineButton(LinearLayout row,String label,View.OnClickListener listener){Button b=button(label,BLUE,listener);needsEngine.add(b);row.addView(b,new LinearLayout.LayoutParams(dp(74),dp(60)));}
}
