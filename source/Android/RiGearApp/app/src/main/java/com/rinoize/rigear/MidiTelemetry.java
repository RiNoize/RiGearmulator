package com.rinoize.rigear;

import java.util.Arrays;

/** USB MIDI reception diagnostics only. Does not count sounding DSP voices.
 * Runs on the MIDI receiver, never on the audio worker. Supports split messages,
 * running status and interspersed realtime bytes; SysEx is skipped for diagnostics.
 */
public final class MidiTelemetry {
    private final boolean[] keys = new boolean[16 * 128];
    private long messages, ons, offs;
    private int down, sustainMask, status, needed, count, first;
    private boolean sysex;

    public synchronized void reset(boolean counters) {
        Arrays.fill(keys, false); down = sustainMask = status = needed = count = first = 0;
        sysex = false;
        if (counters) messages = ons = offs = 0;
    }
    public synchronized void accept(byte[] bytes, int offset, int length) {
        if (bytes == null || offset < 0 || length < 0 || offset > bytes.length - length)
            throw new IllegalArgumentException("Invalid MIDI range");
        for (int i = offset; i < offset + length; ++i) {
            int b = bytes[i] & 255;
            if (b >= 0xf8) continue;
            if ((b & 128) != 0) {
                count = 0;
                if (b >= 0xf0) {
                    status = needed = 0; sysex = b == 0xf0; continue;
                }
                sysex = false; status = b;
                int type = b & 0xf0;
                needed = type == 0xc0 || type == 0xd0 ? 1 : 2;
                continue;
            }
            if (sysex || status == 0 || needed == 0) continue;
            if (count == 0) first = b;
            if (++count == needed) {
                message(status, first, needed == 2 ? b : 0); count = 0;
            }
        }
    }
    private void message(int status, int a, int b) {
        ++messages;
        int channel = status & 15, type = status & 0xf0, key = channel * 128 + a;
        if (type == 0x90 && b != 0) {
            ++ons;
            if (!keys[key]) { keys[key] = true; ++down; }
        } else if (type == 0x80 || (type == 0x90 && b == 0)) {
            ++offs;
            if (keys[key]) { keys[key] = false; --down; }
        } else if (type == 0xb0) {
            if (a == 64) {
                if (b >= 64) sustainMask |= 1 << channel;
                else sustainMask &= ~(1 << channel);
            }
            if (a == 121) sustainMask &= ~(1 << channel);
            if (a == 120 || a == 123) {
                for (int note = channel * 128; note < channel * 128 + 128; ++note)
                    if (keys[note]) { keys[note] = false; --down; }
            }
        }
    }
    // messages, note ons, note offs, USB key states, sustain mask (bit 0 = channel 1)
    public synchronized long[] snapshot() { return new long[]{messages, ons, offs, down, sustainMask}; }
}
