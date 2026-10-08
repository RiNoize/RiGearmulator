package com.rinoize.rigear;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Native instrument UI. Engine/SRC/AudioTrack retain the 0.9 implementation. */
public final class StudioActivity extends Activity {
    private static final ExecutorService ENGINE = Executors.newSingleThreadExecutor();
    private static final ExecutorService STORAGE = Executors.newSingleThreadExecutor();
    private static final int PICK_ROM = 110, PICK_SOUND = 111, EXPORT = 112;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final RealtimeAudio audio = new RealtimeAudio();
    private final MeterWindow window = new MeterWindow();
    private StudioUi skin;
    private MidiInput midi;
    private ThermalMonitor thermal;
    private SoundLibrary library;
    private SoundCodec.Snapshot state;
    private volatile boolean destroyed, visible, ready, midiGate;
    private boolean busy, syncing, nativeOk, onlyFavorites, dirty, comparing;
    private int selectedPart = 64, page, editGroup, generation, workToken, lastSyncedGeneration = -1;
    private long lastSyncedMidi = -1, nextSyncAt;
    private long changedAt, cpuBefore, wallBefore, startedAt, firstUnderrun = -1, nextTrace;
    private int underrunBase;
    private RealtimeAudio.Session measured;
    private final ArrayList<String> trace = new ArrayList<>();
    private final String[] partIds = new String[17];
    private byte[] compareReturn, exportBytes;
    private int compareTarget = 64, bankFilter = -1;
    private String firmware = "Sin ROM", selectedId, sourceFilter = "Todos", setFilter = "", query = "", category = "Todas";
    private TextView topStats, patchName, status, footer, thermalText, audioDetails, libraryDetails, libraryCount;
    private Button start, stop, favorite, single, multi, favoriteFilter;
    private FrameLayout body;
    private LinearLayout multiRows;
    private Spinner partSelector, buffer, rate, outputMode, outputSize, extra, clock, gain, midiSpinner;
    private final List<Button> tabs = new ArrayList<>(), arpButtons = new ArrayList<>();
    private Button latch;
    private final List<DialBinding> dials = new ArrayList<>();
    private final List<Param> parameters = new ArrayList<>();
    private ListView soundList;
    private List<SoundLibrary.Entry> shown = new ArrayList<>();
    private SoundAdapter soundAdapter;
    private SharedPreferences preferences;
    private static final String[] PAGES = {"INICIO", "EDIT", "MULTI", "FX", "BIBLIOTECA", "CONFIG"};
    private static final String[] ARP = {"OFF", "UP", "DOWN", "UP/DOWN", "AS PLAYED", "RANDOM", "CHORD"};
    private static final int[] BLOCKS = {256, 512, 1024}, RATES = {0, 48000}, OUTPUTS = {256,512,768,1024,1536,2048,4096},
        EXTRAS = {0,128,256,512,1024,2048,4096,8192}, CLOCKS = {50,75,100,125,150,200}, GAINS = {0,-6,-12,-18,-24};
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!visible || destroyed) return;
            try { meters(); autoSync(); }
            catch (RuntimeException | LinkageError e) { message(e.getMessage()); }
            ui.postDelayed(this, 1000);
        }
    };
    private interface Work { void run() throws Exception; }
    private static final class Param {
        String name, group; int page, index, min, max; String[] values;
        String format(int value) {
            if (values != null && value >= 0 && value < values.length) return values[value];
            return Integer.toString(value);
        }
    }
    private static final class DialBinding {
        StudioUi.Dial dial; int page, index;
        DialBinding(StudioUi.Dial dial, int page, int index) { this.dial = dial; this.page = page; this.index = index; }
    }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        preferences = getSharedPreferences("studio-v1", MODE_PRIVATE);
        skin = new StudioUi(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(StudioUi.BG); getWindow().setNavigationBarColor(StudioUi.BG);
        // Native inset-aware full screen; navigation can be revealed with a system gesture.
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout root = skin.column(); root.setBackgroundColor(StudioUi.BG); root.setPadding(skin.dp(6),skin.dp(4),skin.dp(6),skin.dp(4));
        LinearLayout header = skin.row();
        TextView brand = skin.text("RiGear 0.11", 21, StudioUi.TEXT); brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD); skin.space(header, brand);
        for (int i=0; i<PAGES.length; ++i) {
            final int p=i; Button b=skin.button(PAGES[i], StudioUi.BLUE, v->showPage(p)); tabs.add(b);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,skin.dp(40),1); lp.setMargins(skin.dp(2),0,skin.dp(2),0); header.addView(b,lp);
        }
        root.addView(header);
        topStats=skin.text("Inicializando biblioteca y motor…", 11, StudioUi.MUTED); root.addView(topStats);
        LinearLayout patchBar=skin.row(); patchBar.setPadding(0,skin.dp(4),0,skin.dp(4));
        single=skin.button("SINGLE",StudioUi.BLUE,v->setMode(false)); multi=skin.button("MULTI",StudioUi.BLUE,v->setMode(true));
        skin.space(patchBar,single); skin.space(patchBar,multi);
        partSelector=spinner(partLabels(),0,null); patchBar.addView(partSelector,new LinearLayout.LayoutParams(skin.dp(90),skin.dp(40)));
        partSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> a,View v,int p,long id){ if(selectedPart!=64 && selectedPart!=p){selectedPart=p; refreshPatch(); rebuildCurrent();} }
            public void onNothingSelected(AdapterView<?> a){}
        });
        skin.space(patchBar,skin.button("BANK -",StudioUi.BLUE,v->stepBank(-1)));
        skin.space(patchBar,skin.button("PATCH -",StudioUi.BLUE,v->stepPatch(-1)));
        patchName=skin.text("Cargar ROM en CONFIG",20,StudioUi.BLUE); patchName.setTypeface(Typeface.DEFAULT,Typeface.BOLD); patchName.setMaxLines(2);
        patchName.setOnClickListener(v->showPage(4)); patchBar.addView(patchName,new LinearLayout.LayoutParams(0,-2,1));
        skin.space(patchBar,skin.button("PATCH +",StudioUi.BLUE,v->stepPatch(1)));
        skin.space(patchBar,skin.button("BANK +",StudioUi.BLUE,v->stepBank(1)));
        favorite=skin.button("☆",StudioUi.ORANGE,v->toggleCurrentFavorite()); skin.space(patchBar,favorite);
        skin.space(patchBar,skin.button("Guardar",StudioUi.BLUE,v->saveCurrent()));
        skin.space(patchBar,skin.button("PANIC",StudioUi.RED,v->panic())); root.addView(patchBar);
        body=new FrameLayout(this); root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        status=skin.text("",11,StudioUi.MUTED); status.setMaxLines(2); root.addView(status);
        footer=skin.text("MIDI desconectado · pantalla encendida mientras RiGear esté visible",11,StudioUi.MUTED); footer.setMaxLines(2); root.addView(footer);
        setContentView(root);
        try { nativeOk=NativeBridge.nativeSelfTest()==1; } catch(LinkageError|RuntimeException e){message("JNI: "+e.getMessage());}
        thermal=new ThermalMonitor(this);
        midi=new MidiInput(this,ui,()->midiGate && audio.isRunning() && !destroyed,()->{if(page==5)refreshMidiPorts();});
        try { readParameters(); } catch(Exception e){message("Catálogo de edición: "+e.getMessage());}
        showPage(0);
        STORAGE.execute(()->{
            try {
                SoundLibrary loaded=new SoundLibrary(new File(getFilesDir(),"sounds-v1.rigearlib"));
                RomImporter.Bundle rom=StudioFiles.retainedRom(this);
                ui.post(()->{if(!destroyed){library=loaded; if(page==4)rebuildCurrent(); if(rom!=null)bootRom(rom,false); else message("Biblioteca lista. Importá tu ROM desde CONFIG.");}});
            }catch(Exception e){ui.post(()->{if(!destroyed)message("No se pudo abrir almacenamiento: "+e.getMessage()+". Los archivos no se sobrescribieron.");});}
        });
    }
    private String[] partLabels(){String[] a=new String[16];for(int i=0;i<16;++i)a[i]="Parte "+(i+1);return a;}
    private int slot(){return selectedPart==64?16:selectedPart;}
    private byte[] current(){return state==null?null:state.single(selectedPart);}
    private int value(int pg,int index){
        if(pg==0x71 && index==16 && selectedPart!=64 && state!=null && state.multi!=null)return state.multi[9+15]&127;
        byte[] p=current(); return p==null?-1:p[9+(pg-0x70)*128+index]&127;
    }
    private void message(String text){status.setText(text==null?"Operación fallida":text);}
    private void work(String label,Work work){
        if(busy || destroyed || !nativeOk)return;
        busy=true; final int token=++workToken; message(label); controls();
        ENGINE.execute(()->{try{if(!destroyed)work.run();}catch(Exception|LinkageError e){ui.post(()->{if(!destroyed)message(e.toString());});}
            finally{ui.post(()->{if(!destroyed && token==workToken){busy=false;controls();}});}});
    }
    private void storage(String label,Work work){
        if(library==null){message("Biblioteca aún no disponible");return;}
        message(label); STORAGE.execute(()->{try{work.run();ui.post(()->{if(!destroyed){refreshPatch();if(page==4)refreshLibrary();}});}catch(Exception e){ui.post(()->{if(!destroyed)message(e.getMessage());});}});
    }
    private void changed(){dirty=true; comparing=false; generation++; changedAt=SystemClock.elapsedRealtime(); refreshPatch();}
    private int set(int pg,int index,int n){
        if(!ready || busy || comparing || current()==null)return Math.max(0,value(pg,index));
        try{
            int target=(pg==0x71 && index==16 && selectedPart!=64)?0:selectedPart;
            StudioBridge.parameters(target,new int[]{pg,index,n});
            current()[9+(pg-0x70)*128+index]=(byte)n; SoundCodec.repair(current());
            if(pg==0x71 && index==16 && state.multi!=null){state.multi[9+15]=(byte)n;SoundCodec.repair(state.multi);}
            changed(); return n;
        }catch(RuntimeException e){message(e.getMessage());return Math.max(0,value(pg,index));}
    }
    private int linked(int first,int second,int requested){
        if(!ready || busy || comparing || current()==null)return Math.max(0,value(0x70,first));
        // If the patch already uses firmware Cutoff Link, keep its native offset untouched.
        if(first==40 && value(0x71,32)!=0)return set(0x70,40,requested);
        int[] pair=SoundCodec.linked(value(0x70,first),value(0x70,second),requested);
        try{StudioBridge.parameters(selectedPart,new int[]{0x70,first,pair[0],0x70,second,pair[1]});
            current()[9+first]=(byte)pair[0];current()[9+second]=(byte)pair[1];SoundCodec.repair(current());changed();return pair[0];
        }catch(RuntimeException e){message(e.getMessage());return value(0x70,first);}
    }
    private StudioUi.Dial dial(String name,int pg,int index,int min,int max,int accent,StudioUi.Format format){
        StudioUi.Dial d=skin.new Dial(name,min,max,accent,n->set(pg,index,n),format);
        dials.add(new DialBinding(d,pg,index));d.show(value(pg,index));d.setEnabled(ready&&!busy&&current()!=null);return d;
    }
    private void showPage(int p){page=p;rebuildCurrent();}
    private void rebuildCurrent(){
        if(body==null)return; body.removeAllViews(); dials.clear();arpButtons.clear();latch=null;start=stop=null;audioDetails=thermalText=null;multiRows=null;
        for(int i=0;i<tabs.size();++i)tabs.get(i).setSelected(i==page);
        View view;
        switch(page){case 1:view=editPage(false);break;case 2:view=multiPage();break;case 3:view=editPage(true);break;case 4:view=libraryPage();break;case 5:view=configPage();break;default:view=home();}
        body.addView(view,new FrameLayout.LayoutParams(-1,-1));refreshPatch();controls();
    }
    private View home(){
        LinearLayout home=skin.column(), groups=skin.row();
        String[] titles={"AMP","FILTER · 1 + 2","COLOR / MOVIMIENTO"};int[] colors={StudioUi.ORANGE,StudioUi.BLUE,StudioUi.GREEN};
        for(int group=0;group<3;++group){
            LinearLayout card=skin.card(titles[group],colors[group]), line=skin.row();
            String[] names;int[] indices;
            if(group==0){names=new String[]{"VOLUMEN","AMP ATTACK","AMP RELEASE"};indices=new int[]{91,59,63};}
            else if(group==1){names=new String[]{"CUTOFF 1+2","RESO 1+2","ENV AMT 1+2"};indices=new int[]{40,42,44};}
            else{names=new String[]{"RING MOD","SUB LEVEL","PORTAMENTO"};indices=new int[]{38,34,5};}
            for(int i=0;i<3;++i){final int idx=indices[i];StudioUi.Dial d;
                if(group==1){d=skin.new Dial(names[i],0,127,colors[group],n->linked(idx,idx+1,n),Integer::toString);dials.add(new DialBinding(d,0x70,idx));d.show(value(0x70,idx));}
                else d=dial(names[i],0x70,idx,0,127,colors[group],Integer::toString);
                line.addView(d,new LinearLayout.LayoutParams(0,-1,1));
            }
            card.addView(line,new LinearLayout.LayoutParams(-1,0,1));skin.equal(groups,card);
        }
        home.addView(groups,new LinearLayout.LayoutParams(-1,0,1.1f));
        LinearLayout arp=skin.card("ARPEGIADOR",StudioUi.PURPLE), content=skin.row(), left=skin.column(), modes=skin.row();
        for(int i=0;i<ARP.length;++i){final int mode=i;Button b=skin.button(ARP[i],StudioUi.PURPLE,v->{set(0x71,1,mode);refreshValues();});
            b.setTextSize(10);arpButtons.add(b);modes.addView(b,new LinearLayout.LayoutParams(0,skin.dp(44),1));}
        left.addView(modes);
        LinearLayout arpTools=skin.row();
        latch=skin.button("LATCH",StudioUi.GREEN,v->{set(0x71,4,value(0x71,4)==0?1:0);refreshValues();});skin.space(arpTools,latch);
        skin.space(arpTools,skin.button("Comparar",StudioUi.BLUE,v->compare()));
        Button homeFavorites=skin.button("★ Solo favoritos",StudioUi.ORANGE,v->{onlyFavorites=!onlyFavorites;v.setSelected(onlyFavorites);message(onlyFavorites?"PATCH ‹/› recorre solo favoritos":"PATCH ‹/› recorre la biblioteca Single");});
        homeFavorites.setSelected(onlyFavorites);skin.space(arpTools,homeFavorites);
        left.addView(arpTools);content.addView(left,new LinearLayout.LayoutParams(0,-2,4));
        StudioUi.Dial length=dial("NOTE LENGTH",0x71,5,0,127,StudioUi.PURPLE,n->String.format(Locale.ROOT,"%+d",n-64));
        StudioUi.Dial tempo=dial("GLOBAL TEMPO",0x71,16,0,127,StudioUi.PURPLE,n->(n+63)+" BPM");
        content.addView(length,new LinearLayout.LayoutParams(0,-1,1));content.addView(tempo,new LinearLayout.LayoutParams(0,-1,1));
        arp.addView(content,new LinearLayout.LayoutParams(-1,0,1));home.addView(arp,new LinearLayout.LayoutParams(-1,0,.85f));
        refreshValues();return home;
    }
    private void readParameters()throws Exception{
        String data=new String(StudioFiles.limited(getAssets().open("studio-parameters.json"),2*1024*1024),StandardCharsets.UTF_8);
        JSONArray list=new JSONArray(data);
        for(int i=0;i<list.length();++i){JSONObject o=list.getJSONObject(i);Param p=new Param();p.name=o.getString("name");p.page=o.getInt("page");p.index=o.getInt("index");p.min=o.getInt("min");p.max=o.getInt("max");p.group=o.getString("group");
            JSONArray names=o.optJSONArray("values");if(names!=null){p.values=new String[names.length()];for(int j=0;j<names.length();++j)p.values[j]=names.getString(j);}parameters.add(p);}
    }
    private View editPage(boolean fx){
        LinearLayout result=skin.column();String[] groups=fx?new String[]{"FX"}:new String[]{"Osciladores","Filtros","Envolventes","LFO / Mod","Arpegiador","Otros"};
        int selected=fx?0:Math.min(editGroup,groups.length-1);
        Spinner choice=spinner(groups,selected,null); result.addView(choice);
        ScrollView scroll=new ScrollView(this);LinearLayout rows=skin.column();scroll.addView(rows);result.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Runnable populate=()->{
            rows.removeAllViews();dials.clear();String group=groups[choice.getSelectedItemPosition()];LinearLayout row=null;int n=0;
            for(Param p:parameters){if(!p.group.equals(group))continue;if(n++%4==0){row=skin.row();rows.addView(row,new LinearLayout.LayoutParams(-1,skin.dp(120)));}
                StudioUi.Dial d=dial(p.name.toUpperCase(Locale.ROOT),p.page,p.index,p.min,p.max,fx?StudioUi.PURPLE:StudioUi.BLUE,p::format);row.addView(d,new LinearLayout.LayoutParams(0,-1,1));}
            if(row!=null)while(n++%4!=0)row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
            rows.addView(skin.text("Parámetros definidos por Osirus. Las funciones disponibles dependen de la ROM A/B/C. No se altera el sonido al mostrar el panel.",11,StudioUi.MUTED));controls();
        };
        choice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>a,View v,int p,long id){if(!fx)editGroup=p;populate.run();}public void onNothingSelected(AdapterView<?>a){}});populate.run();return result;
    }
    private View multiPage(){
        LinearLayout root=skin.column(), tools=skin.row();skin.space(tools,skin.button("Activar MULTI",StudioUi.BLUE,v->setMode(true)));
        skin.space(tools,skin.button("Guardar Multi completo",StudioUi.BLUE,v->saveMulti()));
        tools.addView(skin.text("16 partes · polifonía compartida · MIDI según canal de cada parte",11,StudioUi.MUTED));root.addView(tools);
        ScrollView scroll=new ScrollView(this);multiRows=skin.column();scroll.addView(multiRows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));fillMulti();return root;
    }
    private void fillMulti(){
        if(multiRows==null)return;multiRows.removeAllViews();
        if(state==null||state.multi==null){multiRows.addView(skin.text("Cargá una ROM y pulsá START para consultar las partes reales.",14,StudioUi.MUTED));return;}
        for(int i=0;i<16;++i){final int part=i;byte[] s=state.singles[i];LinearLayout row=skin.row();row.setPadding(skin.dp(3),skin.dp(3),skin.dp(3),skin.dp(3));row.setBackground(skin.box(StudioUi.LINE,false));
            Button choose=skin.button("P"+(i+1),StudioUi.BLUE,v->{if(selectedPart==64){message("Activá MULTI para editar una parte");return;}selectedPart=part;partSelector.setSelection(part);showPage(0);});choose.setSelected(selectedPart==i);skin.space(row,choose);
            String name=s==null?"Sin datos":SoundCodec.name(s);TextView title=skin.text(name,14,StudioUi.TEXT);title.setOnClickListener(v->{if(selectedPart!=64){selectedPart=part;showPage(4);}});row.addView(title,new LinearLayout.LayoutParams(0,-2,1.4f));
            int channel=state.multi[9+64+i]&127;Button ch=skin.button("CH "+(channel+1),StudioUi.BLUE,v->number("Canal MIDI parte "+(part+1),1,16,(state.multi[9+64+part]&127)+1,n->multiValue(part,34,n-1)));skin.space(row,ch);
            boolean on=(state.multi[9+240+i]&1)!=0;Button enable=skin.button(on?"ON":"OFF",StudioUi.GREEN,v->{multiValue(part,72,(state.multi[9+240+part]&1)==0?1:0);fillMulti();});enable.setSelected(on);skin.space(row,enable);
            Button level=skin.button("VOL "+(state.multi[9+144+i]&127),StudioUi.ORANGE,v->number("Nivel parte "+(part+1),0,127,state.multi[9+144+part]&127,n->{multiValue(part,39,n);fillMulti();}));skin.space(row,level);
            int pan=s==null?64:s[9+10]&127;Button panorama=skin.button("PAN "+(pan-64),StudioUi.BLUE,v->number("Pan parte "+(part+1),0,127,pan,n->{try{StudioBridge.parameters(part,new int[]{0x70,10,n});if(state.singles[part]!=null){state.singles[part][19]=(byte)n;SoundCodec.repair(state.singles[part]);}changed();fillMulti();}catch(RuntimeException e){message(e.getMessage());}}));skin.space(row,panorama);
            skin.space(row,skin.button("MAIN L/R",StudioUi.BLUE,v->multiValue(part,41,1)));
            for(int k=0;k<row.getChildCount();++k)row.getChildAt(k).setEnabled(ready&&!busy&&selectedPart!=64);
            multiRows.addView(row,new LinearLayout.LayoutParams(-1,skin.dp(52)));
        }
    }
    private void multiValue(int part,int index,int n){
        if(!ready||busy||selectedPart==64||state==null||state.multi==null)return;
        try{StudioBridge.parameters(part,new int[]{0x72,index,n});
            if(index==72){int old=state.multi[9+240+part]&127;state.multi[9+240+part]=(byte)(n==0?old&~1:old|1);}
            else if(index>=34&&index<=41)state.multi[9+64+(index-34)*16+part]=(byte)n;
            SoundCodec.repair(state.multi);changed();fillMulti();
        }catch(RuntimeException e){message(e.getMessage());}
    }
    private View libraryPage(){
        LinearLayout root=skin.row(),nav=skin.card("BIBLIOTECA",StudioUi.BLUE),middle=skin.column(),detail=skin.card("SONIDO",StudioUi.BLUE);
        String[] filters={"Todos","Factory","Usuario","Importados","★ Favoritos","Recientes","Singles","Multis"};
        for(String filter:filters){Button b=skin.button(filter,StudioUi.BLUE,v->{sourceFilter=filter;bankFilter=-1;setFilter="";refreshLibrary();});nav.addView(b,new LinearLayout.LayoutParams(-1,skin.dp(36)));}
        nav.addView(skin.button("Setlists…",StudioUi.BLUE,v->chooseSet()),new LinearLayout.LayoutParams(-1,skin.dp(36)));
        TextView bankTitle=skin.text("BANCOS FACTORY",11,StudioUi.MUTED);bankTitle.setPadding(0,skin.dp(7),0,skin.dp(2));nav.addView(bankTitle);
        for(int b=0;b<8;++b){final int bank=b;Button bankButton=skin.button("Bank "+(char)('A'+b),StudioUi.BLUE,v->{sourceFilter="Factory";bankFilter=bank;setFilter="";refreshLibrary();});nav.addView(bankButton,new LinearLayout.LayoutParams(-1,skin.dp(35)));}
        EditText search=new EditText(this);search.setSingleLine();search.setTextColor(StudioUi.TEXT);search.setHintTextColor(StudioUi.MUTED);search.setHint("Buscar nombre, categoría o banco…");search.setText(query);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){query=s.toString();refreshLibrary();}public void afterTextChanged(Editable e){}});
        middle.addView(search,new LinearLayout.LayoutParams(-1,skin.dp(44)));
        LinearLayout filtersRow=skin.row();favoriteFilter=skin.button("★ Solo favoritos",StudioUi.ORANGE,v->{onlyFavorites=!onlyFavorites;refreshLibrary();});skin.space(filtersRow,favoriteFilter);
        String[] cats=new String[SoundCodec.CATEGORIES.length+1];cats[0]="Todas";System.arraycopy(SoundCodec.CATEGORIES,0,cats,1,SoundCodec.CATEGORIES.length);
        Spinner cat=spinner(cats,Math.max(0,Arrays.asList(cats).indexOf(category)),p->{category=cats[p];refreshLibrary();});filtersRow.addView(cat,new LinearLayout.LayoutParams(0,skin.dp(40),1));middle.addView(filtersRow);
        libraryCount=skin.text("",11,StudioUi.MUTED);middle.addView(libraryCount);
        soundList=new ListView(this);soundList.setDividerHeight(skin.dp(1));soundAdapter=new SoundAdapter();soundList.setAdapter(soundAdapter);
        soundList.setOnItemClickListener((parent,view,p,id)->{SoundLibrary.Entry e=shown.get(p);selectedId=e.id;refreshLibraryDetails();soundAdapter.notifyDataSetChanged();guard(()->loadEntry(e));});middle.addView(soundList,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout manage=skin.row();skin.space(manage,skin.button("Importar",StudioUi.BLUE,v->pickSounds()));skin.space(manage,skin.button("Exportar banco",StudioUi.BLUE,v->exportBank()));skin.space(manage,skin.button("Backup",StudioUi.BLUE,v->backupLibrary()));middle.addView(manage);
        libraryDetails=skin.text("Seleccioná un sonido",13,StudioUi.TEXT);libraryDetails.setMaxLines(12);detail.addView(libraryDetails,new LinearLayout.LayoutParams(-1,0,1));
        detail.addView(skin.button("CARGAR / TOCAR",StudioUi.BLUE,v->loadSelected()));
        LinearLayout favRow=skin.row();skin.equal(favRow,skin.button("★ Favorito",StudioUi.ORANGE,v->toggleSelectedFavorite()));skin.equal(favRow,skin.button("Exportar",StudioUi.BLUE,v->exportSelected()));detail.addView(favRow,new LinearLayout.LayoutParams(-1,skin.dp(43)));
        LinearLayout edits=skin.row();skin.equal(edits,skin.button("Copiar",StudioUi.BLUE,v->copySelected()));skin.equal(edits,skin.button("Renombrar",StudioUi.BLUE,v->renameSelected()));detail.addView(edits,new LinearLayout.LayoutParams(-1,skin.dp(43)));
        LinearLayout org=skin.row();skin.equal(org,skin.button("Mover",StudioUi.BLUE,v->moveSelected()));skin.equal(org,skin.button("Setlist +",StudioUi.BLUE,v->addSelectedToSet()));detail.addView(org,new LinearLayout.LayoutParams(-1,skin.dp(43)));
        LinearLayout order=skin.row();skin.equal(order,skin.button("↑",StudioUi.BLUE,v->reorder(-1)));skin.equal(order,skin.button("↓",StudioUi.BLUE,v->reorder(1)));skin.equal(order,skin.button("Eliminar",StudioUi.RED,v->deleteSelected()));detail.addView(order,new LinearLayout.LayoutParams(-1,skin.dp(43)));
        ScrollView navScroll=new ScrollView(this);navScroll.addView(nav);root.addView(navScroll,new LinearLayout.LayoutParams(skin.dp(130),-1));
        LinearLayout.LayoutParams center=new LinearLayout.LayoutParams(0,-1,1);center.setMargins(skin.dp(7),0,skin.dp(7),0);root.addView(middle,center);
        root.addView(detail,new LinearLayout.LayoutParams(skin.dp(205),-1));refreshLibrary();return root;
    }
    private void refreshLibrary(){
        if(page!=4||soundAdapter==null)return;shown=filtered();favoriteFilter.setSelected(onlyFavorites);String where=!setFilter.isEmpty()?"Setlist: "+setFilter:(bankFilter>=0?"Bank "+(char)('A'+bankFilter):sourceFilter);libraryCount.setText(where+" · "+shown.size()+" sonidos · tocar una fila = cargar");
        soundAdapter.notifyDataSetChanged();refreshLibraryDetails();
    }
    private List<SoundLibrary.Entry> filtered(){
        ArrayList<SoundLibrary.Entry> results=new ArrayList<>();if(library==null)return results;
        for(SoundLibrary.Entry e:setFilter.isEmpty()?library.all():library.set(setFilter)){
            if((onlyFavorites||sourceFilter.equals("★ Favoritos"))&&!e.favorite)continue;
            if(sourceFilter.equals("Factory")&&!e.factory)continue;
            if(bankFilter>=0){byte[] primary=SoundCodec.primary(e.data);if(!e.factory||primary[6]!=0x10||(primary[7]&127)!=(bankFilter+1))continue;}
            if(sourceFilter.equals("Usuario")&&(e.factory||!e.source.equals("Usuario")))continue;
            if(sourceFilter.equals("Importados")&&(e.factory||e.source.equals("Usuario")))continue;
            if(sourceFilter.equals("Recientes")&&e.used==0)continue;
            if(sourceFilter.equals("Singles")&&e.multi())continue;if(sourceFilter.equals("Multis")&&!e.multi())continue;
            if(!category.equals("Todas")&&!e.category().contains(category))continue;
            String hay=e.name()+" "+e.slot()+" "+e.category()+" "+e.source+" "+e.collection;
            if(!hay.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))continue;results.add(e);
        }
        if(sourceFilter.equals("Recientes")&&setFilter.isEmpty())results.sort((a,b)->Long.compare(b.used,a.used));return results;
    }
    private final class SoundAdapter extends BaseAdapter{
        public int getCount(){return shown.size();}public Object getItem(int p){return shown.get(p);}public long getItemId(int p){return p;}
        public View getView(int p,View old,ViewGroup parent){SoundLibrary.Entry e=shown.get(p);LinearLayout line=skin.row();
            line.setPadding(skin.dp(6),skin.dp(3),skin.dp(6),skin.dp(3));line.setBackground(skin.box(e.id.equals(selectedId)?StudioUi.BLUE:StudioUi.LINE,e.id.equals(selectedId)));
            TextView label=skin.text(e.slot()+"  "+e.name()+"\n"+e.category()+" · "+e.collection,13,StudioUi.TEXT);label.setMaxLines(2);line.addView(label,new LinearLayout.LayoutParams(0,skin.dp(49),1));
            Button star=skin.button(e.favorite?"★":"☆",StudioUi.ORANGE,v->{String id=e.id;storage("Guardando favorito…",()->library.favorite(id));});line.addView(star,new LinearLayout.LayoutParams(skin.dp(43),skin.dp(43)));return line;}
    }
    private SoundLibrary.Entry selected(){return library==null?null:library.get(selectedId);}
    private void refreshLibraryDetails(){
        if(page!=4||libraryDetails==null)return;SoundLibrary.Entry e=selected();
        if(e==null){libraryDetails.setText("Elegí Bank A–H y tocá cualquier patch para cargarlo inmediatamente.\n\nPATCH +/- recorre el banco actual; BANK +/- conserva el número de patch cuando es posible.\n\nImportar no cambia el patch que está sonando. Factory es de solo lectura.");return;}
        byte[] p=SoundCodec.primary(e.data);String extra=e.multi()?"Multi de "+SoundCodec.split(e.data).size()+" paquetes\nUn Multi aislado referencia sus bancos originales.":
            "Unison: "+((p[9+97]&127)==0?"OFF":Integer.toString(p[9+97]&127))+"\nArp: "+ARP[Math.min(6,p[9+129]&127)];
        libraryDetails.setText((e.favorite?"★ ":"")+e.name()+"\n"+e.slot()+" · "+(e.multi()?"Multi":"Single")+"\n"+e.category()+"\n\nOrigen: "+e.source+"\nColección: "+e.collection+"\n\n"+extra+"\n\n"+(e.factory?"Factory: copiar antes de editar el archivo.":"Copia persistente de usuario."));
    }
    private void loadSelected(){SoundLibrary.Entry e=selected();if(e!=null)guard(()->loadEntry(e));}
    private void loadEntry(SoundLibrary.Entry entry){
        if(!ready){message("Primero cargá una ROM en CONFIG");return;}
        int target=selectedPart;work("Cargando "+entry.name()+"…",()->{
            byte[] primary=SoundCodec.primary(entry.data);
            if(entry.factory&&!entry.multi()) StudioBridge.selectFactory(primary[7]&127,primary[8]&127,target);
            else StudioBridge.load(entry.data,target,entry.multi()?1:0);
            ui.post(()->{if(destroyed)return;if(entry.multi()){selectedPart=0;state=new SoundCodec.Snapshot(entry.data);Arrays.fill(partIds,null);}else{
                byte[] p=SoundCodec.primary(entry.data).clone();p[7]=0;p[8]=(byte)target;SoundCodec.repair(p);if(state!=null)state.singles[target==64?16:target]=p;partIds[target==64?16:target]=entry.id;}
                selectedId=entry.id;dirty=false;comparing=false;generation++;changedAt=SystemClock.elapsedRealtime();refreshPatch();rebuildCurrent();message("Cargado: "+entry.name()+(audio.isRunning()?"":" · pulsá START"));});
            STORAGE.execute(()->{try{library.used(entry.id);}catch(IOException ignored){}});
        });
    }
    private SoundLibrary.Entry factoryEntry(int bank,int program){
        if(library==null)return null;
        for(SoundLibrary.Entry e:library.all()){
            if(!e.factory||e.multi())continue;byte[] p=SoundCodec.primary(e.data);
            if((p[7]&127)==bank&&(p[8]&127)==program)return e;
        }
        return null;
    }
    private void stepBank(int step){
        if(library==null||!ready)return;SoundLibrary.Entry current=library.get(partIds[slot()]);
        int bank=1,program=0;
        if(current!=null&&current.factory&&!current.multi()){byte[] p=SoundCodec.primary(current.data);bank=p[7]&127;program=p[8]&127;}
        bank=((bank-1+step+8)%8)+1;SoundLibrary.Entry next=factoryEntry(bank,program);
        if(next==null)next=factoryEntry(bank,0);
        if(next==null){message("Bank "+(char)('A'+bank-1)+" no está disponible en esta ROM");return;}
        bankFilter=bank-1;sourceFilter="Factory";setFilter="";guard(()->loadEntry(next));
    }
    private void stepPatch(int step){
        if(library==null)return;SoundLibrary.Entry current=library.get(partIds[slot()]);
        if(current!=null&&current.factory&&!current.multi()&&!onlyFavorites&&setFilter.isEmpty()){
            byte[] p=SoundCodec.primary(current.data);int bank=p[7]&127,program=p[8]&127;
            for(int n=1;n<=128;++n){int candidate=(program+step*n+128*4)%128;SoundLibrary.Entry next=factoryEntry(bank,candidate);if(next!=null){bankFilter=bank-1;sourceFilter="Factory";guard(()->loadEntry(next));return;}}
        }
        List<SoundLibrary.Entry> all=new ArrayList<>();List<SoundLibrary.Entry> base=setFilter.isEmpty()?library.all():library.set(setFilter);
        for(SoundLibrary.Entry e:base)if(!e.multi()&&(!onlyFavorites||e.favorite))all.add(e);
        if(all.isEmpty()){message(onlyFavorites?"Todavía no marcaste favoritos":"No hay sonidos Single en la biblioteca");return;}
        String currentId=partIds[slot()];int idx=-1;for(int i=0;i<all.size();++i)if(all.get(i).id.equals(currentId))idx=i;
        SoundLibrary.Entry e=all.get((idx+step+all.size())%all.size());guard(()->loadEntry(e));
    }
    private void guard(Runnable action){
        if(!dirty){action.run();return;}
        new AlertDialog.Builder(this).setTitle("Hay cambios sin guardar")
            .setMessage("Guardá una copia antes de cambiar de sonido si querés conservarlos.")
            .setNegativeButton("Cancelar",null).setPositiveButton("Descartar y continuar",(d,w)->{dirty=false;action.run();}).show();
    }
    private void toggleCurrentFavorite(){String id=partIds[slot()];if(id==null){message("Guardá primero una copia de este sonido para marcarla como favorita");return;}storage("Guardando favorito…",()->library.favorite(id));}
    private void toggleSelectedFavorite(){SoundLibrary.Entry e=selected();if(e!=null)storage("Guardando favorito…",()->library.favorite(e.id));}
    private void saveCurrent(){saveSnapshot(false);}
    private void saveMulti(){if(selectedPart==64){message("Activá MULTI primero");return;}saveSnapshot(true);}
    private void saveSnapshot(boolean arrangement){
        if(library==null||!ready)return;
        if(!audio.isRunning()){message("Pulsá START para aplicar los cambios antes de guardarlos");return;}
        if(comparing){message("Volvé de Comparar antes de guardar");return;}
        String suggested=arrangement?"Live Multi":current()==null?"Mi sonido":SoundCodec.name(current());
        ask("Guardar copia de usuario · máximo 10 ASCII",suggested,name->{
            final int part=selectedPart, saveVersion=generation;work("Leyendo edit buffer real…",()->{
                byte[] raw=StudioBridge.snapshot();if(raw==null)throw new IllegalStateException("Hay cambios MIDI pendientes. Esperá un momento y volvé a guardar.");
                SoundCodec.Snapshot snapshot=new SoundCodec.Snapshot(raw);byte[] data=arrangement?snapshot.arrangement():snapshot.single(part);
                if(data==null)throw new IllegalStateException("El motor no devolvió el sonido seleccionado");
                byte[] renamed=SoundCodec.rename(data,name);
                STORAGE.execute(()->{try{SoundLibrary.Entry e=library.add(renamed,"Usuario",arrangement?"Mis Multis":"Mis sonidos",true);
                    ui.post(()->{if(!destroyed){selectedId=e.id;if(generation==saveVersion && selectedPart==part)dirty=false;message("Guardado: "+e.name()+". La copia ya está en Biblioteca.");if(page==4)refreshLibrary();}});
                }catch(Exception e){ui.post(()->message(e.getMessage()));}});
            });
        });
    }
    private void copySelected(){SoundLibrary.Entry e=selected();if(e!=null)storage("Copiando…",()->{SoundLibrary.Entry copy=library.add(e.data,"Usuario","Mis sonidos",true);selectedId=copy.id;});}
    private void renameSelected(){SoundLibrary.Entry e=selected();if(e==null)return;if(e.factory){message("Factory es solo lectura: usá Copiar");return;}ask("Renombrar · 10 caracteres ASCII",e.name(),name->storage("Renombrando…",()->library.rename(e.id,name)));}
    private void moveSelected(){SoundLibrary.Entry e=selected();if(e==null)return;ask("Colección de usuario",e.collection,name->storage("Moviendo…",()->library.move(e.id,name)));}
    private void deleteSelected(){SoundLibrary.Entry e=selected();if(e==null)return;
        if(!setFilter.isEmpty()){String set=setFilter;storage("Quitando de la setlist…",()->library.removeFromSet(set,e.id));return;}
        if(e.factory){message("Factory es solo lectura; quitar favorito no elimina el sonido");return;}
        new AlertDialog.Builder(this).setTitle("Eliminar copia de usuario").setMessage(e.name()+" se eliminará de la biblioteca y sus setlists. No modifica la ROM.")
            .setNegativeButton("Cancelar",null).setPositiveButton("Eliminar",(d,w)->storage("Eliminando…",()->library.delete(e.id))).show();
    }
    private void addSelectedToSet(){SoundLibrary.Entry e=selected();if(e==null)return;ask("Nombre de la setlist",setFilter.isEmpty()?"Mi directo":setFilter,name->storage("Guardando setlist…",()->library.addToSet(name,e.id)));}
    private void chooseSet(){if(library==null)return;List<String> names=library.setNames();if(names.isEmpty()){message("Seleccioná un sonido y usá Setlist + para crear una lista");return;}
        new AlertDialog.Builder(this).setTitle("Setlists").setItems(names.toArray(new String[0]),(d,w)->{setFilter=names.get(w);sourceFilter="Todos";bankFilter=-1;query="";category="Todas";onlyFavorites=false;rebuildCurrent();}).show();}
    private void reorder(int step){SoundLibrary.Entry e=selected();if(e==null||setFilter.isEmpty()){message("↑/↓ reordena sonidos dentro de una setlist");return;}String set=setFilter;storage("Reordenando…",()->library.reorder(set,e.id,step));}
    private void compare(){
        if(!ready||current()==null)return;
        if(comparing&&compareReturn!=null){StudioBridge.load(compareReturn,compareTarget,0);state.singles[slot()]=compareReturn.clone();comparing=false;generation++;changedAt=SystemClock.elapsedRealtime();refreshValues();controls();message("Volviste a la edición");return;}
        SoundLibrary.Entry original=library==null?null:library.get(partIds[slot()]);if(original==null||original.multi()){message("Cargá un Single de la biblioteca para comparar con el original");return;}
        compareReturn=current().clone();compareTarget=selectedPart;StudioBridge.load(original.data,selectedPart,0);state.singles[slot()]=SoundCodec.primary(original.data).clone();comparing=true;generation++;changedAt=SystemClock.elapsedRealtime();refreshValues();controls();message("COMPARE: original. Pulsá Comparar para volver a tu edición.");
    }
    private void exportSelected(){SoundLibrary.Entry e=selected();if(e!=null)export(e.name()+".syx",e.data,"application/octet-stream");}
    private void exportBank(){try{List<byte[]> data=new ArrayList<>();for(SoundLibrary.Entry e:shown)data.add(e.data);export("RiGear-bank.syx",SoundCodec.exportBank(data),"application/octet-stream");}catch(Exception e){message(e.getMessage());}}
    private void backupLibrary(){storage("Preparando respaldo…",()->{byte[] bytes=library.backup();ui.post(()->export("RiGear-biblioteca.rigearlib",bytes,"application/octet-stream"));});}
    private void export(String name,byte[] data,String mime){
        exportBytes=data.clone();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(mime);i.putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(i,EXPORT);
    }
    private void pickSounds(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,PICK_SOUND);}
    private void pickRom(){guard(()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,PICK_ROM);});}
    @Override protected void onActivityResult(int request,int result,Intent intent){
        super.onActivityResult(request,result,intent);if(result!=RESULT_OK||intent==null||intent.getData()==null)return;Uri uri=intent.getData();
        if(request==PICK_ROM){work("Leyendo ROM…",()->{audio.stop();RomImporter.Bundle bundle=RomImporter.read(this,uri);ui.post(()->{busy=false;bootRom(bundle,true);});});}
        else if(request==PICK_SOUND)storage("Importando sonidos (sin cambiar el patch)…",()->{
            byte[] data=StudioFiles.read(this,uri,64*1024*1024);int added=0;
            if(data.length>4&&data[0]=='R'&&data[1]=='G'&&data[2]=='L'&&data[3]=='B')added=library.mergeBackup(data);
            else{List<byte[]> sounds=StudioFiles.importSounds(data);for(byte[] sound:sounds){library.add(sound,"Importado","Importados",false);++added;}}
            final int count=added;ui.post(()->{if(!destroyed){message("Importados "+count+" sonidos. Ningún banco Factory se sobrescribió.");showPage(4);}});
        });
        else if(request==EXPORT&&exportBytes!=null){byte[] data=exportBytes;exportBytes=null;STORAGE.execute(()->{try{StudioFiles.write(this,uri,data);ui.post(()->{if(!destroyed)message("Archivo exportado correctamente");});}catch(IOException e){ui.post(()->message(e.getMessage()));}});}
    }
    private void bootRom(RomImporter.Bundle bundle,boolean retain){
        midiGate=false;ready=false;dirty=false;
        work("Arrancando Virus y leyendo bancos…",()->{
            audio.stop();String result=NativeBridge.nativeLoadRomBundle(bundle.data,bundle.names);
            if(result==null||!result.contains("DSP BOOT: OK"))throw new IllegalStateException(result);
            String name=StudioBridge.firmwareInfo();byte[][] factory=StudioBridge.factory();byte[] current=StudioBridge.snapshot();
            SoundCodec.Snapshot snapshot=current==null?null:new SoundCodec.Snapshot(current);
            // Keep the control transaction busy until storage completes. No audio is
            // running here, and neither the UI nor the render thread waits for disk I/O.
            String storageWarning=STORAGE.submit(()->{
                try{if(retain)StudioFiles.retainRom(this,bundle);if(library!=null&&factory!=null)library.indexFactory(factory,name);return "";}
                catch(Exception e){return " · almacenamiento: "+e.getMessage();}
            }).get();
            ui.post(()->{if(destroyed)return;state=snapshot;firmware=name;ready=true;selectedPart=64;Arrays.fill(partIds,null);
                if(library!=null&&snapshot!=null&&snapshot.single(64)!=null){String match=SoundCodec.name(snapshot.single(64));for(SoundLibrary.Entry e:library.all())if(e.factory&&e.source.equals(name)&&e.name().equals(match)){partIds[16]=e.id;break;}}
                selectedId=partIds[16];generation++;changedAt=SystemClock.elapsedRealtime();rebuildCurrent();
                message(name+" listo · START en CONFIG"+(storageWarning.isEmpty()?". ROM y favoritos se conservan al cerrar.":storageWarning));});
        });
    }
    private void setMode(boolean useMulti){
        if(!ready||busy)return;
        guard(()->{try{StudioBridge.mode(useMulti);selectedPart=useMulti?partSelector.getSelectedItemPosition():64;dirty=false;generation++;changedAt=SystemClock.elapsedRealtime();rebuildCurrent();message(useMulti?"MULTI: cada parte responde a su canal MIDI. La selección de parte no cambia el canal del teclado.":"SINGLE: canal MIDI 1");}catch(RuntimeException e){message(e.getMessage());}});
    }
    private void autoSync(){
        long now=SystemClock.elapsedRealtime();
        if(!ready||busy||syncing||comparing||now<nextSyncAt||now-changedAt<1500)return;
        long messages=midi==null?0:midi.telemetry.snapshot()[0];
        // A held chord with no new input does not require repeated full-state snapshots.
        if(generation==lastSyncedGeneration && messages==lastSyncedMidi)return;
        nextSyncAt=now+2000;sync(false);
    }
    private void sync(boolean explicit){
        if(!ready||syncing||busy||destroyed||comparing||!audio.isRunning())return;
        syncing=true;final int version=generation;final long messages=midi==null?0:midi.telemetry.snapshot()[0];
        ENGINE.execute(()->{
            try{byte[] data=StudioBridge.snapshot();SoundCodec.Snapshot next=data==null?null:new SoundCodec.Snapshot(data);
                ui.post(()->{if(!destroyed&&next!=null&&generation==version){state=next;lastSyncedGeneration=version;lastSyncedMidi=messages;refreshPatch();refreshValues();if(page==2)fillMulti();if(explicit)message("Panel sincronizado con el edit buffer del Virus");}});
            }catch(Exception e){if(explicit)ui.post(()->message(e.getMessage()));}
            finally{ui.post(()->syncing=false);}
        });
    }
    private void refreshPatch(){
        if(patchName==null)return;byte[] p=current();String id=partIds[slot()];SoundLibrary.Entry e=library==null?null:library.get(id);
        String name=p==null?"Cargar ROM en CONFIG":SoundCodec.name(p);
        if(e!=null&&!e.name().equals(name)){partIds[slot()]=null;e=null;}
        patchName.setText((selectedPart==64?"":("P"+(selectedPart+1)+" · "))+(e!=null?e.slot()+"  ":"")+name+(dirty?" *":""));
        favorite.setText(e!=null&&e.favorite?"★":"☆");favorite.setSelected(e!=null&&e.favorite);
        single.setSelected(selectedPart==64);multi.setSelected(selectedPart!=64);partSelector.setVisibility(selectedPart==64?View.GONE:View.VISIBLE);
        refreshValues();
    }
    private void refreshValues(){
        for(DialBinding b:dials)b.dial.show(value(b.page,b.index));
        for(int i=0;i<arpButtons.size();++i)arpButtons.get(i).setSelected(value(0x71,1)==i);
        if(latch!=null)latch.setSelected(value(0x71,4)>0);
    }
    private View configPage(){
        ScrollView scroll=new ScrollView(this);LinearLayout root=skin.column();scroll.addView(root);
        LinearLayout actions=skin.row();skin.space(actions,skin.button("Importar / cambiar ROM",StudioUi.BLUE,v->pickRom()));
        start=skin.button("START AUDIO",StudioUi.GREEN,v->startAudio());stop=skin.button("STOP",StudioUi.RED,v->stopAudio());skin.space(actions,start);skin.space(actions,stop);
        skin.space(actions,skin.button("SYNC panel",StudioUi.BLUE,v->sync(true)));skin.space(actions,skin.button("RESET METERS",StudioUi.BLUE,v->{if(audio.session()!=null){audio.session().meter.reset();underrunBase=audio.session().underruns;}window.reset();RuntimeBridge.signalStats(true);}));root.addView(actions);
        LinearLayout settings=skin.row();buffer=setting(settings,"Motor",new String[]{"256","512","1024"},"block",0);rate=setting(settings,"Hz salida",new String[]{"Nativa","48000"},"rate",1);
        outputMode=setting(settings,"Modo salida",new String[]{"Conservador","Baja latencia"},"mode",1);outputSize=setting(settings,"Salida frames",new String[]{"256","512","768","1024","1536","2048","4096"},"output",3);root.addView(settings);
        LinearLayout second=skin.row();extra=setting(second,"Extra DSP frames",new String[]{"0","128","256","512","1024","2048","4096","8192"},"extra",2);
        clock=setting(second,"Clock DSP",new String[]{"50%","75%","100%","125%","150%","200%"},"clock",2);gain=setting(second,"Ganancia salida",new String[]{"0 dB","-6 dB","-12 dB","-18 dB","-24 dB"},"gain",1);root.addView(second);
        root.addView(skin.text("Aplicar con STOP → START. Esta versión conserva el motor y la salida de audio 0.9. No supone resuelto el underrun tras varios minutos.",12,StudioUi.MUTED));
        LinearLayout midiRow=skin.row();midiSpinner=new Spinner(this);midiRow.addView(midiSpinner,new LinearLayout.LayoutParams(0,skin.dp(44),1));
        skin.space(midiRow,skin.button("Refresh MIDI",StudioUi.BLUE,v->refreshMidiPorts()));skin.space(midiRow,skin.button("Conectar",StudioUi.BLUE,v->{Object o=midiSpinner.getSelectedItem();if(o instanceof MidiInput.Choice)midi.connect((MidiInput.Choice)o);}));
        skin.space(midiRow,skin.button("Desconectar",StudioUi.BLUE,v->midi.disconnect()));root.addView(midiRow);refreshMidiPorts();
        thermalText=skin.text("",13,StudioUi.GREEN);root.addView(thermalText);
        audioDetails=skin.text("",12,StudioUi.MUTED);root.addView(audioDetails);
        skin.space(root,skin.button("Exportar diagnóstico CSV",StudioUi.BLUE,v->{String text="seconds,cpu_percent,render_percent,ov,underruns,thermal,headroom,cpu_C,battery_C\n"+String.join("\n",trace);export("RiGear-diagnostico.csv",text.getBytes(StandardCharsets.UTF_8),"text/csv");}));
        root.addView(skin.text("Los favoritos y sonidos se guardan localmente. Usá Biblioteca → Backup antes de desinstalar o cambiar una APK con firma distinta. Temperatura CPU no accesible se muestra como N/D.",12,StudioUi.MUTED));return scroll;
    }
    private Spinner setting(LinearLayout row,String label,String[] choices,String key,int fallback){
        LinearLayout column=skin.column();column.addView(skin.text(label,11,StudioUi.MUTED));int saved=Math.max(0,Math.min(choices.length-1,preferences.getInt(key,fallback)));
        Spinner sp=spinner(choices,saved,p->{preferences.edit().putInt(key,p).apply();});column.addView(sp);row.addView(column,new LinearLayout.LayoutParams(0,skin.dp(70),1));return sp;
    }
    private int settingValue(String key,int[] choices,int fallback){return choices[Math.max(0,Math.min(choices.length-1,preferences.getInt(key,fallback)))];}
    private void startAudio(){
        final int f=settingValue("block",BLOCKS,0),r=settingValue("rate",RATES,1),o=settingValue("output",OUTPUTS,3),x=settingValue("extra",EXTRAS,2),c=settingValue("clock",CLOCKS,2),g=settingValue("gain",GAINS,1),m=preferences.getInt("mode",1);
        midiGate=false;if(midi!=null)midi.telemetry.reset(false);
        work("Iniciando audio…",()->{if(!visible||!ready)return;audio.start(f,c,x,g,m,o,r);if(!visible||destroyed){audio.stop();return;}midiGate=true;
            ui.post(()->{if(!destroyed){startedAt=SystemClock.elapsedRealtime();firstUnderrun=-1;lastSyncedGeneration=-1;trace.clear();nextTrace=0;message("Audio activo · "+firmware);}});});
    }
    private void stopAudio(){midiGate=false;work("Deteniendo…",()->{audio.stop();ui.post(()->{if(!destroyed)message("Audio detenido. La edición sigue en memoria; guardá una copia para conservarla al cerrar.");});});}
    private void panic(){if(nativeOk)RuntimeBridge.panic();if(midi!=null)midi.telemetry.reset(false);message("PANIC · notas y sustain liberados");}
    private void refreshMidiPorts(){if(midi==null||midiSpinner==null||page!=5)return;ArrayAdapter<MidiInput.Choice> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,midi.choices());a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);midiSpinner.setAdapter(a);}
    private void controls(){
        boolean active=audio.hasLiveSession();single.setEnabled(ready&&!busy);multi.setEnabled(ready&&!busy);favorite.setEnabled(ready&&library!=null);
        if(start!=null)start.setEnabled(ready&&!busy&&!active);if(stop!=null)stop.setEnabled(active&&!busy);
        if(page==5)for(Spinner s:new Spinner[]{buffer,rate,outputMode,outputSize,extra,clock,gain})if(s!=null)s.setEnabled(!active&&!busy);
        for(DialBinding b:dials)b.dial.setEnabled(ready&&!busy&&!comparing&&current()!=null);for(Button b:arpButtons)b.setEnabled(ready&&!busy&&!comparing);if(latch!=null)latch.setEnabled(ready&&!busy&&!comparing);
    }
    private void meters(){
        long wall=SystemClock.elapsedRealtime(),cpu=android.os.Process.getElapsedCpuTime();double use=wallBefore>0&&wall>wallBefore?100.0*(cpu-cpuBefore)/(wall-wallBefore):0;wallBefore=wall;cpuBefore=cpu;
        RealtimeAudio.Session s=audio.session();ThermalMonitor.Reading t=thermal.reading;
        double render=0;long ov=0;int ud=0;String audioText="Audio detenido";
        if(s!=null){if(s!=measured){measured=s;window.reset();underrunBase=0;}long[] v=s.meter.snapshot();double[] w=window.sample(System.nanoTime(),v,s.deviceSampleRate);render=w[0];ov=v[4];ud=s.underruns;
            if(ud>0&&firstUnderrun<0)firstUnderrun=wall-startedAt;
            RealtimeAudio.OutputState out=s.output;
            audioText=String.format(Locale.ROOT,"%s · Motor %d · Salida %d/%d · %d Hz · Pendiente %s",RealtimeAudio.performanceName(out.performanceMode),s.frames,out.effectiveFrames,out.capacityFrames,s.sampleRate,out.queuedFrames<0?"N/D":String.format(Locale.ROOT,"%.1f ms",out.queuedFrames*1000.0/s.sampleRate));
            if(!s.error.isEmpty()){message(s.error);midiGate=false;}
            if(audioDetails!=null)audioDetails.setText(s.configuration+"\n"+audioText+"\nRuta: "+out.route+"\nVirus "+s.deviceSampleRate+" Hz → Android "+s.sampleRate+" Hz\nSRC último "+String.format(Locale.ROOT,"%.3f ms",s.srcNanos/1e6)+"\nTiempo sesión: "+elapsed(wall-startedAt)+" · primer underrun observado: "+(firstUnderrun<0?"ninguno":elapsed(firstUnderrun))+"\nEl sondeo es de 1 segundo; no es una medida exacta tecla-a-sonido.");
            if(!s.finished&&wall>=nextTrace){nextTrace=wall+2000;if(trace.size()>=900)trace.remove(0);trace.add(String.format(Locale.ROOT,"%.1f,%.2f,%.2f,%d,%d,%s,%s,%s,%s",(wall-startedAt)/1000.0,use,render,ov,ud,ThermalMonitor.statusName(t.status),Float.toString(t.headroom),Float.toString(t.cpuC),Float.toString(t.batteryC)));}
        }
        topStats.setText(String.format(Locale.ROOT,"CPU app %.1f%% · Render %.1f%% · OV %d · Underruns %d · %s",use,render,ov,Math.max(0,ud-underrunBase),t.compact()));
        if(thermalText!=null)thermalText.setText(t.detail());
        long[] mt=midi==null?new long[5]:midi.telemetry.snapshot();int channel=selectedPart==64||state==null||state.multi==null?0:state.multi[9+64+selectedPart]&15;
        footer.setText((midi==null?"MIDI desconectado":midi.status)+String.format(Locale.ROOT," · teclas USB %d · sustain ch%d %s\n",mt[3],channel+1,(mt[4]&(1<<channel))!=0?"ON":"OFF")+audioText);controls();
    }
    private static String elapsed(long ms){return String.format(Locale.ROOT,"%02d:%02d",Math.max(0,ms)/60000,(Math.max(0,ms)/1000)%60);}
    private interface TextAction{void run(String text);}
    private interface NumberAction{void run(int value);}
    private void ask(String title,String value,TextAction action){
        EditText input=new EditText(this);input.setSingleLine();input.setText(value);input.selectAll();
        AlertDialog d=new AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Cancelar",null).setPositiveButton("Aceptar",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b->{try{action.run(input.getText().toString().trim());d.dismiss();}catch(RuntimeException e){input.setError(e.getMessage());}}));d.show();
    }
    private void number(String title,int min,int max,int initial,NumberAction action){ask(title+" ("+min+"–"+max+")",Integer.toString(initial),s->{int n=Integer.parseInt(s);if(n<min||n>max)throw new IllegalArgumentException("Fuera de rango");action.run(n);});}
    private interface Selection{void run(int position);}
    private Spinner spinner(String[] choices,int selected,Selection change){Spinner sp=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,choices);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);sp.setAdapter(a);sp.setSelection(selected);
        if(change!=null)sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>a,View v,int p,long id){change.run(p);}public void onNothingSelected(AdapterView<?>a){}});return sp;}
    @Override protected void onStart(){super.onStart();visible=true;getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);ui.removeCallbacks(tick);ui.postDelayed(tick,1000);}
    @Override protected void onStop(){visible=false;midiGate=false;ui.removeCallbacks(tick);ENGINE.execute(()->{try{audio.stop();}catch(Exception ignored){}});super.onStop();}
    @Override protected void onDestroy(){destroyed=true;visible=false;midiGate=false;ui.removeCallbacks(tick);if(midi!=null)midi.close();if(thermal!=null)thermal.close();
        ENGINE.execute(()->{try{audio.stop();if(nativeOk)NativeBridge.nativeRelease();}catch(Exception e){android.util.Log.e("RiGear","Safe engine shutdown failed",e);}});super.onDestroy();}
}
