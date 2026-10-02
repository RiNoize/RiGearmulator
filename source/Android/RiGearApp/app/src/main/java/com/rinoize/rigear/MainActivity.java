package com.rinoize.rigear;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
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

    private final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor();
    private final List<Button> keyboardButtons = new ArrayList<>();
    private final List<MidiPortChoice> midiChoices = new ArrayList<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private TextView detailsView;
    private TextView midiStatsView;
    private Button loadRomButton;
    private Button selfTestButton;
    private Button startAudioButton;
    private Button stopAudioButton;
    private Button panicButton;
    private Button refreshMidiButton;
    private Button connectMidiButton;
    private Button disconnectMidiButton;
    private Spinner midiSpinner;

    private volatile boolean deviceReady = false;
    private volatile boolean audioRunning = false;
    private volatile boolean destroyed = false;

    private Thread audioThread;
    private AudioTrack realtimeTrack;

    private MidiManager midiManager;
    private MidiDevice midiDevice;
    private MidiOutputPort midiOutputPort;
    private MidiPortChoice connectedChoice;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        midiManager = (MidiManager) getSystemService(MIDI_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(18, 10, 18, 10);

        TextView title = new TextView(this);
        title.setText("RiGear 0.5 USB MIDI Test");
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);

        statusView = new TextView(this);
        statusView.setTextSize(17f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 6, 0, 3);

        detailsView = new TextView(this);
        detailsView.setTextSize(12f);
        detailsView.setGravity(Gravity.CENTER);
        detailsView.setPadding(0, 0, 0, 5);

        loadRomButton = new Button(this);
        loadRomButton.setText("LOAD VIRUS ROM");
        loadRomButton.setOnClickListener(v -> pickRom());

        LinearLayout audioControls = new LinearLayout(this);
        audioControls.setOrientation(LinearLayout.HORIZONTAL);
        audioControls.setGravity(Gravity.CENTER);

        startAudioButton = new Button(this);
        startAudioButton.setText("START AUDIO");
        startAudioButton.setEnabled(false);
        startAudioButton.setOnClickListener(v -> startRealtimeAudio());

        stopAudioButton = new Button(this);
        stopAudioButton.setText("STOP");
        stopAudioButton.setEnabled(false);
        stopAudioButton.setOnClickListener(v -> stopRealtimeAudio(true));

        panicButton = new Button(this);
        panicButton.setText("PANIC");
        panicButton.setEnabled(false);
        panicButton.setOnClickListener(v -> panic());

        audioControls.addView(startAudioButton);
        audioControls.addView(stopAudioButton);
        audioControls.addView(panicButton);

        LinearLayout midiControls = new LinearLayout(this);
        midiControls.setOrientation(LinearLayout.HORIZONTAL);
        midiControls.setGravity(Gravity.CENTER);

        midiSpinner = new Spinner(this);
        ArrayAdapter<MidiPortChoice> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                midiChoices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        midiSpinner.setAdapter(adapter);

        refreshMidiButton = new Button(this);
        refreshMidiButton.setText("REFRESH MIDI");
        refreshMidiButton.setOnClickListener(v -> refreshMidiPorts());

        connectMidiButton = new Button(this);
        connectMidiButton.setText("CONNECT");
        connectMidiButton.setEnabled(false);
        connectMidiButton.setOnClickListener(v -> connectSelectedMidi());

        disconnectMidiButton = new Button(this);
        disconnectMidiButton.setText("DISCONNECT");
        disconnectMidiButton.setEnabled(false);
        disconnectMidiButton.setOnClickListener(v -> closeMidiConnection(true));

        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f);

        midiControls.addView(midiSpinner, spinnerParams);
        midiControls.addView(refreshMidiButton);
        midiControls.addView(connectMidiButton);
        midiControls.addView(disconnectMidiButton);

        midiStatsView = new TextView(this);
        midiStatsView.setText("USB MIDI: disconnected");
        midiStatsView.setTextSize(13f);
        midiStatsView.setGravity(Gravity.CENTER);
        midiStatsView.setPadding(0, 2, 0, 4);

        LinearLayout keyboard = new LinearLayout(this);
        keyboard.setOrientation(LinearLayout.HORIZONTAL);
        keyboard.setGravity(Gravity.CENTER);
        keyboard.setPadding(0, 4, 0, 4);

        final String[] labels = {
                "C3", "C#3", "D3", "D#3", "E3", "F3", "F#3",
                "G3", "G#3", "A3", "A#3", "B3", "C4"
        };

        for (int i = 0; i < labels.length; ++i) {
            final int midiNote = 60 + i;
            Button key = new Button(this);
            key.setText(labels[i]);
            key.setTextSize(10f);
            key.setEnabled(false);
            key.setPadding(0, 0, 0, 0);
            key.setOnTouchListener((v, event) -> handleKeyTouch(v, event, midiNote));

            LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1.0f);
            keyboard.addView(key, keyParams);
            keyboardButtons.add(key);
        }

        selfTestButton = new Button(this);
        selfTestButton.setText("SELF TEST");
        selfTestButton.setOnClickListener(v -> runNativeSelfTest());

        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(detailsView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(loadRomButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(audioControls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(midiControls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(midiStatsView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(keyboard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(selfTestButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        if (midiManager != null) {
            midiManager.registerDeviceCallback(midiDeviceCallback, uiHandler);
        }

        runNativeSelfTest();
        refreshMidiPorts();
        updateControlState(false);
        uiHandler.post(midiStatsUpdater);
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
            final String info = NativeBridge.nativeGetCoreInfo();
            final int result = NativeBridge.nativeSelfTest();

            if (result == 1) {
                statusView.setText("ARM64 CORE: OK   JNI: OK");
            } else {
                statusView.setText("NATIVE SELF TEST: FAILED");
            }
            detailsView.setText(info);
        } catch (Throwable t) {
            statusView.setText("NATIVE LOAD: FAILED");
            detailsView.setText(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void refreshMidiPorts() {
        if (midiManager == null || destroyed)
            return;

        final MidiPortChoice previous = getSelectedMidiChoice();

        midiChoices.clear();

        MidiDeviceInfo[] devices = midiManager.getDevices();
        for (MidiDeviceInfo info : devices) {
            String deviceName = buildMidiDeviceName(info);

            for (MidiDeviceInfo.PortInfo port : info.getPorts()) {
                // To receive MIDI from external hardware, the app opens an
                // OUTPUT port exposed by that MIDI device.
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

        updateControlState(false);

        if (midiChoices.isEmpty() && midiOutputPort == null)
            midiStatsView.setText("USB MIDI: no input ports detected");
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

        final MidiPortChoice choice = getSelectedMidiChoice();
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

        final Uri uri = data.getData();
        if (uri == null)
            return;

        deviceReady = false;
        updateControlState(true);

        final String selectedName = getDisplayName(uri);
        statusView.setText("ROM: " + selectedName + "\nInspecting firmware...");
        detailsView.setText("ZIP, MIDI OS update and raw BIN are accepted.");

        nativeExecutor.execute(() -> {
            try {
                byte[] selectedData = readUri(uri, MAX_ARCHIVE_UNCOMPRESSED);
                List<Candidate> candidates = extractCandidates(selectedName, selectedData);

                if (candidates.isEmpty())
                    throw new IllegalArgumentException(
                            "No .mid, .midi or .bin firmware candidate was found.");

                String lastResult = "No valid Virus firmware found.";
                String successResult = null;
                String successName = null;

                for (Candidate candidate : candidates) {
                    final String result = NativeBridge.nativeLoadRom(
                            candidate.data,
                            candidate.name);

                    lastResult = result;

                    if (result != null && result.contains("DSP BOOT: OK")) {
                        successResult = result;
                        successName = candidate.name;
                        break;
                    }
                }

                final String finalSuccessResult = successResult;
                final String finalSuccessName = successName;
                final String finalLastResult = lastResult;
                final int count = candidates.size();

                runOnUiThread(() -> {
                    statusView.setText("RiGear / OSIRUS");

                    if (finalSuccessResult != null) {
                        deviceReady = true;
                        String prefix = selectedName.toLowerCase(Locale.ROOT).endsWith(".zip")
                                ? "Using: " + finalSuccessName + "\n"
                                : "";
                        detailsView.setText(prefix + finalSuccessResult +
                                "\nStart audio, then use virtual or USB MIDI.");
                    } else {
                        deviceReady = false;
                        detailsView.setText(
                                "Tried " + count + " firmware candidate(s).\n\n" +
                                finalLastResult);
                    }

                    updateControlState(false);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    deviceReady = false;
                    statusView.setText("ROM LOAD: FAILED");
                    detailsView.setText(t.getClass().getSimpleName() + ": " + t.getMessage());
                    updateControlState(false);
                });
            }
        });
    }

    private synchronized void startRealtimeAudio() {
        if (!deviceReady || audioRunning)
            return;

        stopRealtimeAudio(false);

        try {
            final int sampleRate = NativeBridge.nativeGetDeviceSampleRate();
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

            statusView.setText("REALTIME AUDIO: RUNNING");
            detailsView.setText(
                    "Virus DSP: " + sampleRate + " Hz   Block: " +
                    AUDIO_BLOCK_FRAMES + " frames\n" +
                    "MIDI IN: Omni   USB events enter the native realtime queue.");
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
        final float[] block = new float[AUDIO_BLOCK_FRAMES * 2];

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

                    if (written == 0)
                        continue;

                    offset += written;
                }
            }
        } catch (Throwable t) {
            final String error = t.getClass().getSimpleName() + ": " + t.getMessage();
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
        final boolean wasRunning = audioRunning;
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
            statusView.setText("REALTIME AUDIO: STOPPED");
            detailsView.setText("AudioTrack underruns: " + underruns +
                    "\n" + NativeBridge.nativeGetMidiStats());
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

        statusView.setText("PANIC: ALL NOTES OFF");
        midiStatsView.setText(NativeBridge.nativeGetMidiStats());
    }

    private void updateControlState(boolean busy) {
        loadRomButton.setEnabled(!busy);
        selfTestButton.setEnabled(!busy && !audioRunning);
        startAudioButton.setEnabled(!busy && deviceReady && !audioRunning);
        stopAudioButton.setEnabled(!busy && audioRunning);
        panicButton.setEnabled(!busy && audioRunning);

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
