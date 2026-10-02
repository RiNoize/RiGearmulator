package com.rinoize.rigear;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final int PICK_ROM = 1001;
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ARCHIVE_UNCOMPRESSED = 64 * 1024 * 1024;
    private static final int AUDIO_BLOCK_FRAMES = 256;

    private static final ParameterSpec[] PARAMETERS = {
            new ParameterSpec("OSC1 SHAPE", 17),
            new ParameterSpec("OSC2 SHAPE", 22),
            new ParameterSpec("DETUNE", 26),
            new ParameterSpec("OSC BAL", 33),
            new ParameterSpec("CUTOFF", 40),
            new ParameterSpec("RESONANCE", 42),

            new ParameterSpec("F ENV AMT", 44),
            new ParameterSpec("F ATTACK", 54),
            new ParameterSpec("F DECAY", 55),
            new ParameterSpec("F SUSTAIN", 56),
            new ParameterSpec("F RELEASE", 58),
            new ParameterSpec("LFO1 RATE", 67),

            new ParameterSpec("A ATTACK", 59),
            new ParameterSpec("A DECAY", 60),
            new ParameterSpec("A SUSTAIN", 61),
            new ParameterSpec("A RELEASE", 63),
            new ParameterSpec("PATCH VOL", 91),
            new ParameterSpec("CHORUS MIX", 105)
    };

    private final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor();
    private final List<Button> keyboardButtons = new ArrayList<>();
    private final List<MidiPortChoice> midiChoices = new ArrayList<>();
    private final List<KnobBinding> knobBindings = new ArrayList<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private TextView detailsView;
    private TextView midiStatsView;
    private TextView patchView;

    private Button loadRomButton;
    private Button selfTestButton;
    private Button startAudioButton;
    private Button stopAudioButton;
    private Button panicButton;
    private Button refreshMidiButton;
    private Button connectMidiButton;
    private Button disconnectMidiButton;

    private Button bankPrevButton;
    private Button bankNextButton;
    private Button patchPrevButton;
    private Button patchNextButton;

    private Spinner midiSpinner;

    private volatile boolean deviceReady = false;
    private volatile boolean audioRunning = false;
    private volatile boolean destroyed = false;

    private int patchBankCount = 0;
    private int currentBank = 0;
    private int currentProgram = 0;

    private Thread audioThread;
    private AudioTrack realtimeTrack;

    private MidiManager midiManager;
    private MidiDevice midiDevice;
    private MidiOutputPort midiOutputPort;
    private MidiPortChoice connectedChoice;

    private static final class ParameterSpec {
        final String label;
        final int cc;

        ParameterSpec(String label, int cc) {
            this.label = label;
            this.cc = cc;
        }
    }

    private static final class KnobBinding {
        final ParameterSpec spec;
        final KnobView knob;

        KnobBinding(ParameterSpec spec, KnobView knob) {
            this.spec = spec;
            this.knob = knob;
        }
    }

    private static final class Candidate {
        final String name;
        final byte[] data;

        Candidate(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    private static final class MidiPortChoice {
        final MidiDeviceInfo deviceInfo;
        final int portNumber;
        final String label;

        MidiPortChoice(MidiDeviceInfo deviceInfo, int portNumber, String label) {
            this.deviceInfo = deviceInfo;
            this.portNumber = portNumber;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final MidiReceiver midiReceiver = new MidiReceiver() {
        @Override
        public void onSend(byte[] data, int offset, int count, long timestamp) {
            if (!audioRunning || !deviceReady || destroyed)
                return;

            NativeBridge.nativeSendMidiBytes(data, offset, count);
        }
    };

    private final Runnable midiStatsUpdater = new Runnable() {
        @Override
        public void run() {
            if (destroyed)
                return;

            if (midiStatsView != null) {
                if (midiOutputPort != null) {
                    midiStatsView.setText(NativeBridge.nativeGetMidiStats());
                } else {
                    midiStatsView.setText("USB MIDI: disconnected");
                }
            }

            uiHandler.postDelayed(this, 250);
        }
    };

    private final MidiManager.DeviceCallback midiDeviceCallback =
            new MidiManager.DeviceCallback() {
        @Override
        public void onDeviceAdded(MidiDeviceInfo device) {
            refreshMidiPorts();
        }

        @Override
        public void onDeviceRemoved(MidiDeviceInfo device) {
            if (connectedChoice != null &&
                    connectedChoice.deviceInfo.getId() == device.getId()) {
                closeMidiConnection(true);
            }
            refreshMidiPorts();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Critical for realtime audio stability on the tablet: while RiGear is
        // visible, Android must not dim or switch the display off.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        midiManager = (MidiManager) getSystemService(MIDI_SERVICE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setKeepScreenOn(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(14), dp(8), dp(14), dp(12));
        root.setBackgroundColor(Color.rgb(18, 19, 21));

        TextView title = makeText("RiGear 0.6  |  VIRUS PERFORMANCE", 23, true);
        title.setTextColor(Color.rgb(226, 159, 55));

        statusView = makeText("ARM64 CORE", 14, true);
        detailsView = makeText("", 11, false);

        LinearLayout topActions = horizontalRow();

        loadRomButton = compactButton("LOAD ROM");
        loadRomButton.setOnClickListener(v -> pickRom());

        startAudioButton = compactButton("START AUDIO");
        startAudioButton.setOnClickListener(v -> startRealtimeAudio());

        stopAudioButton = compactButton("STOP");
        stopAudioButton.setOnClickListener(v -> stopRealtimeAudio(true));

        panicButton = compactButton("PANIC");
        panicButton.setOnClickListener(v -> panic());

        topActions.addView(loadRomButton);
        topActions.addView(startAudioButton);
        topActions.addView(stopAudioButton);
        topActions.addView(panicButton);

        LinearLayout browser = horizontalRow();
        browser.setPadding(0, dp(3), 0, dp(3));

        bankPrevButton = compactButton("BANK -");
        bankPrevButton.setOnClickListener(v -> moveBank(-1));

        bankNextButton = compactButton("BANK +");
        bankNextButton.setOnClickListener(v -> moveBank(1));

        patchPrevButton = compactButton("PATCH -");
        patchPrevButton.setOnClickListener(v -> movePatch(-1));

        patchNextButton = compactButton("PATCH +");
        patchNextButton.setOnClickListener(v -> movePatch(1));

        patchView = makeText("PATCH BROWSER: load a ROM", 17, true);
        patchView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams patchTextParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f);

        browser.addView(bankPrevButton);
        browser.addView(bankNextButton);
        browser.addView(patchView, patchTextParams);
        browser.addView(patchPrevButton);
        browser.addView(patchNextButton);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(3), 0, dp(3));

        for (int row = 0; row < 3; ++row) {
            LinearLayout knobRow = horizontalRow();
            knobRow.setGravity(Gravity.CENTER);

            for (int col = 0; col < 6; ++col) {
                int index = row * 6 + col;
                ParameterSpec spec = PARAMETERS[index];

                KnobView knob = new KnobView(this);
                knob.setLabel(spec.label);
                knob.setEnabled(false);
                knob.setOnValueChangedListener((view, value, fromUser) -> {
                    if (fromUser && deviceReady)
                        NativeBridge.nativeSetParameter(spec.cc, value);
                });

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0,
                        dp(112),
                        1.0f);

                knobRow.addView(knob, lp);
                knobBindings.add(new KnobBinding(spec, knob));
            }

            panel.addView(knobRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout midiControls = horizontalRow();

        midiSpinner = new Spinner(this);
        ArrayAdapter<MidiPortChoice> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                midiChoices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        midiSpinner.setAdapter(adapter);

        refreshMidiButton = compactButton("REFRESH MIDI");
        refreshMidiButton.setOnClickListener(v -> refreshMidiPorts());

        connectMidiButton = compactButton("CONNECT");
        connectMidiButton.setOnClickListener(v -> connectSelectedMidi());

        disconnectMidiButton = compactButton("DISCONNECT");
        disconnectMidiButton.setOnClickListener(v -> closeMidiConnection(true));

        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f);

        midiControls.addView(midiSpinner, spinnerParams);
        midiControls.addView(refreshMidiButton);
        midiControls.addView(connectMidiButton);
        midiControls.addView(disconnectMidiButton);

        midiStatsView = makeText("USB MIDI: disconnected", 12, false);

        LinearLayout keyboard = horizontalRow();
        keyboard.setPadding(0, dp(2), 0, dp(2));

        final String[] labels = {
                "C3", "C#3", "D3", "D#3", "E3", "F3", "F#3",
                "G3", "G#3", "A3", "A#3", "B3", "C4"
        };

        for (int i = 0; i < labels.length; ++i) {
            final int midiNote = 60 + i;
            Button key = new Button(this);
            key.setText(labels[i]);
            key.setTextSize(9f);
            key.setEnabled(false);
            key.setPadding(0, 0, 0, 0);
            key.setMinWidth(0);
            key.setMinimumWidth(0);
            key.setOnTouchListener((v, event) -> handleKeyTouch(v, event, midiNote));

            LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(
                    0,
                    dp(42),
                    1.0f);

            keyboard.addView(key, keyParams);
            keyboardButtons.add(key);
        }

        selfTestButton = compactButton("DIAGNOSTIC SELF TEST");
        selfTestButton.setOnClickListener(v -> runNativeSelfTest());

        root.addView(title);
        root.addView(statusView);
        root.addView(detailsView);
        root.addView(topActions);
        root.addView(browser, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(midiControls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(midiStatsView);
        root.addView(keyboard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(selfTestButton);

        scroll.addView(root);
        setContentView(scroll);

        if (midiManager != null)
            midiManager.registerDeviceCallback(midiDeviceCallback, uiHandler);

        runNativeSelfTest();
        refreshMidiPorts();
        updateControlState(false);
        uiHandler.post(midiStatsUpdater);
    }

    private TextView makeText(String text, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(Color.LTGRAY);
        view.setGravity(Gravity.CENTER);
        if (bold)
            view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private Button compactButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(10f);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(10), dp(5), dp(10), dp(5));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean handleKeyTouch(View view, MotionEvent event, int midiNote) {
        if (!audioRunning)
            return true;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                view.setPressed(true);
                NativeBridge.nativeNoteOn(midiNote, 100);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                NativeBridge.nativeNoteOff(midiNote);
                view.setPressed(false);
                return true;

            default:
                return true;
        }
    }

    private void runNativeSelfTest() {
        try {
            String info = NativeBridge.nativeGetCoreInfo();
            int result = NativeBridge.nativeSelfTest();

            statusView.setText(result == 1
                    ? "ARM64 CORE: OK   JNI: OK   SCREEN: KEEP ON"
                    : "NATIVE SELF TEST: FAILED");
            detailsView.setText(info);
        } catch (Throwable t) {
            statusView.setText("NATIVE LOAD: FAILED");
            detailsView.setText(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void pickRom() {
        stopRealtimeAudio(false);

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_ROM);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != PICK_ROM || resultCode != RESULT_OK || data == null)
            return;

        Uri uri = data.getData();
        if (uri == null)
            return;

        deviceReady = false;
        patchBankCount = 0;
        updateControlState(true);

        String selectedName = getDisplayName(uri);
        statusView.setText("ROM: " + selectedName + "   loading...");
        detailsView.setText("Inspecting firmware and preset images inside archive.");

        nativeExecutor.execute(() -> {
            try {
                byte[] selectedData = readUri(uri, MAX_ARCHIVE_UNCOMPRESSED);
                List<Candidate> candidates = extractCandidates(selectedName, selectedData);

                if (candidates.isEmpty())
                    throw new IllegalArgumentException(
                            "No .mid, .midi or .bin file found.");

                byte[][] bundleData = new byte[candidates.size()][];
                String[] bundleNames = new String[candidates.size()];

                for (int i = 0; i < candidates.size(); ++i) {
                    bundleData[i] = candidates.get(i).data;
                    bundleNames[i] = candidates.get(i).name;
                }

                String result = NativeBridge.nativeLoadRomBundle(
                        bundleData,
                        bundleNames);

                runOnUiThread(() -> {
                    if (result != null && result.contains("DSP BOOT: OK")) {
                        deviceReady = true;
                        patchBankCount = NativeBridge.nativeGetPatchBankCount();
                        currentBank = 0;
                        currentProgram = 0;

                        statusView.setText("RiGear / OSIRUS   READY");
                        detailsView.setText(result);

                        refreshPatchDisplay(false);
                        refreshKnobsFromNative();
                    } else {
                        deviceReady = false;
                        patchBankCount = 0;
                        statusView.setText("ROM LOAD: FAILED");
                        detailsView.setText(result);
                        patchView.setText("PATCH BROWSER unavailable");
                    }

                    updateControlState(false);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    deviceReady = false;
                    patchBankCount = 0;
                    statusView.setText("ROM LOAD: FAILED");
                    detailsView.setText(t.getClass().getSimpleName() + ": " + t.getMessage());
                    updateControlState(false);
                });
            }
        });
    }

    private void moveBank(int delta) {
        if (!deviceReady || patchBankCount <= 0)
            return;

        currentBank = (currentBank + delta) % patchBankCount;
        if (currentBank < 0)
            currentBank += patchBankCount;

        selectCurrentPatch();
    }

    private void movePatch(int delta) {
        if (!deviceReady || patchBankCount <= 0)
            return;

        int p = currentProgram + delta;

        if (p < 0) {
            currentProgram = 127;
            currentBank = (currentBank - 1 + patchBankCount) % patchBankCount;
        } else if (p > 127) {
            currentProgram = 0;
            currentBank = (currentBank + 1) % patchBankCount;
        } else {
            currentProgram = p;
        }

        selectCurrentPatch();
    }

    private void selectCurrentPatch() {
        String name = NativeBridge.nativeSelectPatch(currentBank, currentProgram);
        refreshPatchDisplayWithName(name);
        refreshKnobsFromNative();
    }

    private void refreshPatchDisplay(boolean queryName) {
        if (patchBankCount <= 0) {
            patchView.setText("No preset banks in this firmware image");
            return;
        }

        String name = queryName
                ? NativeBridge.nativeGetPatchName(currentBank, currentProgram)
                : NativeBridge.nativeGetPatchName(currentBank, currentProgram);

        refreshPatchDisplayWithName(name);
    }

    private void refreshPatchDisplayWithName(String name) {
        if (name == null)
            name = "";

        char bankLetter = (char)('A' + currentBank);

        patchView.setText(String.format(
                Locale.US,
                "BANK %c   %03d   %s",
                bankLetter,
                currentProgram + 1,
                name.trim()));
    }

    private void refreshKnobsFromNative() {
        for (KnobBinding binding : knobBindings) {
            int value = NativeBridge.nativeGetParameter(binding.spec.cc);
            binding.knob.setValue(value);
        }
    }

    private synchronized void startRealtimeAudio() {
        if (!deviceReady || audioRunning)
            return;

        stopRealtimeAudio(false);

        try {
            int sampleRate = NativeBridge.nativeGetDeviceSampleRate();
            if (sampleRate <= 0)
                throw new IllegalStateException("Virus device has no valid sample rate.");

            int minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT);

            if (minBuffer <= 0)
                throw new IllegalStateException(
                        "AudioTrack rejected sample rate " + sampleRate + " Hz.");

            int blockBytes = AUDIO_BLOCK_FRAMES * 2 * Float.BYTES;
            int bufferBytes = Math.max(minBuffer * 2, blockBytes * 4);

            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();

            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                throw new IllegalStateException("AudioTrack could not initialize.");
            }

            realtimeTrack = track;
            audioRunning = true;
            NativeBridge.nativePanic();

            track.play();

            audioThread = new Thread(
                    () -> runAudioLoop(track),
                    "RiGear-Audio");
            audioThread.start();

            statusView.setText(
                    "AUDIO RUNNING   " + sampleRate +
                    " Hz   256 frames   SCREEN: KEEP ON");
            updateControlState(false);
        } catch (Throwable t) {
            audioRunning = false;
            cleanupAudioTrack();
            statusView.setText("REALTIME AUDIO: FAILED");
            detailsView.setText(t.getClass().getSimpleName() + ": " + t.getMessage());
            updateControlState(false);
        }
    }

    private void runAudioLoop(AudioTrack track) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        float[] block = new float[AUDIO_BLOCK_FRAMES * 2];

        try {
            while (audioRunning) {
                int frames = NativeBridge.nativeProcessAudio(
                        block,
                        AUDIO_BLOCK_FRAMES);

                if (frames != AUDIO_BLOCK_FRAMES)
                    throw new IllegalStateException(
                            "Native audio returned " + frames + " frames.");

                int sampleCount = frames * 2;
                int offset = 0;

                while (audioRunning && offset < sampleCount) {
                    int written = track.write(
                            block,
                            offset,
                            sampleCount - offset,
                            AudioTrack.WRITE_BLOCKING);

                    if (written < 0)
                        throw new IllegalStateException(
                                "AudioTrack write failed: " + written);

                    if (written > 0)
                        offset += written;
                }
            }
        } catch (Throwable t) {
            String error = t.getClass().getSimpleName() + ": " + t.getMessage();
            audioRunning = false;

            runOnUiThread(() -> {
                cleanupAudioTrack();
                statusView.setText("REALTIME AUDIO: STOPPED BY ERROR");
                detailsView.setText(error);
                updateControlState(false);
            });
        }
    }

    private synchronized void stopRealtimeAudio(boolean showStatus) {
        boolean wasRunning = audioRunning;
        audioRunning = false;
        NativeBridge.nativePanic();

        AudioTrack track = realtimeTrack;

        if (track != null) {
            try {
                track.pause();
                track.flush();
            } catch (Throwable ignored) {
            }
        }

        Thread thread = audioThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int underruns = 0;

        if (track != null) {
            try {
                underruns = track.getUnderrunCount();
            } catch (Throwable ignored) {
            }
        }

        cleanupAudioTrack();

        for (Button key : keyboardButtons)
            key.setPressed(false);

        if (showStatus && wasRunning) {
            statusView.setText(
                    "AUDIO STOPPED   underruns: " + underruns +
                    "   SCREEN: KEEP ON");
        }

        updateControlState(false);
    }

    private synchronized void cleanupAudioTrack() {
        AudioTrack track = realtimeTrack;
        realtimeTrack = null;
        audioThread = null;

        if (track != null) {
            try {
                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)
                    track.stop();
            } catch (Throwable ignored) {
            }

            try {
                track.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void panic() {
        NativeBridge.nativePanic();

        for (Button key : keyboardButtons)
            key.setPressed(false);

        statusView.setText("PANIC: ALL NOTES OFF   SCREEN: KEEP ON");
    }

    private void refreshMidiPorts() {
        if (midiManager == null || destroyed)
            return;

        MidiPortChoice previous = getSelectedMidiChoice();

        midiChoices.clear();

        for (MidiDeviceInfo info : midiManager.getDevices()) {
            String deviceName = buildMidiDeviceName(info);

            for (MidiDeviceInfo.PortInfo port : info.getPorts()) {
                if (port.getType() != MidiDeviceInfo.PortInfo.TYPE_OUTPUT)
                    continue;

                String portName = port.getName();
                if (portName == null || portName.isEmpty())
                    portName = "Out " + port.getPortNumber();

                midiChoices.add(new MidiPortChoice(
                        info,
                        port.getPortNumber(),
                        deviceName + " — " + portName));
            }
        }

        ArrayAdapter<MidiPortChoice> adapter =
                (ArrayAdapter<MidiPortChoice>) midiSpinner.getAdapter();
        adapter.notifyDataSetChanged();

        if (previous != null) {
            for (int i = 0; i < midiChoices.size(); ++i) {
                MidiPortChoice c = midiChoices.get(i);
                if (c.deviceInfo.getId() == previous.deviceInfo.getId() &&
                        c.portNumber == previous.portNumber) {
                    midiSpinner.setSelection(i);
                    break;
                }
            }
        }

        if (midiChoices.isEmpty() && midiOutputPort == null)
            midiStatsView.setText("USB MIDI: no input ports detected");

        updateControlState(false);
    }

    private String buildMidiDeviceName(MidiDeviceInfo info) {
        Bundle props = info.getProperties();

        String name = props.getString(MidiDeviceInfo.PROPERTY_NAME);
        String manufacturer = props.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER);
        String product = props.getString(MidiDeviceInfo.PROPERTY_PRODUCT);

        if (name != null && !name.isEmpty())
            return name;

        if (manufacturer != null && product != null)
            return manufacturer + " " + product;

        if (product != null && !product.isEmpty())
            return product;

        return "MIDI device " + info.getId();
    }

    private MidiPortChoice getSelectedMidiChoice() {
        Object selected = midiSpinner != null ? midiSpinner.getSelectedItem() : null;
        return selected instanceof MidiPortChoice ? (MidiPortChoice) selected : null;
    }

    private void connectSelectedMidi() {
        if (midiManager == null)
            return;

        MidiPortChoice choice = getSelectedMidiChoice();
        if (choice == null) {
            midiStatsView.setText("USB MIDI: select a port first");
            return;
        }

        closeMidiConnection(false);
        midiStatsView.setText("USB MIDI: opening " + choice.label + "...");

        midiManager.openDevice(
                choice.deviceInfo,
                device -> {
                    if (destroyed) {
                        if (device != null) {
                            try {
                                device.close();
                            } catch (Throwable ignored) {
                            }
                        }
                        return;
                    }

                    if (device == null) {
                        midiStatsView.setText("USB MIDI: failed to open device");
                        updateControlState(false);
                        return;
                    }

                    MidiOutputPort port = device.openOutputPort(choice.portNumber);

                    if (port == null) {
                        try {
                            device.close();
                        } catch (Throwable ignored) {
                        }

                        midiStatsView.setText("USB MIDI: failed to open output port");
                        updateControlState(false);
                        return;
                    }

                    midiDevice = device;
                    midiOutputPort = port;
                    connectedChoice = choice;
                    NativeBridge.nativeResetMidiStats();

                    port.connect(midiReceiver);

                    midiStatsView.setText("USB MIDI connected: " + choice.label);
                    updateControlState(false);
                },
                uiHandler);
    }

    private void closeMidiConnection(boolean showStatus) {
        NativeBridge.nativePanic();

        MidiOutputPort port = midiOutputPort;
        midiOutputPort = null;

        if (port != null) {
            try {
                port.disconnect(midiReceiver);
            } catch (Throwable ignored) {
            }

            try {
                port.close();
            } catch (Throwable ignored) {
            }
        }

        MidiDevice device = midiDevice;
        midiDevice = null;
        connectedChoice = null;

        if (device != null) {
            try {
                device.close();
            } catch (Throwable ignored) {
            }
        }

        NativeBridge.nativeResetMidiStats();

        if (showStatus)
            midiStatsView.setText("USB MIDI: disconnected");

        updateControlState(false);
    }

    private void updateControlState(boolean busy) {
        loadRomButton.setEnabled(!busy);
        selfTestButton.setEnabled(!busy && !audioRunning);

        startAudioButton.setEnabled(!busy && deviceReady && !audioRunning);
        stopAudioButton.setEnabled(!busy && audioRunning);
        panicButton.setEnabled(!busy && audioRunning);

        boolean browserEnabled = !busy && deviceReady && patchBankCount > 0;
        bankPrevButton.setEnabled(browserEnabled);
        bankNextButton.setEnabled(browserEnabled);
        patchPrevButton.setEnabled(browserEnabled);
        patchNextButton.setEnabled(browserEnabled);

        for (KnobBinding binding : knobBindings)
            binding.knob.setEnabled(!busy && deviceReady);

        boolean keysEnabled = !busy && audioRunning;
        for (Button key : keyboardButtons)
            key.setEnabled(keysEnabled);

        refreshMidiButton.setEnabled(!busy);
        midiSpinner.setEnabled(!busy && midiOutputPort == null);
        connectMidiButton.setEnabled(
                !busy && midiOutputPort == null && !midiChoices.isEmpty());
        disconnectMidiButton.setEnabled(!busy && midiOutputPort != null);
    }

    private List<Candidate> extractCandidates(String selectedName, byte[] selectedData)
            throws Exception {
        String lower = selectedName.toLowerCase(Locale.ROOT);
        List<Candidate> result = new ArrayList<>();

        if (!lower.endsWith(".zip")) {
            if (isFirmwareName(lower))
                result.add(new Candidate(selectedName, selectedData));
            else
                throw new IllegalArgumentException(
                        "Choose a .zip, .mid, .midi or .bin file.");

            return result;
        }

        int totalUncompressed = 0;

        try (ZipInputStream zin = new ZipInputStream(
                new ByteArrayInputStream(selectedData))) {
            ZipEntry entry;

            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zin.closeEntry();
                    continue;
                }

                String entryName = entry.getName();
                String entryLower = entryName.toLowerCase(Locale.ROOT);

                if (!isFirmwareName(entryLower)) {
                    zin.closeEntry();
                    continue;
                }

                byte[] bytes = readLimited(zin, MAX_FILE_BYTES);
                totalUncompressed += bytes.length;

                if (totalUncompressed > MAX_ARCHIVE_UNCOMPRESSED)
                    throw new IllegalArgumentException(
                            "ZIP expands beyond the 64 MB safety limit.");

                result.add(new Candidate(entryName, bytes));
                zin.closeEntry();
            }
        }

        return result;
    }

    private boolean isFirmwareName(String lowerName) {
        return lowerName.endsWith(".mid") ||
               lowerName.endsWith(".midi") ||
               lowerName.endsWith(".bin");
    }

    private byte[] readUri(Uri uri, int maxBytes) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null)
                throw new IllegalStateException(
                        "Android could not open the selected file.");

            return readLimited(in, maxBytes);
        }
    }

    private byte[] readLimited(InputStream in, int maxBytes) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int n;

            while ((n = in.read(buffer)) >= 0) {
                total += n;

                if (total > maxBytes)
                    throw new IllegalArgumentException(
                            "Selected file is larger than the safety limit.");

                out.write(buffer, 0, n);
            }

            return out.toByteArray();
        }
    }

    private String getDisplayName(Uri uri) {
        String result = "virus.bin";

        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);

                if (index >= 0) {
                    String value = cursor.getString(index);

                    if (value != null && !value.isEmpty())
                        result = value;
                }
            }
        } catch (Throwable ignored) {
        }

        return result;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        uiHandler.removeCallbacks(midiStatsUpdater);

        if (midiManager != null) {
            try {
                midiManager.unregisterDeviceCallback(midiDeviceCallback);
            } catch (Throwable ignored) {
            }
        }

        closeMidiConnection(false);
        stopRealtimeAudio(false);
        NativeBridge.nativeRelease();
        nativeExecutor.shutdown();

        super.onDestroy();
    }
}
