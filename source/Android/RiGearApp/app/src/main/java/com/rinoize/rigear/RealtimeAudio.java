package com.rinoize.rigear;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;

/** One AudioTrack and one native render caller per session. Control calls use a serial executor. */
public final class RealtimeAudio {
    public static final class Session {
        public final int frames, sampleRate, bufferFrames;
        public final String configuration;
        public final RenderMeter meter = new RenderMeter();
        private final AudioTrack track;
        private Thread worker;
        public volatile boolean stopRequested, playing, finished;
        public volatile int underruns;
        public volatile String error = "";
        private Session(AudioTrack track, int frames, int sampleRate, String configuration) {
            this.track = track; this.frames = frames; this.sampleRate = sampleRate;
            this.configuration = configuration;
            this.bufferFrames = track.getBufferSizeInFrames();
        }
    }
    private volatile Session current;
    public Session session() { return current; }
    public boolean isRunning() {
        Session s = current;
        return s != null && s.playing && !s.stopRequested && !s.finished;
    }
    public void start(int frames, int clockPercent, int extraBlocks, int gainDb) throws Exception {
        stop();
        RuntimeBridge.setGainDb(gainDb);
        String configuration = RuntimeBridge.prepare(frames, clockPercent, extraBlocks);
        int sampleRate = NativeBridge.nativeGetDeviceSampleRate();
        if (sampleRate <= 0) throw new IllegalStateException("No device sample rate");
        int minBytes = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
        if (minBytes <= 0) throw new IllegalStateException("Unsupported AudioTrack rate: " + sampleRate);
        int capacityBytes = Math.max(minBytes * 2, frames * 2 * Float.BYTES * 4);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(capacityBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release(); throw new IllegalStateException("AudioTrack initialization failed");
        }
        Session s = new Session(track, frames, sampleRate, configuration);
        s.worker = new Thread(() -> run(s), "RiGear-Audio");
        current = s;
        try { s.worker.start(); }
        catch (RuntimeException error) {
            s.finished = true; track.release(); throw error;
        }
    }
    private void run(Session s) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            float[] block = new float[s.frames * 2];
            // Allocate scratch/JIT paths before playback. Boot/preparation is not counted as OV.
            for (int i = 0; i < 4 && !s.stopRequested; ++i) {
                if (RuntimeBridge.render(block, s.frames) != s.frames)
                    throw new IllegalStateException("Native warm-up failed");
            }
            if (s.stopRequested) return;
            float[] silence = new float[s.bufferFrames * 2];
            s.track.write(silence, 0, silence.length, AudioTrack.WRITE_NON_BLOCKING);
            s.meter.reset();
            s.track.play(); s.playing = true;
            while (!s.stopRequested) {
                long start = System.nanoTime();
                int rendered = RuntimeBridge.render(block, s.frames);
                long elapsed = System.nanoTime() - start;
                s.meter.record(elapsed, s.frames, s.sampleRate);
                if (rendered != s.frames) throw new IllegalStateException("Incomplete native block");
                int offset = 0, zeroWrites = 0;
                while (!s.stopRequested && offset < block.length) {
                    int n = s.track.write(block, offset, block.length - offset, AudioTrack.WRITE_BLOCKING);
                    if (n < 0) {
                        if (s.stopRequested) break;
                        throw new IllegalStateException("AudioTrack write error: " + n);
                    }
                    if (n == 0) {
                        if (++zeroWrites > 100) throw new IllegalStateException("AudioTrack made no progress");
                        Thread.sleep(1);
                    } else { offset += n; zeroWrites = 0; }
                }
                s.underruns = s.track.getUnderrunCount();
            }
        } catch (Exception | LinkageError error) {
            if (!s.stopRequested) s.error = error.getClass().getSimpleName() + ": " + error.getMessage();
        } finally {
            s.playing = false;
            try { s.underruns = s.track.getUnderrunCount(); } catch (RuntimeException ignored) {}
            try { s.track.pause(); s.track.flush(); } catch (RuntimeException ignored) {}
            s.track.release(); // Only its own worker releases this session's track.
            s.finished = true;
        }
    }
    /** Called off the UI thread. Never release/reuse a native engine whose worker did not stop. */
    public void stop() throws InterruptedException {
        Session s = current;
        if (s == null || s.finished) return;
        s.stopRequested = true;
        // Unblock a write if the output device stopped consuming audio.
        try { s.track.pause(); s.track.flush(); } catch (RuntimeException ignored) {}
        s.worker.join(3000);
        if (s.worker.isAlive())
            throw new IllegalStateException("Audio worker did not stop. Close and reopen RiGear before restarting.");
    }
}
