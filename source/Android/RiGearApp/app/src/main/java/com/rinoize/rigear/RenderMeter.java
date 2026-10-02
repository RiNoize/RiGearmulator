package com.rinoize.rigear;

/** Render wall time, not CPU time. A block is late when it exceeds its audio duration. */
public final class RenderMeter {
    private long blocks, frames, nanos, maxNanos, overruns;
    public synchronized void record(long elapsedNanos, int frameCount, int sampleRate) {
        if (elapsedNanos < 0 || frameCount <= 0 || sampleRate <= 0)
            throw new IllegalArgumentException("Invalid render measurement");
        ++blocks; frames += frameCount; nanos += elapsedNanos;
        maxNanos = Math.max(maxNanos, elapsedNanos);
        if (elapsedNanos > frameCount * 1_000_000_000L / sampleRate) ++overruns;
    }
    public synchronized void reset() { blocks = frames = nanos = maxNanos = overruns = 0; }
    public synchronized long[] snapshot() { return new long[]{blocks, frames, nanos, maxNanos, overruns}; }
}
