package com.rinoize.riostirus;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** RiOsTIrus 0.1.0: test bench for the Virus TI engine on Android. One screen:
 * firmware boot, audio settings, CPU placement, MIDI USB, test chords and diagnostics. */
public final class MainActivity extends Activity {
    private static final int PICK_FIRMWARE = 1;
    private static final int MAX_FIRMWARE = 10 * 1024 * 1024;
    private static final int BG = 0xff101014, PANEL = 0xff1c1c24, FG = 0xffeeeeee, MUTED = 0xff9a9aa8;
    private static final int RED = 0xffff3b3b, BLUE = 0xff3b7bff;
    private static final String[] MODELS = {"Virus TI", "Virus TI2", "Virus TI Snow"};
    private static final int[] RATES = {44100, 48000};
    private static final int[] BLOCKS = {256, 512, 1024};
    private static final int[] CLOCKS = {100, 75, 50};
    private static final int[] OUTPUTS = {512, 768, 1024, 1536, 2048, 3072, 4096};
    private static final int[] CHORD4 = {48, 52, 55, 59};
    private static final int[] CHORD8 = {36, 43, 48, 52, 55, 59, 62, 67};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final RealtimeAudio audio = new RealtimeAudio();
    private final MeterWindow renderWindow = new MeterWindow();
    private final List<Button> needsEngine = new ArrayList<>();
    private SharedPreferences prefs;
    private MidiInput midi;
    private List<MidiInput.Choice> ports = new ArrayList<>();
    private TextView stats, status, engineInfo, diagnostics, midiStatus;
    private Button play, stop, loadButton, bootButton;
    private Spinner midiChoice;
    private final List<Spinner> engineSettings = new ArrayList<>();
    private volatile boolean busy, ready, visible, destroyed;
    private boolean nativeOk;
    private int program;
    private volatile int[] held = new int[0];
    private long cpuBefore, wallBefore;
    private String firmwareName = "Sin firmware", lastError = "", bootInfo = "";
    private RealtimeAudio.Session measured;

