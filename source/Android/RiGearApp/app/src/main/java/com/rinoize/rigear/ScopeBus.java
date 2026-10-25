package com.rinoize.rigear;

import java.util.Arrays;

/**
 * Lock-free, allocation-free oscilloscope tap shared by the realtime audio thread
 * and the simulated LCD. The audio thread is the only writer; the UI is read-only.
 */
public final class ScopeBus {
    public static final int POINTS = 160;
    private static final float[] A = new float[POINTS];
    private static final float[] B = new float[POINTS];
    private static volatile float[] visible = A;
    private static float[] writable = B;
    private static int divider;
    public static volatile boolean active;
    public static volatile int revision;

    private ScopeBus() {}

    /** Called only by RiGear-Audio after a complete interleaved stereo block exists. */
    public static void publish(float[] interleaved, int frames) {
        if (interleaved == null || frames <= 0) return;
        // About 20–25 captures/second at 256 frames: enough for the display and
        // deliberately much less work than the audio block rate.
        if (++divider < 8) return;
        divider = 0;
        final int available = Math.min(frames, interleaved.length / 2);
        if (available <= 0) return;
        final float[] target = writable;
        for (int x = 0; x < POINTS; ++x) {
            int from = x * available / POINTS;
            int to = Math.max(from + 1, (x + 1) * available / POINTS);
            to = Math.min(to, available);
            float chosen = 0f, peak = -1f;
            for (int i = from; i < to; ++i) {
                float sample = (interleaved[i * 2] + interleaved[i * 2 + 1]) * 0.5f;
                float amplitude = Math.abs(sample);
                if (amplitude > peak) { peak = amplitude; chosen = sample; }
            }
            target[x] = chosen;
        }
        float[] oldVisible = visible;
        visible = target;
        writable = oldVisible;
        ++revision;
    }

    public static float[] snapshot() { return visible; }

    public static void setActive(boolean value) {
        active = value;
        if (!value) divider = 0;
    }

    public static void clear() {
        Arrays.fill(A, 0f);
        Arrays.fill(B, 0f);
        ++revision;
    }
}
