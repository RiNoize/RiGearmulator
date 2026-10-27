package com.rinoize.rixenia;

/** Render wall time, not CPU time. A block is late when it exceeds its audio duration. */
public final class RenderMeter {
    private long blocks, frames, nanos, maxNanos, overruns;
    private long windowFrames, windowNanos, windowMax, windowOverruns;
    public synchronized void record(long elapsedNanos, int frameCount, int sampleRate) {
        if (elapsedNanos < 0 || frameCount <= 0 || sampleRate <= 0)
            throw new IllegalArgumentException("Invalid render measurement");
        ++blocks; frames += frameCount; nanos += elapsedNanos;
        maxNanos = Math.max(maxNanos, elapsedNanos);
        windowFrames += frameCount; windowNanos += elapsedNanos;
        windowMax = Math.max(windowMax, elapsedNanos);
        if (elapsedNanos > frameCount * 1_000_000_000L / sampleRate) { ++overruns; ++windowOverruns; }
    }
    public synchronized void reset() {
        blocks = frames = nanos = maxNanos = overruns = 0;
        windowFrames = windowNanos = windowMax = windowOverruns = 0;
    }
    /** One monitor consumer; caller owns the reusable array. Does not reset lifetime meters. */
    public synchronized void drainWindow(long[] out) {
        if (out == null || out.length < 4) throw new IllegalArgumentException("Four window slots required");
        out[0] = windowFrames; out[1] = windowNanos; out[2] = windowMax; out[3] = windowOverruns;
        windowFrames = windowNanos = windowMax = windowOverruns = 0;
    }
    public synchronized long[] snapshot() { return new long[]{blocks, frames, nanos, maxNanos, overruns}; }
}
