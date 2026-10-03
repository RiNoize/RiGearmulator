package com.rinoize.rigear;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 0.9: optional 48 kHz output, with independent Virus and playback time domains. */
public class MainActivity extends Activity {
    private static final int PICK_ROM = 1001;
    private static final ExecutorService ENGINE = Executors.newSingleThreadExecutor();
    private static final int[] BUFFERS = {256, 512, 1024};
    private static final int[] OUTPUTS = {256, 512, 768, 1024, 1536, 2048, 4096};
    private static final int[] CLOCKS = {50, 75, 100, 125, 150, 200};
    private static final int[] EXTRA_FRAMES = {0, 128, 256, 512, 1024, 2048, 4096, 8192};
    private static final int[] GAINS = {0, -6, -12, -18, -24};
    private static final int[] RATES = {0, 48000};
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final RealtimeAudio audio = new RealtimeAudio();
    private final MeterWindow meterWindow = new MeterWindow();
    private MidiInput midi;
    private final List<Button> keys = new ArrayList<>();
    private final List<Binding> bindings = new ArrayList<>();
    private final List<LinearLayout> pages = new ArrayList<>();
    private final List<MidiInput.Choice> midiChoices = new ArrayList<>();
    private TextView status, monitor, patch, midiStatus, configuration, queueView;
    private Button load, start, stop, panic, bankPrev, bankNext, prev, next, sync;
    private Spinner bufferChoice, outputChoice, modeChoice, clockChoice, latencyChoice, gainChoice, midiChoice, rateChoice;
    private volatile boolean visible, destroyed, ready, nativeReady, midiGate;
    private boolean busy;
    private int bankCount, bank, program, lastPanelRevision = -1;
    private long cpuTime, wallTime;
    private RealtimeAudio.Session measuredSession;
    private double cpuPercent, renderPercent, ovRate;
    private int underrunBase;
    private String problem = "";

