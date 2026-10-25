package com.rinoize.rigear;

/** Per-session estimate at AudioTrack's playback head, NOT end-to-end latency.
 * Includes every sample accepted by write(), including startup silence.
 * A reset/backward jump invalidates the estimate until a new session is created.
 *
 * accepted() has exactly one writer: the realtime audio thread. The monitor thread
 * only reads the volatile total, so diagnostics never take a lock in the audio path.
 */
public final class PlaybackQueueMeter {
    private volatile long acceptedSamples;
    private long wraps, previousHead;
    private boolean initialized, invalid;

    public void accepted(int sampleCount) {
        if (sampleCount < 0) throw new IllegalArgumentException("Negative write count");
        acceptedSamples += sampleCount;
    }
    public long writtenFrames() { return acceptedSamples / 2; }
    public long pending(int signedHeadPosition) {
        long head = Integer.toUnsignedLong(signedHeadPosition);
        if (initialized && head < previousHead) {
            if (previousHead > 0xf0000000L && head < 0x10000000L) wraps += 0x100000000L;
            else invalid = true;
        }
        previousHead = head; initialized = true;
        long played = wraps + head;
        long written = writtenFrames();
        if (played > written) invalid = true;
        return invalid ? -1 : written - played;
    }

    /** One conservative hardware-burst step. Growth is monotonic and never exceeds capacity. */
    public static int nextAdaptiveBufferSize(int currentFrames, int capacityFrames, int quantumFrames) {
        if (currentFrames <= 0 || capacityFrames <= 0 || quantumFrames <= 0 || currentFrames > capacityFrames)
            throw new IllegalArgumentException("Invalid adaptive buffer values");
        if (currentFrames == capacityFrames) return currentFrames;
        long raw = (long)currentFrames + quantumFrames;
        long rounded = ((raw + quantumFrames - 1L) / quantumFrames) * quantumFrames;
        return (int)Math.min(capacityFrames, rounded);
    }
}
