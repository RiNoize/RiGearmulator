package com.rinoize.rigear;

/** Per-session estimate at AudioTrack's playback head, NOT end-to-end latency.
 * Includes every sample accepted by write(), including startup silence.
 * A reset/backward jump invalidates the estimate until a new session is created.
 */
public final class PlaybackQueueMeter {
    private long acceptedSamples, wraps, previousHead;
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
        if (played > writtenFrames()) invalid = true;
        return invalid ? -1 : writtenFrames() - played;
    }
}
