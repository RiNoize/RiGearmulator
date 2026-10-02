package com.rinoize.rigear;

import android.content.Context;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Handler;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Android MIDI output ports are the hardware's outputs, hence this app's inputs. */
public final class MidiInput implements AutoCloseable {
    public static final class Choice {
        final MidiDeviceInfo info; final int port; final String name;
        Choice(MidiDeviceInfo info, int port, String name) { this.info = info; this.port = port; this.name = name; }
        @Override public String toString() { return name; }
    }
    private final MidiManager manager;
    private final Handler handler;
    private final BooleanSupplier enabled;
    private final Runnable changed;
    private MidiDevice device;
    private MidiOutputPort port;
    private Choice selected;
    private volatile int generation;
    private boolean closed;
    public String status = "MIDI desconectado";
    private final MidiManager.DeviceCallback callback = new MidiManager.DeviceCallback() {
        @Override public void onDeviceAdded(MidiDeviceInfo info) { changed.run(); }
        @Override public void onDeviceRemoved(MidiDeviceInfo info) {
            if (selected != null && selected.info.getId() == info.getId()) disconnect();
            changed.run();
        }
    };
    public MidiInput(Context context, Handler handler, BooleanSupplier enabled, Runnable changed) {
        this.manager = (MidiManager) context.getSystemService(Context.MIDI_SERVICE);
        this.handler = handler; this.enabled = enabled; this.changed = changed;
        if (manager != null) manager.registerDeviceCallback(callback, handler);
        else status = "Android MIDI no disponible";
    }
    public List<Choice> choices() {
        List<Choice> list = new ArrayList<>();
        if (manager == null || closed) return list;
        for (MidiDeviceInfo info : manager.getDevices()) {
            String name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
            if (name == null || name.isEmpty()) name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_PRODUCT);
            if (name == null || name.isEmpty()) name = "MIDI " + info.getId();
            for (MidiDeviceInfo.PortInfo p : info.getPorts()) {
                if (p.getType() != MidiDeviceInfo.PortInfo.TYPE_OUTPUT) continue;
                String label = p.getName();
                list.add(new Choice(info, p.getPortNumber(), name + " / " +
                        (label == null || label.isEmpty() ? "Out " + p.getPortNumber() : label)));
            }
        }
        return list;
    }
    public void connect(Choice choice) {
        if (manager == null || choice == null || closed) return;
        disconnect();
        final int token = ++generation;
        status = "Abriendo " + choice.name;
        manager.openDevice(choice.info, opened -> {
            if (closed || token != generation) { safeClose(opened); return; }
            if (opened == null) { status = "No se pudo abrir MIDI"; return; }
            MidiOutputPort output = opened.openOutputPort(choice.port);
            if (output == null) { safeClose(opened); status = "Puerto MIDI no disponible"; return; }
            device = opened; port = output; selected = choice;
            NativeBridge.nativeResetMidiStats();
            output.connect(new MidiReceiver() {
                @Override public void onSend(byte[] data, int offset, int count, long timestamp) {
                    if (token != generation || !enabled.getAsBoolean()) return;
                    NativeBridge.nativeSendMidiBytes(data, offset, count);
                }
            });
            status = "MIDI: " + choice.name + " (usar canal 1)";
        }, handler);
    }
    public void disconnect() {
        ++generation;
        MidiOutputPort old = port; port = null; selected = null;
        try { if (old != null) old.close(); } catch (Exception ignored) {}
        safeClose(device); device = null;
        if (enabled.getAsBoolean()) RuntimeBridge.panic();
        status = "MIDI desconectado";
    }
    private static void safeClose(MidiDevice device) {
        try { if (device != null) device.close(); } catch (Exception ignored) {}
    }
    @Override public void close() {
        closed = true; disconnect();
        if (manager != null) manager.unregisterDeviceCallback(callback);
    }
}
