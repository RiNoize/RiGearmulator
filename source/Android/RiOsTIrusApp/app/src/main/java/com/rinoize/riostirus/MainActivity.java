package com.rinoize.riostirus;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** RiOsTIrus 0.2.0 "Snow White": play screen with performance knobs, a patch library modelled
 * on the OsTIrus plugin (collections, categories, tags, search, favourites), and the 0.1.0
 * test bench as the settings page. */
public final class MainActivity extends Activity implements PlayPanel.Host, LibraryPanel.Host {
    private static final String VERSION = "0.2.2";
    private static final int PICK_FIRMWARE = 1;
    private static final int MAX_FIRMWARE = 10 * 1024 * 1024;
    private static final int RECENT_LIMIT = 40;
    private static final String[] MODELS = {"Virus TI", "Virus TI2", "Virus TI Snow"};
    private static final int[] RATES = {44100, 48000};
    private static final int[] BLOCKS = {256, 512, 1024};
    private static final int[] CLOCKS = {100, 75, 50};
    private static final int[] OUTPUTS = {512, 768, 1024, 1536, 2048, 3072, 4096};
    private static final int[] CHORD4 = {48, 52, 55, 59};
    private static final int[] CHORD8 = {36, 43, 48, 52, 55, 59, 62, 67};
    private static final int PAGE_PLAY = 0, PAGE_LIBRARY = 1, PAGE_SETTINGS = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();   // boot, start, stop
    private final ExecutorService control = Executors.newSingleThreadExecutor();  // patch select, sync
    private final RealtimeAudio audio = new RealtimeAudio();
    private final MeterWindow renderWindow = new MeterWindow();
    private final List<Button> needsEngine = new ArrayList<>(), tabs = new ArrayList<>();
    private final List<Spinner> engineSettings = new ArrayList<>();
    private SharedPreferences prefs;
    private MidiInput midi;
    private List<MidiInput.Choice> ports = new ArrayList<>();
    private TextView stats, status, subtitle, engineInfo, diagnostics, midiStatus;
    private Button play, stop, loadButton, bootButton;
    private Spinner midiChoice, rateChoice, outputChoice;
    private AudioManager audioManager;
    private List<AudioDeviceInfo> outputs = new ArrayList<>();
    private boolean bindingOutputs;
    private final AudioDeviceCallback outputWatcher = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { refreshOutputs(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { refreshOutputs(); }
    };
    private PlayPanel playPanel;
    private LibraryPanel libraryPanel;
    private View[] pages;
    private int page;
    private volatile boolean busy, ready, visible, destroyed;
    private boolean nativeOk;
    private int program;
    private volatile int[] held = new int[0];
    private long cpuBefore, wallBefore;
    private String firmwareName = "Sin firmware", lastError = "", bootInfo = "";
    private RealtimeAudio.Session measured;

    private PatchLibrary library = PatchLibrary.parse("");
    private final Set<String> favorites = new HashSet<>();
    private List<String> recent = new ArrayList<>();
    private List<PatchLibrary.Patch> results = new ArrayList<>();
    private String listDescription = "Todos";
    private String currentKey = "";

    private interface Job { void run() throws Exception; }
    private final Runnable tick = new Runnable() { public void run() {
        if (!visible || destroyed) return;
        refresh();
        ui.postDelayed(this, 1000);
    }};
    private final Runnable panelTick = new Runnable() { public void run() {
        if (!visible || destroyed) return;
        refreshPanel();
        ui.postDelayed(this, 200);
    }};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("riostirus-v1", MODE_PRIVATE);
        firmwareName = prefs.getString("firmwareName", "Sin firmware");
        program = Math.max(0, Math.min(127, prefs.getInt("program", 0)));
        favorites.addAll(prefs.getStringSet("favorites", Collections.emptySet()));
        String r = prefs.getString("recent", "");
        if (!r.isEmpty()) recent = new ArrayList<>(Arrays.asList(r.split(",")));
        try { NativeBridge.nativeGetCoreInfo(); nativeOk = true; }
        catch (LinkageError e) { nativeOk = false; }
        midi = new MidiInput(this, ui, () -> ready && !busy, () -> post(this::refreshPorts));
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        setContentView(buildUi());
        refreshPorts();
        refreshOutputs();
        if (audioManager != null) audioManager.registerAudioDeviceCallback(outputWatcher, ui);
        showPage(firmwareFile().isFile() ? PAGE_PLAY : PAGE_SETTINGS);
        if (!nativeOk) message("La biblioteca nativa no cargó. Este APK necesita ARM64.");
        else if (firmwareFile().isFile()) boot(null, firmwareName);
        else message("Cargá el firmware del Virus TI en AJUSTES (.bin del OS, 6 a 9 MiB).");
        controls();
    }

