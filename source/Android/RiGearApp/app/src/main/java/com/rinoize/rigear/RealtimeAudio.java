package com.rinoize.rigear;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;

/** 0.11.1 stability update: optional C++ conversion to 48000 Hz; native-rate path retained.
 * Engine frame counts and deadlines use deviceSampleRate. Playback uses sampleRate.
 *
 * The render thread now does only render + AudioTrack.write. Route inspection, underrun
 * queries and SRC telemetry run on a separate low-priority monitor thread. In low-latency
 * mode, the effective AudioTrack buffer grows by one 256-frame step only after a real
 * underrun, preserving the requested latency while the patch is stable.
 */
public final class RealtimeAudio {
    public static final int CONSERVATIVE = 0, LOW_LATENCY = 1;
    private static final int ADAPTIVE_QUANTUM_FRAMES = 256;
    private static final long ADAPTIVE_GRACE_NANOS = 2_000_000_000L;
    private static final long ADAPTIVE_MIN_INTERVAL_NANOS = 1_000_000_000L;
    private static final long MONITOR_INTERVAL_MS = 500L;

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
        public final int frames, deviceSampleRate, sampleRate, maxOutputFrames;
        public final int outputMode, requestedOutputFrames, extraDspFrames;
        public final String configuration;
        public final boolean resampling;
        public final RenderMeter meter = new RenderMeter();
        private final AudioTrack track;
        private final PlaybackQueueMeter queueMeter = new PlaybackQueueMeter();
        private Thread worker, monitor;
        public volatile boolean stopRequested, monitorStop, playing, finished;
        public volatile int underruns, adaptiveChanges, adaptiveFrames;
        public volatile String error = "", monitorNote = "";
        public volatile OutputState output;
        public volatile long srcNanos, srcMaxNanos, srcInputFrames, srcOutputFrames;
        private volatile int lastObservedUnderruns;
        private volatile long playbackStartedNanos, lastAdaptiveNanos;

