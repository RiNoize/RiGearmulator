package com.rinoize.rigear;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;

/** AudioTrack output; engine blocks, effective output buffer and DSP extra frames are independent.
 * No automatic buffer growth, resampling change or dropped blocks is performed by this class.
 */
public final class RealtimeAudio {
    public static final int CONSERVATIVE = 0, LOW_LATENCY = 1;
    public static final class OutputState {
        public final int effectiveFrames, capacityFrames, performanceMode, startThreshold;
        public final long queuedFrames, writtenFrames, measuredNanos;
        public final String route;
        private OutputState(int effective, int capacity, int performance, int threshold,
                            long queued, long written, long time, String route) {
            effectiveFrames = effective; capacityFrames = capacity; performanceMode = performance;
            startThreshold = threshold; queuedFrames = queued; writtenFrames = written;
            measuredNanos = time; this.route = route;
        }
    }
    public static final class Session {
        public final int frames, sampleRate, outputMode, requestedOutputFrames, extraDspFrames;
        public final String configuration;
        public final RenderMeter meter = new RenderMeter();
        private final AudioTrack track;
        private final PlaybackQueueMeter queueMeter = new PlaybackQueueMeter();
        private Thread worker;
        public volatile boolean stopRequested, playing, finished;
        public volatile int underruns;
        public volatile String error = "";
        public volatile OutputState output;
        private Session(AudioTrack track, int frames, int rate, String config, int mode, int requested, int extra) {
            this.track = track; this.frames = frames; sampleRate = rate; configuration = config;
            outputMode = mode; requestedOutputFrames = requested; extraDspFrames = extra;
            output = new OutputState(track.getBufferSizeInFrames(), track.getBufferCapacityInFrames(),
                    track.getPerformanceMode(), threshold(track), -1, 0, 0, "Sin ruta activa");
        }
    }
    private volatile Session current;
    public Session session() { return current; }
    public boolean isRunning() {
        Session s = current;
        return s != null && s.playing && !s.stopRequested && !s.finished;
    }
    public void start(int frames, int clockPercent, int extraFrames, int gainDb,
                      int outputMode, int requestedOutputFrames) throws Exception {
        stop();
        if (outputMode != CONSERVATIVE && outputMode != LOW_LATENCY)
            throw new IllegalArgumentException("Modo de salida desconocido");
        if (requestedOutputFrames < 128 || requestedOutputFrames > 8192)
            throw new IllegalArgumentException("Buffer de salida fuera de rango");
        RuntimeBridge.setGainDb(gainDb);
        String configuration = RuntimeBridge.prepare(frames, clockPercent, extraFrames);
        int sampleRate = NativeBridge.nativeGetDeviceSampleRate();
        if (sampleRate <= 0) throw new IllegalStateException("No device sample rate");
        int minBytes = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
        if (minBytes <= 0) throw new IllegalStateException("Unsupported AudioTrack rate: " + sampleRate);
        // Preserve 0.7 sizing in conservative mode. Low-latency capacity does not depend on engine block.
        int capacityBytes = outputMode == CONSERVATIVE
                ? Math.max(minBytes * 2, frames * 8 * 4)
                : Math.max(minBytes * 2, requestedOutputFrames * 8);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(capacityBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
        try {
            if (track.getState() != AudioTrack.STATE_INITIALIZED)
                throw new IllegalStateException("AudioTrack initialization failed");
            if (outputMode == LOW_LATENCY) {
                int accepted = track.setBufferSizeInFrames(requestedOutputFrames);
                if (accepted <= 0) throw new IllegalStateException(
                        "Android rechazo el buffer: " + accepted + ". Probar modo Conservador.");
                if (Build.VERSION.SDK_INT >= 31)
                    track.setStartThresholdInFrames(track.getBufferSizeInFrames());
            }
            Session s = new Session(track, frames, sampleRate, configuration, outputMode,
                    outputMode == CONSERVATIVE ? 0 : requestedOutputFrames, extraFrames);
            s.worker = new Thread(() -> run(s), "RiGear-Audio");
            current = s;
            try { s.worker.start(); }
            catch (RuntimeException error) { s.finished = true; throw error; }
        } catch (RuntimeException error) { track.release(); throw error; }
    }
    private static int threshold(AudioTrack track) {
        // -1 means not exposed by this Android API, not a guessed threshold.
        return Build.VERSION.SDK_INT >= 31 ? track.getStartThresholdInFrames() : -1;
    }
    private void inspectOutput(Session s) {
        if (s.stopRequested) return;
        long pending = s.queueMeter.pending(s.track.getPlaybackHeadPosition());
        AudioDeviceInfo device = s.track.getRoutedDevice();
        String route = device == null ? "Ruta aun no disponible"
                : device.getProductName() + " (tipo " + device.getType() + ")";
        s.output = new OutputState(s.track.getBufferSizeInFrames(), s.track.getBufferCapacityInFrames(),
                s.track.getPerformanceMode(), threshold(s.track), pending, s.queueMeter.writtenFrames(),
                System.nanoTime(), route);
        s.underruns = s.track.getUnderrunCount();
    }
    private void run(Session s) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            float[] block = new float[s.frames * 2];
            for (int i = 0; i < 4 && !s.stopRequested; ++i)
                if (RuntimeBridge.render(block, s.frames) != s.frames)
                    throw new IllegalStateException("Native warm-up failed");
            if (s.stopRequested) return;
            // Prime only the EFFECTIVE buffer, never the larger allocation in low-latency mode.
            float[] silence = new float[s.track.getBufferSizeInFrames() * 2];
            int primed = s.track.write(silence, 0, silence.length, AudioTrack.WRITE_NON_BLOCKING);
            if (primed < 0) throw new IllegalStateException("AudioTrack prime error: " + primed);
            s.queueMeter.accepted(primed);
            s.meter.reset(); s.track.play(); s.playing = true;
            long nextInspection = 0;
            while (!s.stopRequested) {
                long start = System.nanoTime();
                int rendered = RuntimeBridge.render(block, s.frames);
                s.meter.record(System.nanoTime() - start, s.frames, s.sampleRate);
                if (rendered != s.frames) throw new IllegalStateException("Incomplete native block");
                int offset = 0, zeroWrites = 0;
                while (!s.stopRequested && offset < block.length) {
                    int n = s.track.write(block, offset, block.length - offset, AudioTrack.WRITE_BLOCKING);
                    if (n < 0) {
                        if (s.stopRequested) break;
                        throw new IllegalStateException("AudioTrack write error: " + n);
                    }
                    s.queueMeter.accepted(n);
                    if (n == 0) {
                        if (++zeroWrites > 100) throw new IllegalStateException("AudioTrack made no progress");
                        Thread.sleep(1);
                    } else { offset += n; zeroWrites = 0; }
                }
                long now = System.nanoTime();
                if (!s.stopRequested && now >= nextInspection) {
                    inspectOutput(s); nextInspection = now + 250_000_000L;
                }
            }
        } catch (Exception | LinkageError error) {
            if (!s.stopRequested) s.error = error.getClass().getSimpleName() + ": " + error.getMessage();
        } finally {
            s.playing = false;
            synchronized (s) {
                try { s.underruns = s.track.getUnderrunCount(); } catch (RuntimeException ignored) {}
                try { s.track.pause(); s.track.flush(); } catch (RuntimeException ignored) {}
                s.track.release(); s.finished = true;
            }
        }
    }
    /** Off UI thread. A timed-out render must never be followed by native engine reuse. */
    public void stop() throws InterruptedException {
        Session s = current;
        if (s == null) return;
        s.stopRequested = true;
        synchronized (s) {
            if (!s.finished) {
                try { s.track.pause(); s.track.flush(); } catch (RuntimeException ignored) {}
            }
        }
        if (s.worker != null) {
            s.worker.join(3000);
            if (s.worker.isAlive()) throw new IllegalStateException(
                    "Audio worker did not stop. Close and reopen RiGear before restarting.");
        }
    }
    public static String performanceName(int mode) {
        if (mode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) return "LOW_LATENCY";
        if (mode == AudioTrack.PERFORMANCE_MODE_POWER_SAVING) return "POWER_SAVING";
        return "NORMAL";
    }
}