    @Override protected void onResume() { super.onResume(); visible = true; ui.post(tick); ui.post(panelTick); }
    @Override protected void onPause() {
        visible = false; ui.removeCallbacks(tick); ui.removeCallbacks(panelTick);
        if (audio.hasLiveSession()) work("Deteniendo audio…", this::stopWorker);
        super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed = true; ui.removeCallbacksAndMessages(null);
        if (audioManager != null) audioManager.unregisterAudioDeviceCallback(outputWatcher);
        if (midi != null) midi.close();
        control.shutdown();
        worker.execute(() -> {
            try { audio.stop(); } catch (Exception ignored) {}
            try { if (nativeOk) NativeBridge.nativeRelease(); } catch (RuntimeException | LinkageError ignored) {}
        });
        worker.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        LinearLayout root = Snow.column(this); root.setBackgroundColor(Snow.BG);
        root.setPadding(Snow.dp(this, 12), Snow.dp(this, 8), Snow.dp(this, 12), Snow.dp(this, 8));

        LinearLayout header = Snow.row(this);
        TextView title = Snow.text(this, "RiOsTIrus", 24, Snow.RED); title.setTypeface(Snow.CONDENSED); header.addView(title);
        subtitle = Snow.text(this, "  Virus TI · " + VERSION + " α", 12, Snow.MUTED);
        header.addView(subtitle, new LinearLayout.LayoutParams(0, -2, 1));
        String[] names = {"TOCAR", "BIBLIOTECA", "AJUSTES"};
        for (int i = 0; i < names.length; i++) {
            final int target = i;
            Button tab = Snow.button(this, names[i], false, v -> showPage(target));
            tabs.add(tab); header.addView(tab, margin(Snow.dp(this, 4)));
        }
        View gap = new View(this); header.addView(gap, new LinearLayout.LayoutParams(Snow.dp(this, 14), 1));
        play = Snow.button(this, "▶ INICIAR", true, v -> startAudio()); header.addView(play, margin(Snow.dp(this, 4)));
        stop = Snow.button(this, "■ DETENER", false, v -> stopAudio()); header.addView(stop, margin(Snow.dp(this, 4)));
        header.addView(Snow.button(this, "PANIC", false, v -> panic()));
        root.addView(header);

        LinearLayout statusRow = Snow.row(this); statusRow.setPadding(0, Snow.dp(this, 4), 0, Snow.dp(this, 6));
        stats = Snow.text(this, "Motor detenido", 12, Snow.MUTED); statusRow.addView(stats, new LinearLayout.LayoutParams(0, -2, 1));
        status = Snow.text(this, "", 12, Snow.TEXT); status.setGravity(Gravity.END); statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(statusRow);

        FrameLayout body = new FrameLayout(this);
        playPanel = new PlayPanel(this, this);
        libraryPanel = new LibraryPanel(this, this);
        View settings = buildSettings();
        pages = new View[]{playPanel.view, libraryPanel.view, settings};
        for (View v : pages) body.addView(v, new FrameLayout.LayoutParams(-1, -1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        libraryPanel.refresh();
        return root;
    }

    private static LinearLayout.LayoutParams margin(int right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.setMargins(0, 0, right, 0); return p;
    }

    private void showPage(int target) {
        page = target;
        for (int i = 0; i < pages.length; i++) pages[i].setVisibility(i == page ? View.VISIBLE : View.GONE);
        for (int i = 0; i < tabs.size(); i++) Snow.style(tabs.get(i), false, i == page);
        if (page == PAGE_LIBRARY && !currentKey.isEmpty()) libraryPanel.reveal(currentKey);
    }

    private View buildSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = Snow.column(this); scroll.addView(content);

        LinearLayout firmwareCard = Snow.card(this, "Firmware");
        LinearLayout firmware = Snow.row(this); firmware.setPadding(0, Snow.dp(this, 6), 0, Snow.dp(this, 4));
        loadButton = Snow.button(this, "CARGAR FIRMWARE .BIN", true, v -> pickFirmware()); firmware.addView(loadButton, margin(Snow.dp(this, 6)));
        bootButton = Snow.button(this, "REINICIAR MOTOR", false, v -> boot(null, firmwareName)); firmware.addView(bootButton, margin(Snow.dp(this, 6)));
        engineInfo = Snow.text(this, firmwareName, 13, Snow.TEXT); firmware.addView(engineInfo, new LinearLayout.LayoutParams(0, -2, 1));
        firmwareCard.addView(firmware);
        firmwareCard.addView(Snow.text(this, "El archivo del sistema operativo del Virus TI (el mismo que usa OsTIrus en la PC) sirve para TI, TI2 y Snow. Modelo y frecuencia se aplican con REINICIAR MOTOR; Snow usa un solo DSP.", 11, Snow.MUTED));
        LinearLayout engine = Snow.row(this); engine.setPadding(0, Snow.dp(this, 6), 0, 0);
        engineSettings.add(setting(engine, "Modelo", MODELS, "model", 0));
        rateChoice = setting(engine, "Frecuencia", new String[]{"44100 Hz", "48000 Hz"}, "rate", 0);
        engineSettings.add(rateChoice);
        firmwareCard.addView(engine);
        content.addView(firmwareCard);

        LinearLayout audioCard = Snow.card(this, "Audio y núcleos");
        LinearLayout audioRow = Snow.row(this); audioRow.setPadding(0, Snow.dp(this, 6), 0, 0);
        engineSettings.add(setting(audioRow, "Buffer motor", new String[]{"256", "512", "1024"}, "block", 1));
        engineSettings.add(setting(audioRow, "Reloj DSP", new String[]{"100%", "75%", "50%"}, "clock", 0));
        engineSettings.add(setting(audioRow, "Modo", new String[]{"Conservador", "Baja latencia + AUTO"}, "mode", 1));
        engineSettings.add(setting(audioRow, "Buffer salida", new String[]{"512", "768", "1024", "1536", "2048", "3072", "4096"}, "output", 3));
        audioCard.addView(audioRow);
        LinearLayout outputRow = Snow.row(this);
        LinearLayout outputBox = Snow.column(this); outputBox.addView(Snow.label(this, "Salida de audio"));
        outputChoice = new Spinner(this);
        outputChoice.setBackground(Snow.surface(this, Snow.PANEL_LOW, Snow.EDGE, 6));
        outputChoice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int p, long id) {
                if (v instanceof TextView) ((TextView) v).setTextColor(Snow.TEXT);
                if (!bindingOutputs) outputChosen(p);
            }
            public void onNothingSelected(AdapterView<?> a) {}
        });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, Snow.dp(this, 42)); op.setMargins(0, Snow.dp(this, 4), 0, 0);
        outputBox.addView(outputChoice, op);
        outputRow.addView(outputBox, new LinearLayout.LayoutParams(0, -2, 1));
        audioCard.addView(outputRow);
        audioCard.addView(Snow.text(this, "Con auriculares en la tablet, Android manda el audio al jack aunque haya una placa USB: elegí acá el KROSS u otra placa. Si la salida trabaja a otra frecuencia, el motor se reinicia en la correcta para no perder la baja latencia.", 11, Snow.MUTED));
        LinearLayout cores = Snow.row(this);
        engineSettings.add(setting(cores, "Núcleos (hilos DSP)", new String[]{"Automático (Android decide)",
                "Rendimiento: núcleos rápidos", "Repartido: un núcleo rápido por DSP"}, "cpu", RealtimeAudio.CPU_SPREAD));
        audioCard.addView(cores);
        audioCard.addView(Snow.text(this, "Se aplican al detener e iniciar. Si «DSP DSP A» o «DSP DSP B» se acercan a 100% de un núcleo, ese DSP no llega a tiempo real.", 11, Snow.MUTED));
        content.addView(audioCard, cardGap());

        LinearLayout midiCard = Snow.card(this, "MIDI y prueba");
        LinearLayout connection = Snow.row(this); connection.setPadding(0, Snow.dp(this, 6), 0, 0);
        midiChoice = new Spinner(this); connection.addView(midiChoice, new LinearLayout.LayoutParams(0, Snow.dp(this, 44), 1));
        connection.addView(Snow.button(this, "CONECTAR MIDI", true, v -> {
            if (ports.isEmpty()) { message("No hay puertos MIDI disponibles"); return; }
            midi.connect(ports.get(Math.max(0, midiChoice.getSelectedItemPosition())));
        }), margin(Snow.dp(this, 6)));
        connection.addView(Snow.button(this, "DESCONECTAR", false, v -> midi.disconnect()));
        midiCard.addView(connection);
        midiStatus = Snow.text(this, midi.status, 12, Snow.MUTED); midiStatus.setPadding(0, Snow.dp(this, 4), 0, 0); midiCard.addView(midiStatus);
        LinearLayout test = Snow.row(this); test.setPadding(0, Snow.dp(this, 8), 0, Snow.dp(this, 2));
        Button down = Snow.button(this, "PROGRAMA −", false, v -> programChange(-1)); test.addView(down, margin(Snow.dp(this, 6))); needsEngine.add(down);
        Button up = Snow.button(this, "PROGRAMA +", false, v -> programChange(1)); test.addView(up, margin(Snow.dp(this, 6))); needsEngine.add(up);
        Button c4 = Snow.button(this, "ACORDE 4", false, v -> chord(CHORD4)); test.addView(c4, margin(Snow.dp(this, 6))); needsEngine.add(c4);
        Button c8 = Snow.button(this, "ACORDE 8", false, v -> chord(CHORD8)); test.addView(c8, margin(Snow.dp(this, 6))); needsEngine.add(c8);
        Button off = Snow.button(this, "SOLTAR", false, v -> release()); test.addView(off); needsEngine.add(off);
        midiCard.addView(test);
        content.addView(midiCard, cardGap());

        LinearLayout diagCard = Snow.card(this, "Diagnóstico");
        Button copy = Snow.button(this, "COPIAR DIAGNÓSTICO", false, v -> copyDiagnostics());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.setMargins(0, Snow.dp(this, 6), 0, Snow.dp(this, 4));
        diagCard.addView(copy, cp);
        diagnostics = Snow.text(this, "", 12, Snow.MUTED); diagnostics.setLineSpacing(0, 1.2f); diagCard.addView(diagnostics);
        content.addView(diagCard, cardGap());
        return scroll;
    }

    private LinearLayout.LayoutParams cardGap() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, Snow.dp(this, 8), 0, 0); return p;
    }

    private Spinner setting(LinearLayout parent, String label, String[] choices, String key, int fallback) {
        LinearLayout box = Snow.column(this); box.addView(Snow.label(this, label));
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
        spinner.setSelection(bounded(prefs.getInt(key, fallback), choices.length));
        spinner.setBackground(Snow.surface(this, Snow.PANEL_LOW, Snow.EDGE, 6));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int p, long id) {
                if (v instanceof TextView) ((TextView) v).setTextColor(Snow.TEXT);
                prefs.edit().putInt(key, p).apply();
            }
            public void onNothingSelected(AdapterView<?> a) {}
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Snow.dp(this, 42)); sp.setMargins(0, Snow.dp(this, 4), 0, 0);
        box.addView(spinner, sp);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(0, 0, Snow.dp(this, 10), 0);
        parent.addView(box, params);
        return spinner;
    }

    // ---------------------------------------------------------------- library and play hosts

    @Override public PatchLibrary library() { return library; }
    @Override public Set<String> favorites() { return favorites; }
    @Override public List<String> recent() { return recent; }
    @Override public String currentKey() { return currentKey; }

    @Override public void filterChanged(List<PatchLibrary.Patch> list, String description) {
        results = list; listDescription = description; updateDisplay();
    }

    @Override public void select(PatchLibrary.Patch patch) {
        if (!ready || patch == null) return;
        control.execute(() -> {
            try {
                NativeBridge.nativeSelectPatch(patch.bank, patch.program);
                post(() -> {
                    currentKey = patch.key();
                    recent = PatchLibrary.pushRecent(recent, currentKey, RECENT_LIMIT);
                    prefs.edit().putString("recent", String.join(",", recent)).putString("lastPatch", currentKey).apply();
                    if (libraryPanel.filter.source == PatchLibrary.Source.RECENT) libraryPanel.refresh(); else libraryPanel.repaint();
                    updateDisplay();
                    if (!audio.isRunning()) message("Patch cargado: suena al iniciar el audio");
                });
            } catch (RuntimeException e) { post(() -> message(e.getMessage())); }
        });
    }

    @Override public void toggleFavorite(PatchLibrary.Patch patch) {
        if (patch == null) return;
        if (!favorites.remove(patch.key())) favorites.add(patch.key());
        prefs.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
        libraryPanel.refresh();
        updateDisplay();
        message(favorites.contains(patch.key()) ? "★ " + patch.name + " en favoritos" : patch.name + " quitado de favoritos");
    }

    @Override public void toggleFavoriteCurrent() { toggleFavorite(currentPatch()); }
    @Override public void openLibrary() { showPage(PAGE_LIBRARY); }

    /** PATCH ◀ ▶ walk the library's current list, so a filtered category becomes a set list. */
    @Override public void step(int delta) {
        if (results.isEmpty()) { message("La lista actual de la biblioteca está vacía"); return; }
        int index = -1;
        for (int i = 0; i < results.size(); i++) if (results.get(i).key().equals(currentKey)) { index = i; break; }
        int next = index < 0 ? (delta > 0 ? 0 : results.size() - 1) : Math.floorMod(index + delta, results.size());
        select(results.get(next));
    }

    @Override public void bankStep(int delta) {
        if (library.bankCount() == 0) return;
        PatchLibrary.Patch p = currentPatch();
        int bank = p == null ? 0 : Math.floorMod(p.bank + delta, library.bankCount());
        select(library.find(bank, 0));
    }

    @Override public boolean sendParameter(int pageId, int index, int value) {
        if (!ready) return false;
        try { return RuntimeBridge.setParameter(pageId, index, value); }
        catch (RuntimeException e) { message(e.getMessage()); return false; }
    }

    private PatchLibrary.Patch currentPatch() {
        int colon = currentKey.indexOf(':');
        if (colon <= 0) return null;
        try { return library.find(Integer.parseInt(currentKey.substring(0, colon)), Integer.parseInt(currentKey.substring(colon + 1))); }
        catch (NumberFormatException e) { return null; }
    }

    private void updateDisplay() {
        if (playPanel == null) return;
        PatchLibrary.Patch p = currentPatch();
        if (!ready) { playPanel.showPatch(busy ? "Arrancando el motor…" : "Sin firmware", "RiOsTIrus", "Cargá el firmware en AJUSTES", false); return; }
        String name;
        try { name = RuntimeBridge.currentName(); } catch (RuntimeException e) { name = ""; }
        if (p == null) {
            playPanel.showPatch("Edit buffer · " + library.size() + " patches en la ROM", name.isEmpty() ? "—" : name,
                    "Lista: " + listDescription + " (" + results.size() + ")", false);
            return;
        }
        int position = -1;
        for (int i = 0; i < results.size(); i++) if (results.get(i) == p) { position = i; break; }
        String cats = p.categories();
        String slotText = library.bankName(p.bank) + " · " + String.format(Locale.ROOT, "%03d", p.program + 1)
                + (cats.isEmpty() ? "" : " · " + cats) + " · " + p.model();
        String infoText = "Lista: " + listDescription + (position >= 0
                ? String.format(Locale.ROOT, " · %d / %d", position + 1, results.size())
                : " · (fuera de la lista, " + results.size() + ")");
        playPanel.showPatch(slotText, name.isEmpty() ? p.name : name, infoText, favorites.contains(p.key()));
    }

    /** 200 ms: knob values. Only the MIDI lock is touched, never the engine. A Program Change
     * from the keyboard is re-read by the render thread itself; here it shows up as a new
     * revision with no ROM slot, so the display switches to the edit buffer. */
    private int shownRevision = -1;
    private void refreshPanel() {
        if (!nativeOk || !ready || busy) { if (playPanel != null) playPanel.bind(null, false); return; }
        try {
            int[] values = RuntimeBridge.values(playPanel.pages, playPanel.indices);
            if (values == null) return;
            playPanel.bind(values, true);
            if (values[0] == shownRevision) return;
            shownRevision = values[0];
            String key = values[2] >= 0 && values[3] >= 0 ? values[2] + ":" + values[3] : "";
            if (!key.equals(currentKey)) { currentKey = key; libraryPanel.repaint(); }
            updateDisplay();
        } catch (RuntimeException | LinkageError e) { message(e.getMessage()); }
    }

    // ---------------------------------------------------------------- engine

    private File firmwareFile() { return new File(getFilesDir(), "firmware.bin"); }

    private void pickFirmware() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
        startActivityForResult(intent, PICK_FIRMWARE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FIRMWARE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        boot(uri, AppFiles.name(this, uri));
    }

    /** uri == null: reboot the stored firmware with the current model and rate. */
    private void boot(Uri uri, String name) {
        final int model = bounded(prefs.getInt("model", 0), MODELS.length);
        final int rate = RATES[bounded(prefs.getInt("rate", 0), RATES.length)];
        work("Arrancando " + MODELS[model] + "…", () -> {
            stopWorker();
            ready = false;
            post(this::updateDisplay);
            byte[] rom = uri != null ? AppFiles.read(this, uri, MAX_FIRMWARE) : AppFiles.read(firmwareFile(), MAX_FIRMWARE);
            long before = SystemClock.elapsedRealtime();
            String info = NativeBridge.nativeLoadRom(rom, name, model, rate);
            long ms = SystemClock.elapsedRealtime() - before;
            if (uri != null) { AppFiles.atomic(firmwareFile(), rom); prefs.edit().putString("firmwareName", name).apply(); }
            PatchLibrary loaded = PatchLibrary.parse(NativeBridge.nativeLibrary());
            ready = true;
            bootInfo = info + " | arranque " + ms + " ms";
            String last = prefs.getString("lastPatch", "");
            post(() -> {
                firmwareName = name; engineInfo.setText(name + "\n" + bootInfo);
                subtitle.setText("  " + MODELS[model] + " · " + rate + " Hz · " + loaded.size() + " patches · " + VERSION + " α");
                library = loaded; currentKey = "";
                libraryPanel.refresh();
                message(MODELS[model] + " listo en " + ms + " ms");
                PatchLibrary.Patch restore = null;
                int colon = last.indexOf(':');
                if (colon > 0) try { restore = library.find(Integer.parseInt(last.substring(0, colon)), Integer.parseInt(last.substring(colon + 1))); }
                catch (NumberFormatException ignored) {}
                if (restore != null) select(restore);
                updateDisplay();
            });
        });
    }

    private void startAudio() {
        if (!ready) { message("Primero cargá el firmware en AJUSTES."); return; }
        final int frames = BLOCKS[bounded(prefs.getInt("block", 1), BLOCKS.length)];
        final int clock = CLOCKS[bounded(prefs.getInt("clock", 0), CLOCKS.length)];
        final int mode = bounded(prefs.getInt("mode", 1), 2);
        final int output = OUTPUTS[bounded(prefs.getInt("output", 3), OUTPUTS.length)];
        final int cpu = bounded(prefs.getInt("cpu", RealtimeAudio.CPU_SPREAD), 3);
        final String outputKey = prefs.getString("outputKey", "");
        final AudioDeviceInfo device = audioManager == null ? null : OutputDevices.find(audioManager, outputKey);
        work("Iniciando audio…", () -> {
            if (!visible) return;
            audio.start(frames, clock, -6, mode, output, cpu, device);
            lastError = "";
            String where = device != null ? " por " + OutputDevices.label(device)
                    : outputKey.isEmpty() ? "" : " (la salida elegida no está conectada: decide Android)";
            post(() -> message("Audio activo" + where));
        });
    }

    private void stopAudio() { work("Deteniendo audio…", () -> { stopWorker(); post(() -> message("Audio detenido")); }); }

    /** Worker thread only. */
    private void stopWorker() throws Exception {
        audio.stop();
        try { if (ready) RuntimeBridge.panic(); } catch (RuntimeException ignored) {}
        held = new int[0];
    }

    private void panic() {
        if (!ready) return;
        try { RuntimeBridge.panic(); held = new int[0]; message("PANIC: notas y pedal liberados"); }
        catch (RuntimeException e) { message(e.getMessage()); }
    }

    private void programChange(int delta) {
        program = (program + delta + 128) % 128;
        prefs.edit().putInt("program", program).apply();
        send(0xc0, program, 0);
        message("Program Change canal 1: " + (program + 1));
    }

    private void chord(int[] notes) {
        release();
        for (int note : notes) send(0x90, note, 100);
        held = notes.clone();
        message("Sosteniendo " + notes.length + " notas (canal 1)");
    }

    private void release() {
        for (int note : held) send(0x80, note, 0);
        held = new int[0];
    }

    private void send(int statusByte, int a, int b) {
        if (!ready) return;
        try { NativeBridge.nativeSendShort(statusByte, a, b); } catch (RuntimeException e) { message(e.getMessage()); }
    }

    // ---------------------------------------------------------------- status

    private void refresh() {
        try {
            long now = SystemClock.elapsedRealtime(), cpu = android.os.Process.getElapsedCpuTime();
            double appCpu = wallBefore > 0 && now > wallBefore ? 100.0 * (cpu - cpuBefore) / (now - wallBefore) : 0;
            wallBefore = now; cpuBefore = cpu;
            RealtimeAudio.Session session = audio.session();
            if (session != measured) { measured = session; renderWindow.reset(); }
            if (session != null && audio.isRunning()) {
                long[] totals = session.meter.snapshot();
                double[] window = renderWindow.sample(System.nanoTime(), totals, session.deviceSampleRate);
                stats.setText(String.format(Locale.ROOT, "● Audio  ·  render %.0f%%  ·  CPU app %.0f%%  ·  underruns %d  ·  %d Hz",
                        window[0], appCpu, session.underruns, session.sampleRate));
                RealtimeAudio.OutputState s = session.output;
                diagnostics.setText(session.configuration + "\nSalida: " + s.effectiveFrames + " frames / capacidad " + s.capacityFrames
                        + " · " + RealtimeAudio.performanceName(s.performanceMode) + "\n" + s.route
                        + (session.monitorNote.isEmpty() ? "" : "\n" + session.monitorNote) + "\n" + audio.healthSummary()
                        + "\n" + session.placementSummary + (session.placement.isEmpty() ? "" : "\n" + session.placement));
            } else {
                stats.setText(String.format(Locale.ROOT, "%s  ·  CPU app %.0f%%", busy ? "Preparando motor…" : "Audio detenido", appCpu));
            }
            if (session != null && !session.error.isEmpty() && !session.error.equals(lastError)) {
                lastError = session.error; message(session.error);
            }
            String midiText = midi.status;
            if (ready) try { midiText += " · " + NativeBridge.nativeGetMidiStats(); } catch (RuntimeException ignored) {}
            midiStatus.setText(midiText);
            controls();
        } catch (RuntimeException | LinkageError e) { message(e.getMessage()); }
    }

    private void controls() {
        boolean available = nativeOk && !busy;
        boolean live = audio.hasLiveSession();
        play.setEnabled(available && ready && !live);
        stop.setEnabled(available && live);
        loadButton.setEnabled(available);
        bootButton.setEnabled(available && firmwareFile().isFile());
        for (Button b : needsEngine) b.setEnabled(available && ready);
        for (Spinner s : engineSettings) s.setEnabled(available && !live);
        if (outputChoice != null) outputChoice.setEnabled(available && !live);
    }

    /** Rebuild the output list (on start and whenever Android adds or removes a device). */
    private void refreshOutputs() {
        if (outputChoice == null || audioManager == null) return;
        outputs = OutputDevices.list(audioManager);
        String key = prefs.getString("outputKey", "");
        List<String> names = new ArrayList<>();
        names.add("Automática (Android decide)");
        int selected = 0;
        for (int i = 0; i < outputs.size(); i++) {
            names.add(OutputDevices.label(outputs.get(i)));
            if (OutputDevices.key(outputs.get(i)).equals(key)) selected = i + 1;
        }
        if (!key.isEmpty() && selected == 0) {      // remembered device unplugged: keep the choice
            String name = key.substring(key.indexOf('|') + 1);
            names.add((name.isEmpty() ? "Salida elegida" : name) + " (desconectada)");
            selected = names.size() - 1;
        }
        bindingOutputs = true;
        outputChoice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        outputChoice.setSelection(selected, false);
        outputChoice.post(() -> bindingOutputs = false);
    }

    private void outputChosen(int position) {
        if (position > outputs.size()) return;                       // the "(desconectada)" entry
        AudioDeviceInfo device = position == 0 ? null : outputs.get(position - 1);
        String key = device == null ? "" : OutputDevices.key(device);
        if (key.equals(prefs.getString("outputKey", ""))) return;
        prefs.edit().putString("outputKey", key).apply();
        if (device == null) { message("Salida automática: Android decide"); return; }
        int current = RATES[bounded(prefs.getInt("rate", 0), RATES.length)];
        int best = OutputDevices.bestRate(device, current);
        if (best == current) {
            message(OutputDevices.label(device) + " elegida · acepta " + OutputDevices.rates(device) + " · se aplica al iniciar el audio");
            return;
        }
        // A rate mismatch makes Android resample and leave the low-latency path: match it.
        int index = best == 48000 ? 1 : 0;
        prefs.edit().putInt("rate", index).apply();
        if (rateChoice != null) rateChoice.setSelection(index, false);
        message(OutputDevices.label(device) + " trabaja a " + best + " Hz: reinicio el motor a " + best + " Hz");
        if (ready && firmwareFile().isFile() && !audio.hasLiveSession()) boot(null, firmwareName);
    }

    private void refreshPorts() {
        if (midiChoice == null || midi == null) return;
        ports = midi.choices();
        List<String> names = new ArrayList<>();
        for (MidiInput.Choice p : ports) names.add(p.toString());
        if (names.isEmpty()) names.add("Sin puertos MIDI — conectá el teclado USB");
        midiChoice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
    }

    private void copyDiagnostics() {
        ClipboardManager clip = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clip == null) return;
        clip.setPrimaryClip(ClipData.newPlainText("RiOsTIrus diagnóstico",
                "RiOsTIrus " + VERSION + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                        + "\n" + bootInfo + "\n" + stats.getText() + "\n" + midiStatus.getText() + "\n" + audio.diagnosticReport()));
        message("Diagnóstico copiado. Podés pegarlo en el chat; no contiene el firmware.");
    }

    // ---------------------------------------------------------------- helpers

    private void work(String label, Job job) {
        if (busy) { message("Esperá a que termine la operación en curso."); return; }
        busy = true; message(label); controls();
        worker.execute(() -> {
            try { job.run(); }
            catch (Exception | LinkageError e) {
                String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                post(() -> message(text));
            } finally {
                busy = false; post(() -> { controls(); updateDisplay(); });
            }
        });
    }

    private void post(Runnable r) { if (!destroyed) ui.post(r); }
    private void message(String text) { if (status != null) status.setText(text == null ? "" : text); }
    private static int bounded(int value, int count) { return Math.max(0, Math.min(count - 1, value)); }
}