    private interface Job { void run() throws Exception; }
    private final Runnable tick = new Runnable() { public void run() {
        if (!visible || destroyed) return;
        refresh();
        ui.postDelayed(this, 1000);
    }};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("riostirus-v1", MODE_PRIVATE);
        firmwareName = prefs.getString("firmwareName", "Sin firmware");
        program = Math.max(0, Math.min(127, prefs.getInt("program", 0)));
        try { NativeBridge.nativeGetCoreInfo(); nativeOk = true; }
        catch (LinkageError e) { nativeOk = false; }
        midi = new MidiInput(this, ui, () -> ready && !busy, () -> post(this::refreshPorts));
        setContentView(buildUi());
        refreshPorts();
        if (!nativeOk) message("La biblioteca nativa no cargó. Este APK necesita ARM64.");
        else if (firmwareFile().isFile()) boot(null, firmwareName);
        else message("Cargá el firmware del Virus TI (.bin del OS, 6 a 9 MiB).");
        controls();
    }

    @Override protected void onResume() { super.onResume(); visible = true; ui.post(tick); }
    @Override protected void onPause() {
        visible = false; ui.removeCallbacks(tick);
        if (audio.hasLiveSession()) work("Deteniendo audio…", this::stopWorker);
        super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed = true; ui.removeCallbacksAndMessages(null);
        if (midi != null) midi.close();
        worker.execute(() -> {
            try { audio.stop(); } catch (Exception ignored) {}
            try { if (nativeOk) NativeBridge.nativeRelease(); } catch (RuntimeException | LinkageError ignored) {}
        });
        worker.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout root = column(); root.setBackgroundColor(BG); root.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout header = row();
        TextView title = text("RiOsTIrus", 22, RED); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); header.addView(title);
        header.addView(text("  Virus TI · banco de pruebas · 0.1.0 α", 12, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        play = button("▶ INICIAR", RED, v -> startAudio()); stop = button("■ DETENER", BLUE, v -> stopAudio());
        header.addView(play); header.addView(stop); header.addView(button("PANIC", 0xffff7787, v -> panic()));
        root.addView(header);
        stats = text("Motor detenido", 12, MUTED); root.addView(stats);
        status = text("", 13, FG); root.addView(status);

        ScrollView scroll = new ScrollView(this); LinearLayout content = column(); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout firmware = row();
        loadButton = button("CARGAR FIRMWARE .BIN", RED, v -> pickFirmware()); firmware.addView(loadButton);
        bootButton = button("REINICIAR MOTOR", BLUE, v -> boot(null, firmwareName)); firmware.addView(bootButton);
        engineInfo = text("  " + firmwareName, 13, FG); firmware.addView(engineInfo, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(firmware);
        content.addView(text("El firmware es el archivo del sistema operativo del Virus TI (el mismo que usa OsTIrus en la PC). El mismo archivo sirve para TI, TI2 y Snow: el modelo se elige abajo.", 11, MUTED));

        LinearLayout engine = row();
        engineSettings.add(setting(engine, "Modelo", MODELS, "model", 0));
        engineSettings.add(setting(engine, "Frecuencia", new String[]{"44100 Hz", "48000 Hz"}, "rate", 0));
        content.addView(engine);
        content.addView(text("Modelo y frecuencia se aplican con REINICIAR MOTOR. Snow usa un solo DSP: es el plan B si el TI no llega a tiempo real.", 11, MUTED));

        LinearLayout audioRow = row();
        engineSettings.add(setting(audioRow, "Buffer motor", new String[]{"256", "512", "1024"}, "block", 1));
        engineSettings.add(setting(audioRow, "Reloj DSP", new String[]{"100%", "75%", "50%"}, "clock", 0));
        engineSettings.add(setting(audioRow, "Modo", new String[]{"Conservador", "Baja latencia + AUTO"}, "mode", 1));
        engineSettings.add(setting(audioRow, "Buffer salida", new String[]{"512", "768", "1024", "1536", "2048", "3072", "4096"}, "output", 3));
        content.addView(audioRow);
        LinearLayout cores = row();
        engineSettings.add(setting(cores, "Núcleos (hilos DSP)", new String[]{"Automático (Android decide)",
                "Rendimiento: núcleos rápidos", "Repartido: un núcleo rápido por DSP"}, "cpu", RealtimeAudio.CPU_SPREAD));
        content.addView(cores);
        content.addView(text("Se aplican al detener e iniciar. Si «DSP DSP A» o «DSP DSP B» se acercan a 100% de un núcleo, ese DSP no llega a tiempo real. El microcontrolador corre dentro del hilo DSP A.", 11, MUTED));

        LinearLayout test = row();
        Button down = button("PROGRAMA −", BLUE, v -> programChange(-1)); test.addView(down); needsEngine.add(down);
        Button up = button("PROGRAMA +", BLUE, v -> programChange(1)); test.addView(up); needsEngine.add(up);
        Button c4 = button("ACORDE 4", RED, v -> chord(CHORD4)); test.addView(c4); needsEngine.add(c4);
        Button c8 = button("ACORDE 8", RED, v -> chord(CHORD8)); test.addView(c8); needsEngine.add(c8);
        Button off = button("SOLTAR", BLUE, v -> release()); test.addView(off); needsEngine.add(off);
        content.addView(test);
        content.addView(text("Prueba sin teclado: ACORDE 4/8 deja las notas sostenidas en el canal 1 hasta SOLTAR. Con 8 voces se ve la carga máxima de un patch.", 11, MUTED));

        LinearLayout connection = row();
        midiChoice = new Spinner(this); connection.addView(midiChoice, new LinearLayout.LayoutParams(0, dp(44), 1));
        connection.addView(button("CONECTAR MIDI", BLUE, v -> {
            if (ports.isEmpty()) { message("No hay puertos MIDI disponibles"); return; }
            midi.connect(ports.get(Math.max(0, midiChoice.getSelectedItemPosition())));
        }));
        connection.addView(button("DESCONECTAR", BLUE, v -> midi.disconnect()));
        content.addView(connection);
        midiStatus = text(midi.status, 12, MUTED); content.addView(midiStatus);

        content.addView(button("COPIAR DIAGNÓSTICO", BLUE, v -> copyDiagnostics()));
        diagnostics = text("", 12, MUTED); content.addView(diagnostics);
        return root;
    }

    private Spinner setting(LinearLayout parent, String label, String[] choices, String key, int fallback) {
        LinearLayout box = column(); box.addView(text(label, 12, FG));
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
        spinner.setSelection(bounded(prefs.getInt(key, fallback), choices.length));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int p, long id) { prefs.edit().putInt(key, p).apply(); }
            public void onNothingSelected(AdapterView<?> a) {}
        });
        box.addView(spinner, new LinearLayout.LayoutParams(-1, dp(42)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(0, 0, dp(8), 0);
        parent.addView(box, params);
        return spinner;
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
        work("Arrancando " + MODELS[model] + "… (puede tardar unos segundos)", () -> {
            stopWorker();
            ready = false;
            byte[] rom = uri != null ? AppFiles.read(this, uri, MAX_FIRMWARE) : AppFiles.read(firmwareFile(), MAX_FIRMWARE);
            long before = SystemClock.elapsedRealtime();
            String info = NativeBridge.nativeLoadRom(rom, name, model, rate);
            long ms = SystemClock.elapsedRealtime() - before;
            if (uri != null) { AppFiles.atomic(firmwareFile(), rom); prefs.edit().putString("firmwareName", name).apply(); }
            ready = true;
            bootInfo = info + " | arranque " + ms + " ms";
            post(() -> { firmwareName = name; engineInfo.setText("  " + name); message(bootInfo); });
        });
    }

    private void startAudio() {
        if (!ready) { message("Primero cargá el firmware."); return; }
        final int frames = BLOCKS[bounded(prefs.getInt("block", 1), BLOCKS.length)];
        final int clock = CLOCKS[bounded(prefs.getInt("clock", 0), CLOCKS.length)];
        final int mode = bounded(prefs.getInt("mode", 1), 2);
        final int output = OUTPUTS[bounded(prefs.getInt("output", 3), OUTPUTS.length)];
        final int cpu = bounded(prefs.getInt("cpu", RealtimeAudio.CPU_SPREAD), 3);
        work("Iniciando audio…", () -> {
            if (!visible) return;
            audio.start(frames, clock, -6, mode, output, cpu);
            lastError = "";
            post(() -> message("Audio activo. Tocá desde el teclado MIDI o con ACORDE 4/8."));
        });
    }

    private void stopAudio() { work("Deteniendo audio…", () -> { stopWorker(); post(() -> message("Audio detenido")); }); }

    /** Worker thread only. */
    private void stopWorker() throws Exception {
        audio.stop();
        try { RuntimeBridge.panic(); } catch (RuntimeException ignored) {}
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

    private void send(int status, int a, int b) {
        if (!ready) return;
        try { NativeBridge.nativeSendShort(status, a, b); } catch (RuntimeException e) { message(e.getMessage()); }
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
                stats.setText(String.format(Locale.ROOT, "Render %.1f%%  ·  CPU app %.1f%%  ·  OV %d · %.1f OV/s  ·  underruns %d  ·  %d Hz",
                        window[0], appCpu, totals[4], window[1], session.underruns, session.sampleRate));
                RealtimeAudio.OutputState s = session.output;
                diagnostics.setText(session.configuration + "\nSalida: " + s.effectiveFrames + " frames / capacidad " + s.capacityFrames
                        + " · " + RealtimeAudio.performanceName(s.performanceMode) + "\n" + s.route
                        + (session.monitorNote.isEmpty() ? "" : "\n" + session.monitorNote) + "\n" + audio.healthSummary()
                        + "\n" + session.placementSummary + (session.placement.isEmpty() ? "" : "\n" + session.placement));
            } else {
                stats.setText(String.format(Locale.ROOT, "%s  ·  CPU app %.1f%%", busy ? "Preparando motor…" : "Motor detenido", appCpu));
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
                "RiOsTIrus 0.1.0 · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
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
                busy = false; post(this::controls);
            }
        });
    }

    private void post(Runnable r) { if (!destroyed) ui.post(r); }
    private void message(String text) { if (status != null) status.setText(text == null ? "" : text); }
    private static int bounded(int value, int count) { return Math.max(0, Math.min(count - 1, value)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setPadding(0, dp(4), 0, dp(4)); return layout;
    }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout;
    }
    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(color); return view;
    }
    private Button button(String label, int color, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(label); button.setTextColor(FG); button.setTextSize(13);
        button.setAllCaps(false);
        GradientDrawable shape = new GradientDrawable(); shape.setColor(PANEL); shape.setStroke(dp(1), color);
        shape.setCornerRadius(dp(6)); button.setBackground(shape);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(44)); params.setMargins(0, 0, dp(6), 0);
        button.setLayoutParams(params);
        return button;
    }
}
