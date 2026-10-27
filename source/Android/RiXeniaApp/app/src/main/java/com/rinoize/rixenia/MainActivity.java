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
    private static final int BG=SynthUi.BG,PANEL=SynthUi.PANEL,FG=SynthUi.TEXT,MUTED=SynthUi.MUTED,ORANGE=SynthUi.ORANGE,BLUE=SynthUi.CYAN;
    private static final int[] BLOCKS={256,512,1024},RATES={48000,0},OUTPUTS={512,768,1024,1536,2048,2304,3072,4096};
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final RealtimeAudio audio=new RealtimeAudio();
    private final MeterWindow renderWindow=new MeterWindow();
    private final List<Button> needsEngine=new ArrayList<>();
    private final List<Button> navTabs=new ArrayList<>();
    private XeniaEditor editor;
    private final int[] editorValues=new int[256];
    private volatile boolean ready,destroyed,visible,midiGate;
    private boolean busy,nativeOk,syncing,currentKnown;
    private int page,bank,program,revision=-1,gainDb=-6;
    private int patchPhase,lastPatchMs=-1,queuedMidi,queuedSysex;
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

    private final Runnable patchTick=new Runnable(){public void run(){
        if(!visible||destroyed)return;
        refreshPatchState();
        if(patchPhase!=0)ui.postDelayed(this,patchPhase==3?500:100);
    }};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        java.util.Arrays.fill(editorValues,-1);
        prefs=getSharedPreferences("rixenia-v1",MODE_PRIVATE);
        // Preserve the old 4096-frame choice: new entries must not reinterpret index 5.
        if(!prefs.getBoolean("output-options-012",false)) {
            int old=prefs.getInt("output",2);
            prefs.edit().putInt("output",old==5?7:old).putBoolean("output-options-012",true).apply();
        }
        bank=Math.max(0,Math.min(1,prefs.getInt("bank",0)));program=Math.max(0,Math.min(127,prefs.getInt("program",0)));
        gainDb=Math.max(-24,Math.min(0,prefs.getInt("gain",-6)));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout root=column();root.setBackgroundColor(BG);root.setPadding(dp(10),dp(6),dp(10),dp(5));
        LinearLayout header=row();TextView title=text("RiXenia",22,ORANGE);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);header.addView(title);
        TextView sub=text("  Microwave II / XT  ·  0.2.0 α",12,MUTED);header.addView(sub,new LinearLayout.LayoutParams(0,-2,1));
        play=button("▶ INICIAR",ORANGE,v->startAudio());stop=button("■ DETENER",BLUE,v->stopAudio());
        addTransport(header,play);addTransport(header,stop);addTransport(header,button("PANIC",0xffff7787,v->panic()));root.addView(header,new LinearLayout.LayoutParams(-1,dp(40)));
        stats=text("Motor detenido",11,MUTED);root.addView(stats);
        LinearLayout patchBar=row();patchBar.setPadding(0,dp(6),0,dp(6));
        addEngineButton(patchBar,"BANK −",v->stepBank());
        addEngineButton(patchBar,"PATCH −",v->stepPatch(-1));
        display=text("Cargar ROM\nImportar banco de sonidos",19,0xffd7edff);display.setTypeface(Typeface.MONOSPACE);display.setGravity(Gravity.CENTER);display.setMinHeight(dp(54));display.setMaxLines(2);display.setBackground(new SynthUi.LcdBackground(this));display.setPadding(dp(5),dp(3),dp(5),dp(3));
        display.setOnClickListener(v->showPage(1));patchBar.addView(display,new LinearLayout.LayoutParams(0,dp(57),1));
        addEngineButton(patchBar,"PATCH +",v->stepPatch(1));addEngineButton(patchBar,"BANK +",v->stepBank());root.addView(patchBar);
        LinearLayout tabs=row();for(int i=0;i<3;++i){final int selected=i;Button b=button(new String[]{"TOCAR / EDITAR","BANCOS","CONFIGURACIÓN"}[i],BLUE,v->showPage(selected));navTabs.add(b);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(38),1);lp.setMargins(dp(2),0,dp(2),0);tabs.addView(b,lp);}root.addView(tabs);
        body=new FrameLayout(this);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        status=text("Cargá tu ROM BIN/ZIP, pulsá INICIAR y mantené NOTA C4 para la primera prueba.",12,FG);status.setMaxLines(1);status.setEllipsize(android.text.TextUtils.TruncateAt.END);root.addView(status);
        footer=text("MIDI desconectado",11,MUTED);footer.setMaxLines(1);footer.setEllipsize(android.text.TextUtils.TruncateAt.END);root.addView(footer);setContentView(root);
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
            refreshPatchState();refreshDisplay();controls();
            message(library.has(bank,program)?"Cambio solicitado: esperando confirmación del firmware.":"Posición vacía elegida para importar; el motor conserva el sonido actual.");
            ui.removeCallbacks(patchTick);ui.post(patchTick);
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
                // Deduplicate the fixed address space before JNI.
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
        if(editor!=null)editor.setAvailable(available&&ready&&currentKnown&&patchPhase==0,available&&ready);
    }
    private void setDisplay(String text){if(!text.contentEquals(display.getText()))display.setText(text);}
    private void refreshDisplay(){
        if(patchPhase!=0){
            String label=!audio.isRunning()?"PENDIENTE · INICIAR":patchPhase==3?"SIN CONFIRMAR":"CARGANDO";
            setDisplay(String.format(Locale.ROOT,"%s%03d · %s\n%s",bank==0?"A":"B",program+1,label,library.name(bank,program)));
            return;
        }
        boolean imported=library.has(bank,program);
        String name=liveName;
        if(name.isEmpty())name=library.name(bank,program);
        if(name.isEmpty())name=ready?(currentKnown?"(nombre vacío)":"Iniciá para consultar"):"Cargar ROM BIN / ZIP";
        String slot=String.format(Locale.ROOT,"%s%03d",bank==0?"A":"B",program+1);
        String heading=imported?slot+"  ·  SINGLE":currentKnown?"ACTUAL · destino "+slot:"DESTINO "+slot;
        setDisplay(heading+"\n"+name);
    }
    private void showPage(int selected){
        page=selected;romButton=bankButton=null;diagnostics=null;body.removeAllViews();
        for(int i=0;i<navTabs.size();i++)SynthUi.styleButton(navTabs.get(i),BLUE,i==page);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        if(page==0){
            if(editor==null)editor=new XeniaEditor(this,new XeniaEditor.Listener(){
                public boolean parameter(int index,int value){
                    if(!ready||busy||patchPhase!=0||!currentKnown)return false;
                    try{RuntimeBridge.setParameter(index,value);editorValues[index]=value;message("Edición temporal · "+XtParameters.get(index).name+" = "+value);return true;}
                    catch(RuntimeException|LinkageError e){message(e.getMessage());return false;}
                }
                public void gain(int db){
                    if(!nativeOk||busy)return;
                    try{RuntimeBridge.setGainDb(db);gainDb=db;prefs.edit().putInt("gain",gainDb).apply();}
                    catch(RuntimeException|LinkageError e){message(e.getMessage());}
                }
                public void testNote(boolean on){
                    if(!ready||!nativeOk)return;
                    try{if(on){if(!audio.isRunning()){message("Pulsá INICIAR para probar la nota C4.");return;}NativeBridge.nativeNoteOn(60,100);}
                        else NativeBridge.nativeNoteOff(60);}
                    catch(RuntimeException|LinkageError e){message(e.getMessage());}
                }
            });
            if(editor.getParent()!=null)((android.view.ViewGroup)editor.getParent()).removeView(editor);
            editor.bind(editorValues,gainDb);scroll.addView(editor,new ScrollView.LayoutParams(-1,-2));
        }else{
            LinearLayout content=column();content.setPadding(dp(6),dp(10),dp(6),dp(8));
            if(page==1)buildBanks(content);else buildConfig(content);
            scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        }
        body.addView(scroll,new FrameLayout.LayoutParams(-1,-1));refreshDisplay();controls();
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
        outputChoice=setting(config,"Buffer salida",new String[]{"512","768","1024","1536","2048","2304","3072","4096"},"output",2);content.addView(config);
        content.addView(text("Los cambios de audio se aplican al detener e iniciar. 512 / 48000 Hz / Conservador es el punto de partida. Motor a 100% y expansión de voces desactivada.",11,MUTED));
        LinearLayout connection=row();midiChoice=new Spinner(this);connection.addView(midiChoice,new LinearLayout.LayoutParams(0,dp(44),1));
        connection.addView(button("CONECTAR MIDI",BLUE,v->{if(ports.isEmpty()){message("No hay puertos MIDI disponibles");return;}try{midi.connect(ports.get(Math.max(0,midiChoice.getSelectedItemPosition())));}catch(RuntimeException e){message(e.getMessage());}}));
        connection.addView(button("DESCONECTAR",BLUE,v->midi.disconnect()));content.addView(connection);refreshPorts();
        content.addView(button("COPIAR DIAGNÓSTICO",BLUE,v->{
            android.content.ClipboardManager clip=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(clip!=null) {
                clip.setPrimaryClip(android.content.ClipData.newPlainText("RiXenia diagnóstico",
                    "RiXenia 0.2.0 · "+android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL+
                    " · Android "+android.os.Build.VERSION.RELEASE+"\n"+display.getText()+"\n"+
                    stats.getText()+"\n"+footer.getText()+"\n"+audio.diagnosticReport()));
                message("Diagnóstico copiado. Podés pegarlo en el chat; no contiene ROM ni bancos.");
            }
        }));
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
                stats.setText(String.format(Locale.ROOT,"Render %.1f%%  ·  CPU app %.1f%%  ·  OV %d · %.1f OV/s  ·  underruns %d  ·  %d → %d Hz",window[0],appCpu,totals[4],window[1],session.underruns,session.deviceSampleRate,session.sampleRate));
                if(diagnostics!=null){RealtimeAudio.OutputState s=session.output;diagnostics.setText(session.configuration+"\nSalida: "+s.effectiveFrames+" frames / capacidad "+s.capacityFrames+" · "+RealtimeAudio.performanceName(s.performanceMode)+"\n"+s.route+(session.monitorNote.isEmpty()?"":"\n"+session.monitorNote)+"\n"+audio.healthSummary());}
            }else stats.setText(String.format(Locale.ROOT,"%s  ·  CPU app %.1f%%",busy?"Preparando motor…":"Motor detenido",appCpu));
            if(session!=null&&!session.error.isEmpty()&&!session.error.equals(lastError)){lastError=session.error;message(session.error);midiGate=false;}
            refreshPatchState();
            if(patchPhase!=0){ui.removeCallbacks(patchTick);ui.postDelayed(patchTick,100);}
            if(midi!=null){long[] telemetry=midi.telemetry.snapshot();footer.setText(midi.status+" · msgs "+telemetry[0]+" · teclas "+telemetry[3]+" · pedal "+(telemetry[4]==0?"OFF":"ON"));}controls();
        }catch(RuntimeException|LinkageError e){message(e.getMessage());}
    }
    private void refreshPatchState(){
        if(!ready||busy||!nativeOk)return;
        try{
            int[] state=RuntimeBridge.panel();if(state==null||state.length!=264)return;
            int previous=patchPhase;patchPhase=state[259];lastPatchMs=state[260];queuedMidi=state[262];queuedSysex=state[263];
            if(state[0]!=revision||previous!=patchPhase){
                revision=state[0];
                if(state[1]>=0&&state[1]<2&&state[2]>=0&&state[2]<128){
                    if(bank!=state[1]||program!=state[2]){bank=state[1];program=state[2];prefs.edit().putInt("bank",bank).putInt("program",program).apply();}
                }
                currentKnown=patchPhase==0;
                for(int i=0;i<256;++i)if(state[3+i]<0){currentKnown=false;break;}
                StringBuilder n=new StringBuilder();
                if(currentKnown)for(int i=240;i<256;++i){int c=state[3+i];n.append(c>=32&&c<127?(char)c:' ');}
                liveName=n.toString().trim();
                System.arraycopy(state,3,editorValues,0,256);
                if(editor!=null)editor.bind(editorValues,gainDb);
            }
            if(patchPhase!=0&&audio.isRunning())message(String.format(Locale.ROOT,
                "%s · %.1f s · cola MIDI %d / SysEx %d",patchPhase==3?"El firmware aún no confirmó el sonido":"Aplicando patch",state[261]/1000.0,queuedMidi,queuedSysex));
            else if(previous!=0&&patchPhase==0&&lastPatchMs>=0)message("Patch confirmado por el firmware en "+lastPatchMs+" ms (no es latencia de audio).");
            refreshDisplay();controls();
        }catch(RuntimeException|LinkageError e){message(e.getMessage());}
    }
    @Override protected void onResume(){super.onResume();visible=true;cpuBefore=android.os.Process.getElapsedCpuTime();wallBefore=SystemClock.elapsedRealtime();ui.removeCallbacks(tick);ui.post(tick);}
    @Override protected void onStop(){
        visible=false;midiGate=false;ui.removeCallbacks(tick);ui.removeCallbacks(patchTick);super.onStop();
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
    private Button button(String label,int color,View.OnClickListener listener){return SynthUi.button(this,label,color,listener);}
    private void addTransport(LinearLayout row,Button button){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(34));p.setMargins(dp(3),0,0,0);row.addView(button,p);}
    private void addEngineButton(LinearLayout row,String label,View.OnClickListener listener){
        Button b=button(label,BLUE,listener);needsEngine.add(b);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(69),dp(54));p.setMargins(dp(2),0,dp(2),0);row.addView(b,p);
    }
}