        private Session(AudioTrack track, int frames, int nativeRate, int outputRate,
                        String config, int mode, int requested, int extra) {
            this.track = track; this.frames = frames; deviceSampleRate = nativeRate;
            sampleRate = outputRate; resampling = nativeRate != outputRate;
            maxOutputFrames = resampling ? (frames * 128 + 124) / 125 : frames;
            configuration = config; outputMode = mode;
            requestedOutputFrames = requested; extraDspFrames = extra;
            adaptiveFrames = track.getBufferSizeInFrames();
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
    public boolean hasLiveSession() {
        Session s = current; return s != null && !s.finished;
    }

    public void start(int frames, int clockPercent, int extraFrames, int gainDb,
                      int outputMode, int requestedOutputFrames, int requestedRate) throws Exception {
        stop();
        if (outputMode != CONSERVATIVE && outputMode != LOW_LATENCY)
            throw new IllegalArgumentException("Modo de salida desconocido");
        if (requestedOutputFrames < 128 || requestedOutputFrames > 8192)
            throw new IllegalArgumentException("Buffer de salida fuera de rango");
        if (requestedRate != 0 && requestedRate != 48000)
            throw new IllegalArgumentException("Seleccionar Nativa o 48000 Hz");
        RuntimeBridge.setGainDb(gainDb);
        String configuration = RuntimeBridge.prepare(frames, clockPercent, extraFrames);
        int nativeRate = NativeBridge.nativeGetDeviceSampleRate();
        if (nativeRate <= 0) throw new IllegalStateException("No device sample rate");
        int sampleRate = requestedRate == 0 ? nativeRate : requestedRate;
        RuntimeBridge.configureOutput(sampleRate);
        int maxOutputFrames = sampleRate == nativeRate ? frames : (frames * 128 + 124) / 125;
        int minBytes = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
        if (minBytes <= 0) throw new IllegalStateException("Unsupported AudioTrack rate: " + sampleRate);

        // A larger capacity does not add latency by itself: setBufferSizeInFrames below keeps
        // the requested effective size. It only leaves room for a later one-burst recovery.
        int adaptiveCapacityFrames = Math.min(8192, Math.max(4096, requestedOutputFrames * 4));
        int capacityBytes = outputMode == CONSERVATIVE
                ? Math.max(minBytes * 2, maxOutputFrames * 8 * 4)
                : Math.max(minBytes * 2, adaptiveCapacityFrames * 8);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(capacityBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
        try {
            if (track.getState() != AudioTrack.STATE_INITIALIZED || track.getSampleRate() != sampleRate)
                throw new IllegalStateException("AudioTrack no acepto la frecuencia solicitada");
            if (outputMode == LOW_LATENCY) {
                int accepted = track.setBufferSizeInFrames(requestedOutputFrames);
                if (accepted <= 0) throw new IllegalStateException(
                        "Android rechazo el buffer: " + accepted + ". Probar modo Conservador.");
                if (Build.VERSION.SDK_INT >= 31)
                    track.setStartThresholdInFrames(track.getBufferSizeInFrames());
            }
            Session s = new Session(track, frames, nativeRate, sampleRate, configuration, outputMode,
                    outputMode == CONSERVATIVE ? 0 : requestedOutputFrames, extraFrames);
            s.worker = new Thread(() -> run(s), "RiGear-Audio");
            current = s;
            try { s.worker.start(); }
            catch (RuntimeException error) { s.finished = true; throw error; }
        } catch (RuntimeException error) { track.release(); throw error; }
    }

    private static int threshold(AudioTrack track) {
        return Build.VERSION.SDK_INT >= 31 ? track.getStartThresholdInFrames() : -1;
    }

    /** Monitor-thread only. Never called by the realtime render/write loop. */
    private void inspectOutput(Session s) {
        if (s.stopRequested || s.monitorStop || s.finished) return;
        try {
            int observedUnderruns = s.track.getUnderrunCount();
            long now = System.nanoTime();
            if (s.outputMode == LOW_LATENCY && s.playing
                    && now - s.playbackStartedNanos >= ADAPTIVE_GRACE_NANOS
                    && observedUnderruns > s.lastObservedUnderruns
                    && now - s.lastAdaptiveNanos >= ADAPTIVE_MIN_INTERVAL_NANOS) {
                int currentFrames = s.track.getBufferSizeInFrames();
                int capacityFrames = s.track.getBufferCapacityInFrames();
                int targetFrames = PlaybackQueueMeter.nextAdaptiveBufferSize(
                        currentFrames, capacityFrames, ADAPTIVE_QUANTUM_FRAMES);
                if (targetFrames > currentFrames) {
                    int accepted = s.track.setBufferSizeInFrames(targetFrames);
                    if (accepted > currentFrames) {
                        ++s.adaptiveChanges; s.adaptiveFrames = accepted;
                        s.monitorNote = "AUTO buffer " + currentFrames + "→" + accepted;
                        s.lastAdaptiveNanos = now;
                    } else if (accepted <= 0) {
                        s.monitorNote = "AUTO buffer rechazado: " + accepted;
                        s.lastAdaptiveNanos = now;
                    }
                } else {
                    s.monitorNote = "AUTO buffer en capacidad maxima";
                    s.lastAdaptiveNanos = now;
                }
            }
            s.lastObservedUnderruns = observedUnderruns;
            long pending = s.queueMeter.pending(s.track.getPlaybackHeadPosition());
            AudioDeviceInfo device = s.track.getRoutedDevice();
            String route = device == null ? "Ruta aun no disponible"
                    : device.getProductName() + " (tipo " + device.getType() + ")";
            int effective = s.track.getBufferSizeInFrames();
            if (s.adaptiveChanges > 0)
                route += " · AUTO +" + s.adaptiveChanges + " → " + effective + " fr";
            s.output = new OutputState(effective, s.track.getBufferCapacityInFrames(),
                    s.track.getPerformanceMode(), threshold(s.track), pending,
                    s.queueMeter.writtenFrames(), now, route);
            s.underruns = observedUnderruns;
            if (s.resampling) {
                long[] stats = RuntimeBridge.conversionStats();
                if (stats != null && stats.length == 4) {
                    s.srcNanos = stats[0]; s.srcMaxNanos = stats[1];
                    s.srcInputFrames = stats[2]; s.srcOutputFrames = stats[3];
                }
            }
        } catch (RuntimeException error) {
            if (!s.stopRequested && !s.monitorStop)
                s.monitorNote = "Diagnostico audio: " + error.getClass().getSimpleName();
        }
    }

    private void monitor(Session s) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            while (!s.stopRequested && !s.monitorStop && !s.finished) {
                inspectOutput(s);
                Thread.sleep(MONITOR_INTERVAL_MS);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            if (!s.stopRequested) s.monitorNote = "Monitor: " + error.getClass().getSimpleName();
        }
    }

    private void startMonitor(Session s) {
        s.monitor = new Thread(() -> monitor(s), "RiGear-Monitor");
        s.monitor.setDaemon(true);
        s.monitor.start();
    }

    private void stopMonitor(Session s) {
        s.monitorStop = true;
        Thread monitor = s.monitor;
        if (monitor == null) return;
        monitor.interrupt();
        if (monitor == Thread.currentThread()) return;
        try { monitor.join(1000); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private void run(Session s) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            float[] block = new float[s.maxOutputFrames * 2];
            for (int i = 0; i < 4 && !s.stopRequested; ++i) {
                int n = RuntimeBridge.render(block, s.frames);
                if (n <= 0 || n > s.maxOutputFrames) throw new IllegalStateException("Native warm-up failed");
            }
            if (s.stopRequested) return;
            float[] silence = new float[s.track.getBufferSizeInFrames() * 2];
            int primed = s.track.write(silence, 0, silence.length, AudioTrack.WRITE_NON_BLOCKING);
            if (primed < 0) throw new IllegalStateException("AudioTrack prime error: " + primed);
            s.queueMeter.accepted(primed);
            s.meter.reset(); s.track.play(); s.playing = true;
            s.playbackStartedNanos = System.nanoTime();
            s.lastObservedUnderruns = s.track.getUnderrunCount();
            startMonitor(s);

            while (!s.stopRequested) {
                long start = System.nanoTime();
                int rendered = RuntimeBridge.render(block, s.frames);
                // Total render INCLUDING SRC, relative to the native block's audio duration.
                s.meter.record(System.nanoTime() - start, s.frames, s.deviceSampleRate);
                if (rendered <= 0 || rendered > s.maxOutputFrames)
                    throw new IllegalStateException("Invalid converted output block");
                final int sampleCount = rendered * 2;
                int offset = 0, zeroWrites = 0;
                // The last array slot may be unused on a fractional-rate block. Never play it.
                while (!s.stopRequested && offset < sampleCount) {
                    int n = s.track.write(block, offset, sampleCount - offset, AudioTrack.WRITE_BLOCKING);
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
            }
        } catch (Exception | LinkageError error) {
            if (!s.stopRequested) s.error = error.getClass().getSimpleName() + ": " + error.getMessage();
        } finally {
            s.playing = false;
            stopMonitor(s);
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
        s.stopRequested = true; s.monitorStop = true;
        if (s.monitor != null) s.monitor.interrupt();
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