    private static final class Spec {
        final String name; final int page, index, min, max;
        Spec(String name, int index) { this(name, 0x70, index, 0, 127); }
        Spec(String name, int page, int index, int min, int max) {
            this.name = name; this.page = page; this.index = index; this.min = min; this.max = max;
        }
    }
    private static final Spec[][] SPECS = {
        {
            new Spec("OSC1 SHAPE", 17), new Spec("OSC1 PW", 18),
            new Spec("OSC2 SHAPE", 22), new Spec("OSC2 DETUNE", 26),
            new Spec("FM AMOUNT", 27), new Spec("OSC BALANCE", 33),
            new Spec("SUB LEVEL", 34), new Spec("NOISE LEVEL", 37),
            new Spec("CUTOFF 1", 40), new Spec("RESONANCE 1", 42),
            new Spec("CUTOFF 2", 41), new Spec("RESONANCE 2", 43),
            new Spec("FILTER BAL", 48), new Spec("FILTER ENV", 44),
            new Spec("PAN", 10), new Spec("PATCH VOL", 91),
            new Spec("OSC3 MODE", 0x71, 41, 0, 67), new Spec("OSC3 LEVEL", 0x71, 42, 0, 127)
        },
        {
            new Spec("F ATTACK", 54), new Spec("F DECAY", 55), new Spec("F SUSTAIN", 56),
            new Spec("F RELEASE", 58), new Spec("LFO1 RATE", 67), new Spec("LFO2 RATE", 79),
            new Spec("A ATTACK", 59), new Spec("A DECAY", 60), new Spec("A SUSTAIN", 61),
            new Spec("A RELEASE", 63), new Spec("LFO1 OSC1", 74), new Spec("LFO2 CUTOFF", 88),
            new Spec("ARP MODE", 0x71, 1, 0, 6), new Spec("ARP PATTERN", 0x71, 2, 0, 63),
            new Spec("ARP OCTAVES", 0x71, 3, 0, 3), new Spec("ARP HOLD", 0x71, 4, 0, 1),
            new Spec("CLOCK TEMPO", 0x71, 16, 0, 127), new Spec("PORTAMENTO", 5)
        },
        {
            new Spec("UNISON MODE", 0x70, 97, 0, 15), new Spec("UNI DETUNE", 98),
            new Spec("UNI SPREAD", 99), new Spec("KEY MODE", 0x70, 94, 0, 5),
            new Spec("OSC2 SYNC", 0x70, 28, 0, 1), new Spec("PUNCH", 0x71, 36, 0, 127),
            new Spec("CHORUS MIX", 105), new Spec("CHORUS RATE", 106),
            new Spec("CHORUS DEPTH", 107), new Spec("CHORUS DELAY", 108),
            new Spec("CHORUS FB", 109), new Spec("CTRL SMOOTH", 0x71, 25, 0, 3),
            new Spec("DLY/REV MODE", 0x70, 112, 0, 26), new Spec("FX SEND", 113),
            new Spec("DELAY TIME", 114), new Spec("DELAY FB", 115),
            new Spec("RATE/DECAY", 116), new Spec("DELAY COLOR", 119)
        }
    };
    private static final class Binding {
        final Spec spec; final ControlKnob knob;
        Binding(Spec spec, ControlKnob knob) { this.spec = spec; this.knob = knob; }
    }
    private interface Work { void run() throws Exception; }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (visible) {
                try { refreshMonitor(); refreshPanel(); controls(); }
                catch (RuntimeException | LinkageError error) { status.setText("Diagnostico: " + error.getMessage()); }
                ui.postDelayed(this, 500);
            }
        }
    };
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = column();
        root.setBackgroundColor(0xff121417); root.setPadding(dp(10), dp(4), dp(10), dp(4));
        TextView title = text("RiGear 0.9 | OSIRUS / SALIDA 48 kHz", 18);
        title.setTextColor(0xffffb34d); root.addView(title);
        status = text("Inicializando JNI...", 11); root.addView(status);
        monitor = text("CPU / Render / OV / Underruns", 12); root.addView(monitor);
        queueView = text("Cola estimada: — (no es latencia total)", 12); root.addView(queueView);
        configuration = text("", 10); root.addView(configuration);
        LinearLayout actions = row();
        load = button("ROM", v -> chooseRom()); start = button("START", v -> startAudio());
        stop = button("STOP", v -> stopAudio());
        panic = button("PANIC", v -> {
            if (nativeReady) RuntimeBridge.panic();
            if (midi != null) midi.telemetry.reset(false);
            clearKeys();
        });
        for (Button b : new Button[]{load, start, stop, panic,
                button("RESET METERS", v -> resetMeters()), button("AUDIO INFO", v -> audioInfo())}) actions.addView(b);
        root.addView(actions);
        LinearLayout settings = row();
        bufferChoice = spinner(new String[]{"256", "512", "1024"}, 1);
        rateChoice = spinner(new String[]{"Nativa", "48000"}, 1);
        modeChoice = spinner(new String[]{"Conservador", "Baja latencia"}, 0);
        outputChoice = spinner(new String[]{"256", "512", "768", "1024", "1536", "2048", "4096"}, 3);
        latencyChoice = spinner(new String[]{"0", "128", "256", "512", "1024", "2048", "4096", "8192"}, 3);
        clockChoice = spinner(new String[]{"50%", "75%", "100%", "125%", "150%", "200%"}, 2);
        gainChoice = spinner(new String[]{"0 dB", "-6 dB", "-12 dB", "-18 dB", "-24 dB"}, 1);
        addSetting(settings, "Motor (fr Virus)", bufferChoice);
        addSetting(settings, "Hz salida", rateChoice);
        addSetting(settings, "Modo salida", modeChoice);
        addSetting(settings, "Salida (fr)", outputChoice);
        addSetting(settings, "Extra DSP (fr)", latencyChoice);
        addSetting(settings, "Clock DSP", clockChoice);
        addSetting(settings, "Ganancia", gainChoice);
        root.addView(settings);
        root.addView(text("STOP -> START. Nativa = ruta 0.8; 48000 = conversion C++ (Virus no cambia de frecuencia).", 10));
        LinearLayout browser = row();
        bankPrev = button("BANK -", v -> moveBank(-1)); bankNext = button("BANK +", v -> moveBank(1));
        prev = button("PATCH -", v -> movePatch(-1)); next = button("PATCH +", v -> movePatch(1));
        patch = text("Cargar ROM / ZIP", 14);
        browser.addView(bankPrev); browser.addView(bankNext);
        browser.addView(patch, new LinearLayout.LayoutParams(0, -2, 1));
        browser.addView(prev); browser.addView(next); root.addView(browser);
        LinearLayout tabs = row();
        String[] tabNames = {"OSC / FILTER", "ENV / LFO / ARP", "FX / UNISON"};
        for (int i = 0; i < tabNames.length; ++i) {
            final int tab = i; tabs.addView(button(tabNames[i], v -> showPage(tab)));
        }
        sync = button("SYNC", v -> { if (ready) RuntimeBridge.requestPanel(); });
        tabs.addView(sync); root.addView(tabs);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setKeepScreenOn(true);
        LinearLayout panel = column();
        for (Spec[] specs : SPECS) {
            LinearLayout page = column();
            for (int r = 0; r < specs.length / 6; ++r) {
                LinearLayout line = row();
                for (int c = 0; c < 6; ++c) {
                    Spec spec = specs[r * 6 + c];
                    ControlKnob knob = new ControlKnob(this, spec.name, spec.min, spec.max,
                            value -> { if (ready && !busy) RuntimeBridge.setParameter(spec.page, spec.index, value); });
                    line.addView(knob, new LinearLayout.LayoutParams(0, dp(107), 1));
                    bindings.add(new Binding(spec, knob));
                }
                page.addView(line);
            }
            pages.add(page); panel.addView(page);
        }
        panel.addView(text("Valores nativos del Virus. UNISON MODE: 0 = apagado. SYNC requiere audio corriendo.", 11));
        scroll.addView(panel); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout midiRow = row(); midiChoice = new Spinner(this);
        midiRow.addView(midiChoice, new LinearLayout.LayoutParams(0, -2, 1));
        midiRow.addView(button("REFRESH MIDI", v -> refreshMidiChoices()));
        midiRow.addView(button("CONNECT", v -> {
            Object selected = midiChoice.getSelectedItem();
            if (midi != null && selected instanceof MidiInput.Choice) midi.connect((MidiInput.Choice) selected);
        }));
        midiRow.addView(button("DISCONNECT", v -> { if (midi != null) midi.disconnect(); }));
        root.addView(midiRow);
        midiStatus = text("USB MIDI", 11); root.addView(midiStatus);
        LinearLayout keyboard = row(); keyboard.setMotionEventSplittingEnabled(true);
        String[] labels = {"C3", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B", "C4"};
        for (int i = 0; i < labels.length; ++i) {
            final int note = 60 + i; Button key = button(labels[i], null);
            key.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && midiGate && audio.isRunning()) {
                    view.setPressed(true); NativeBridge.nativeNoteOn(note, 100); return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    if (view.isPressed() && nativeReady) NativeBridge.nativeNoteOff(note);
                    view.setPressed(false);
                }
                return true;
            });
            keyboard.addView(key, new LinearLayout.LayoutParams(0, dp(36), 1)); keys.add(key);
        }
        root.addView(keyboard); setContentView(root); showPage(0);
        modeChoice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { controls(); }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        try {
            nativeReady = NativeBridge.nativeSelfTest() == 1;
            status.setText("JNI conectado | pantalla encendida mientras RiGear es visible");
            midi = new MidiInput(this, ui, () -> midiGate && audio.isRunning() && !destroyed, this::refreshMidiChoices);
            refreshMidiChoices();
        } catch (RuntimeException | LinkageError error) { status.setText("Carga nativa: " + error.getMessage()); }
        controls();
    }
    private void work(String label, Work action) {
        if (busy || destroyed || !nativeReady) return;
        busy = true; status.setText(label); controls();
        ENGINE.execute(() -> {
            try { if (!destroyed) action.run(); }
            catch (Exception | LinkageError error) {
                ui.post(() -> { if (!destroyed) { problem = error.toString(); status.setText(problem); } });
            } finally { ui.post(() -> { if (!destroyed) { busy = false; controls(); } }); }
        });
    }
    private void chooseRom() {
        midiGate = false;
        work("Deteniendo audio para importar ROM...", () -> {
            audio.stop();
            ui.post(() -> {
                if (!destroyed && visible) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
                    startActivityForResult(intent, PICK_ROM);
                }
            });
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_ROM || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        final int frames = chosen(BUFFERS, bufferChoice), clock = chosen(CLOCKS, clockChoice);
        final int extra = chosen(EXTRA_FRAMES, latencyChoice);
        ready = false; bankCount = 0;
        work("Leyendo firmware y bancos...", () -> {
            audio.stop();
            RomImporter.Bundle bundle = RomImporter.read(getApplicationContext(), uri);
            String loaded = NativeBridge.nativeLoadRomBundle(bundle.data, bundle.names);
            if (loaded == null || !loaded.contains("DSP BOOT: OK")) throw new IllegalStateException(loaded);
            String config = RuntimeBridge.prepare(frames, clock, extra);
            int banks = NativeBridge.nativeGetPatchBankCount();
            String name = banks > 0 ? NativeBridge.nativeGetPatchName(0, 0) : "Sin bancos en ROM";
            ui.post(() -> {
                if (destroyed) return;
                ready = true; bankCount = banks; bank = 0; program = 0; lastPanelRevision = -1;
                problem = ""; status.setText("ROM lista. START para tocar.");
                configuration.setText(config); showPatch(name); refreshPanel();
                if (midi != null) midi.telemetry.reset(false);
            });
        });
    }
    private void startAudio() {
        final int frames = chosen(BUFFERS, bufferChoice), clock = chosen(CLOCKS, clockChoice);
        final int extra = chosen(EXTRA_FRAMES, latencyChoice), gain = chosen(GAINS, gainChoice);
        final int mode = modeChoice.getSelectedItemPosition(), target = chosen(OUTPUTS, outputChoice);
        final int rate = chosen(RATES, rateChoice);
        midiGate = false;
        if (midi != null) midi.telemetry.reset(false);
        work("Preparando audio Release...", () -> {
            if (!visible || !ready) return;
            audio.start(frames, clock, extra, gain, mode, target, rate);
            if (!visible || destroyed) { audio.stop(); return; }
            midiGate = true;
            ui.post(() -> { if (!destroyed) { problem = ""; status.setText("Audio iniciado. Canal MIDI 1."); } });
        });
    }
    private void stopAudio() {
        midiGate = false; clearKeys();
        work("Deteniendo...", () -> {
            audio.stop();
            ui.post(() -> { if (!destroyed) status.setText("Audio detenido; ultima medicion conservada."); });
        });
    }
    private void moveBank(int delta) {
        if (bankCount <= 0 || busy) return;
        bank = (bank + delta + bankCount) % bankCount; selectPatch();
    }
    private void movePatch(int delta) {
        if (bankCount <= 0 || busy) return;
        int n = ((bank * 128 + program + delta) % (bankCount * 128) + bankCount * 128) % (bankCount * 128);
        bank = n / 128; program = n % 128; selectPatch();
    }
    private void selectPatch() {
        final int b = bank, p = program;
        final int frames = chosen(BUFFERS, bufferChoice), clock = chosen(CLOCKS, clockChoice);
        final int extra = chosen(EXTRA_FRAMES, latencyChoice);
        work("Cambiando patch...", () -> {
            String name = NativeBridge.nativeSelectPatch(b, p);
            // Do not reset SRC while a session is warming up (not yet playing).
            if (!audio.hasLiveSession()) RuntimeBridge.prepare(frames, clock, extra);
            RuntimeBridge.requestPanel();
            ui.post(() -> {
                if (!destroyed) { showPatch(name); lastPanelRevision = -1; status.setText("Patch seleccionado"); refreshPanel(); }
            });
        });
    }
    private void showPatch(String name) {
        patch.setText(String.format(Locale.US, "%c %03d  %s", 'A' + bank, program + 1, name == null ? "" : name.trim()));
    }
    private void refreshPanel() {
        if (!ready || !nativeReady || busy) return;
        int[] values = RuntimeBridge.panel();
        if (values == null || values.length != 257 || values[0] == lastPanelRevision) return;
        lastPanelRevision = values[0];
        for (Binding binding : bindings)
            binding.knob.showValue(values[1 + binding.spec.index + (binding.spec.page == 0x71 ? 128 : 0)]);
    }
    private void refreshMonitor() {
        long now = SystemClock.elapsedRealtime(), cpu = Process.getElapsedCpuTime();
        if (wallTime != 0 && now > wallTime) cpuPercent = 100.0 * (cpu - cpuTime) / (now - wallTime);
        wallTime = now; cpuTime = cpu;
        RealtimeAudio.Session s = audio.session();
        if (s != measuredSession) {
            measuredSession = s; meterWindow.reset(); underrunBase = 0; renderPercent = ovRate = 0;
        }
        if (s != null) {
            long[] m = s.meter.snapshot();
            if (!s.finished) {
                double[] window = meterWindow.sample(System.nanoTime(), m, s.deviceSampleRate);
                renderPercent = window[0]; ovRate = window[1];
            } else ovRate = 0;
            double budgetMs = 1000.0 * s.frames / s.deviceSampleRate;
            double peak = 100.0 * m[3] / (budgetMs * 1e6);
            long[] signal = nativeReady ? RuntimeBridge.signalStats(false) : new long[]{0, 0, 0};
            monitor.setText(String.format(Locale.US,
                    "CPU app %.1f%% (100%% = 1 nucleo) | Render %.1f%% / max %.1f%%\nOV %d | OV/s %.1f | Underruns %d | Peak %.3f | Clips %d | NaN/Inf %d",
                    cpuPercent, renderPercent, peak, m[4], ovRate, Math.max(0, s.underruns - underrunBase),
                    Float.intBitsToFloat((int)signal[2]), signal[0], signal[1]));
            RealtimeAudio.OutputState o = s.output;
            boolean stale = !s.finished && o.measuredNanos > 0 && System.nanoTime() - o.measuredNanos > 1_000_000_000L;
            String pending = o.queuedFrames < 0 ? "no disponible" : String.format(Locale.US,
                    "~%d fr / %.1f ms", o.queuedFrames, o.queuedFrames * 1000.0 / s.sampleRate);
            queueView.setText("Pendiente " + pending + (s.finished ? " [ultima]" : stale ? " [dato viejo]" : "") +
                    " | Android: " + RealtimeAudio.performanceName(o.performanceMode) + " | no es latencia total");
            String request = s.outputMode == RealtimeAudio.CONSERVATIVE ? "Conservador" : "Pedido " + s.requestedOutputFrames;
            String conversion = s.resampling ? String.format(Locale.US,
                    " | SRC %.3f ms (ultimo) / max %.3f ms", s.srcNanos/1e6, s.srcMaxNanos/1e6) : " | SRC bypass (0.8)";
            configuration.setText(s.configuration + String.format(Locale.US,
                    "\nVirus %d Hz -> AudioTrack %d Hz | Motor %d (%.2f ms)\nSalida %s -> efectiva %d / capacidad %d fr",
                    s.deviceSampleRate, s.sampleRate, s.frames, budgetMs, request, o.effectiveFrames, o.capacityFrames) + conversion);
            if (!s.error.isEmpty()) { problem = s.error; status.setText(problem); midiGate = false; }
            monitor.setTextColor(ovRate > 0 || s.underruns > underrunBase ? 0xffffbd69 : Color.LTGRAY);
        } else monitor.setText(String.format(Locale.US, "CPU app %.1f%% | Render — | OV 0 | OV/s 0 | Underruns 0", cpuPercent));
        if (midi != null) {
            long[] t = midi.telemetry.snapshot();
            midiStatus.setText(midi.status + String.format(Locale.US,
                    "\nTeclas USB presionadas: %d | Sustain canal 1: %s | Note On %d / Off %d",
                    t[3], (t[4] & 1) != 0 ? "ON" : "OFF", t[1], t[2]));
        }
    }
    private void resetMeters() {
        RealtimeAudio.Session s = audio.session();
        if (s != null) { s.meter.reset(); underrunBase = s.underruns; }
        meterWindow.reset(); renderPercent = ovRate = 0;
        if (nativeReady) RuntimeBridge.signalStats(true);
        status.setText("Medidores reiniciados. Cola, fase SRC y sustain no se reinician.");
    }
    private void audioInfo() {
        RealtimeAudio.Session s = audio.session();
        String info = "Cola estimada = frames aceptados por AudioTrack menos su posicion de reproduccion.\n" +
                "No es latencia total de MIDI, DSP, mezclador, DAC o Bluetooth.\n" +
                "48000 convierte audio en C++ sin modificar el clock ni la afinacion del Virus.\n" +
                "No se garantiza LOW_LATENCY ni se aumenta automaticamente el buffer por OV.\n\n";
        if (s != null) {
            RealtimeAudio.OutputState o = s.output;
            info += s.configuration + "\nVirus: " + s.deviceSampleRate + " Hz\nAudioTrack: " + s.sampleRate + " Hz\n" +
                    (s.resampling ? "SRC: FIR 96 taps / 128 fases / razon 128:125\nRetardo teorico FIR: 1.013 ms\n" : "SRC: desactivado (ruta nativa 0.8)\n") +
                    "Ruta: " + o.route + "\nModo concedido: " + RealtimeAudio.performanceName(o.performanceMode) +
                    "\nBuffer efectivo/capacidad: " + o.effectiveFrames + "/" + o.capacityFrames +
                    "\nUmbral de arranque API: " + (o.startThreshold < 0 ? "no disponible" : o.startThreshold + " frames salida") +
                    "\nFrames aceptados (incluye precarga): " + o.writtenFrames + "\n";
            if (s.resampling) info += String.format(Locale.US,
                    "SRC computo: %.3f ms ultimo / %.3f ms max\nSRC entrada/salida acumulados: %d / %d (incluyen warm-up)\n",
                    s.srcNanos/1e6, s.srcMaxNanos/1e6, s.srcInputFrames, s.srcOutputFrames);
            info += "\n";
        }
        AudioManager manager = (AudioManager)getSystemService(AUDIO_SERVICE);
        if (manager != null) info += "Propiedades orientativas Android:\nSample rate: " +
                manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) + "\nFrames/buffer: " +
                manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
        ScrollView scroll = new ScrollView(this);
        TextView detail = text(info, 13); detail.setGravity(Gravity.START); detail.setPadding(dp(16),dp(12),dp(16),dp(12));
        scroll.addView(detail);
        new AlertDialog.Builder(this).setTitle("RiGear 0.9 / Audio info").setView(scroll)
                .setPositiveButton("CERRAR", null).show();
    }
    private void controls() {
        RealtimeAudio.Session s = audio.session();
        boolean active = s != null && !s.finished && !s.stopRequested;
        load.setEnabled(nativeReady && !busy); start.setEnabled(ready && !busy && !active);
        stop.setEnabled(!busy && active); panic.setEnabled(ready && !busy);
        for (Spinner choice : new Spinner[]{bufferChoice, rateChoice, modeChoice, clockChoice, latencyChoice, gainChoice}) choice.setEnabled(!busy && !active);
        outputChoice.setEnabled(!busy && !active && modeChoice.getSelectedItemPosition() == RealtimeAudio.LOW_LATENCY);
        for (Button b : new Button[]{bankPrev, bankNext, prev, next}) b.setEnabled(ready && !busy && bankCount > 0);
        sync.setEnabled(ready && !busy && active);
        for (Binding binding : bindings) binding.knob.setEnabled(ready && !busy);
        for (Button key : keys) key.setEnabled(midiGate && audio.isRunning() && !busy);
    }
    private void refreshMidiChoices() {
        if (midi == null || destroyed) return;
        midiChoices.clear(); midiChoices.addAll(midi.choices());
        ArrayAdapter<MidiInput.Choice> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, midiChoices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); midiChoice.setAdapter(adapter);
    }
    private void clearKeys() { for (Button key : keys) key.setPressed(false); }
    private void showPage(int selected) {
        for (int i = 0; i < pages.size(); ++i) pages.get(i).setVisibility(i == selected ? View.VISIBLE : View.GONE);
        lastPanelRevision = -1;
    }
    private int chosen(int[] choices, Spinner spinner) { return choices[Math.max(0, Math.min(choices.length - 1, spinner.getSelectedItemPosition()))]; }
    private Spinner spinner(String[] values, int selection) {
        Spinner s = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(adapter); s.setSelection(selection); return s;
    }
    private void addSetting(LinearLayout parent, String name, Spinner spinner) {
        LinearLayout setting = column(); setting.addView(text(name, 10)); setting.addView(spinner);
        parent.addView(setting, new LinearLayout.LayoutParams(0, -2, 1));
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setGravity(Gravity.CENTER); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView text(String value, int size) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(Color.LTGRAY); v.setGravity(Gravity.CENTER); return v;
    }
    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(label); b.setTextSize(10); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(6), dp(3), dp(6), dp(3));
        if (listener != null) b.setOnClickListener(listener); return b;
    }
    @Override protected void onStart() {
        super.onStart(); visible = true; getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        wallTime = cpuTime = 0; meterWindow.reset(); ui.removeCallbacks(tick); ui.post(tick);
    }
    @Override protected void onStop() {
        visible = false; midiGate = false; clearKeys(); ui.removeCallbacks(tick);
        ENGINE.execute(() -> {
            try { audio.stop(); }
            catch (Exception error) { ui.post(() -> { if (!destroyed) status.setText(error.toString()); }); }
        });
        super.onStop();
    }
    @Override protected void onDestroy() {
        destroyed = true; visible = false; midiGate = false; ui.removeCallbacks(tick);
        if (midi != null) midi.close();
        ENGINE.execute(() -> {
            try { audio.stop(); if (nativeReady) NativeBridge.nativeRelease(); }
            catch (Exception error) { android.util.Log.e("RiGear", "Engine could not be safely released", error); }
        });
        super.onDestroy();
    }
}
