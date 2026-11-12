package com.rinoize.riostirus;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

/** RiOsTIrus: AudioTrack session around the TI engine. The engine runs at the output
 * rate (44100 or 48000 Hz), so there is no sample-rate conversion. Adapted from RiXenia:
 * the render thread does only render + AudioTrack.write; route inspection, underrun
 * queries and per-thread CPU diagnostics run on a separate low-priority monitor thread.
 */
public final class RealtimeAudio {
    public static final int CONSERVATIVE = 0, LOW_LATENCY = 1;
    public static final int CPU_AUTOMATIC = 0, CPU_PERFORMANCE = 1, CPU_SPREAD = 2;
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
        public final int outputMode, requestedOutputFrames;
        public final String configuration;
        public final RenderMeter meter = new RenderMeter();
        private final AudioTrack track;
        private final PlaybackQueueMeter queueMeter = new PlaybackQueueMeter();
        private final AudioHealth health;
        private final long[] renderWindow = new long[4];
        private long lastCpuMs, lastCpuWallMs;
        private Thread worker, monitor;
        public volatile boolean stopRequested, monitorStop, playing, finished;
        public volatile int underruns, adaptiveChanges, adaptiveFrames;
        public volatile String error = "", monitorNote = "";
        /** Placement applied at start, and the latest per-thread report from the monitor. */
        public volatile String placementSummary = "", placement = "";
        public volatile OutputState output;
        private volatile int lastObservedUnderruns;
        private volatile long playbackStartedNanos, lastAdaptiveNanos;

        private Session(AudioTrack track, int frames, int nativeRate, int outputRate,
                        String config, int mode, int requested) {
            this.track = track; this.frames = frames; deviceSampleRate = nativeRate;
            sampleRate = outputRate; maxOutputFrames = frames;
            configuration = config; outputMode = mode;
            requestedOutputFrames = requested;
            health = new AudioHealth(frames, nativeRate, outputRate, requested);
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

    /** preferredOutput: null lets Android choose (it prefers the headphone jack over USB). */
    public void start(int frames, int clockPercent, int gainDb,
                      int outputMode, int requestedOutputFrames, int cpuPlacement,
                      AudioDeviceInfo preferredOutput) throws Exception {
        stop();
        if (outputMode != CONSERVATIVE && outputMode != LOW_LATENCY)
            throw new IllegalArgumentException("Modo de salida desconocido");
        if (requestedOutputFrames < 128 || requestedOutputFrames > 8192)
            throw new IllegalArgumentException("Buffer de salida fuera de rango");
        if (cpuPlacement < CPU_AUTOMATIC || cpuPlacement > CPU_SPREAD)
            throw new IllegalArgumentException("Modo de nucleos desconocido");
        RuntimeBridge.setGainDb(gainDb);
        String configuration = RuntimeBridge.prepare(frames, clockPercent);
        int nativeRate = NativeBridge.nativeGetDeviceSampleRate();
        if (nativeRate <= 0) throw new IllegalStateException("No device sample rate");
        int sampleRate = nativeRate;
        // Engine worker threads exist since the ROM boot; place them before rendering.
        String placementSummary = RuntimeBridge.setCpuPlacement(cpuPlacement);
        int maxOutputFrames = frames;
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
            // Route before play(), so the first start already goes to the chosen device.
            if (preferredOutput != null) {
                boolean routed = track.setPreferredDevice(preferredOutput);
                configuration += "\nSalida elegida: " + OutputDevices.label(preferredOutput)
                        + " · acepta " + OutputDevices.rates(preferredOutput) + (routed ? "" : " · Android la rechazó");
            } else {
                configuration += "\nSalida elegida: automática (Android decide)";
            }
            if (outputMode == LOW_LATENCY) {
                int accepted = track.setBufferSizeInFrames(requestedOutputFrames);
                if (accepted <= 0) throw new IllegalStateException(
                        "Android rechazo el buffer: " + accepted + ". Probar modo Conservador.");
                if (Build.VERSION.SDK_INT >= 31)
                    track.setStartThresholdInFrames(track.getBufferSizeInFrames());
            }
            Session s = new Session(track, frames, nativeRate, sampleRate, configuration, outputMode,
                    outputMode == CONSERVATIVE ? 0 : requestedOutputFrames);
            s.placementSummary = placementSummary == null ? "" : placementSummary;
            s.worker = new Thread(() -> run(s), "RiOsTIrus-Audio");
            current = s;
            try { s.worker.start(); }
            catch (RuntimeException error) { s.finished = true; throw error; }
        } catch (RuntimeException error) { track.release(); throw error; }
    }

    public String healthSummary() {
        Session s = current; return s == null ? "Sin sesión de audio" : s.health.summary();
    }

    public String diagnosticReport() {
        Session s = current; return s == null ? "Sin sesión de audio" :
                s.configuration + "\n" + s.output.route + "\n" + s.health.report() +
                "\n" + s.placementSummary + (s.placement.isEmpty() ? "" : "\n" + s.placement);
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
            int beforeFrames = s.track.getBufferSizeInFrames();
            if (s.outputMode == LOW_LATENCY && s.playing
                    && now - s.playbackStartedNanos >= ADAPTIVE_GRACE_NANOS
                    && observedUnderruns > s.lastObservedUnderruns
                    && now - s.lastAdaptiveNanos >= ADAPTIVE_MIN_INTERVAL_NANOS) {
                int currentFrames = beforeFrames;
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
            long wallMs = SystemClock.elapsedRealtime(), cpuMs = Process.getElapsedCpuTime();
            double cpu = wallMs > s.lastCpuWallMs ?
                    100.0 * (cpuMs - s.lastCpuMs) / (wallMs - s.lastCpuWallMs) : 0;
            s.lastCpuMs = cpuMs; s.lastCpuWallMs = wallMs;
            s.meter.drainWindow(s.renderWindow);
            s.health.observe(now, observedUnderruns, beforeFrames, effective,
                    s.output.capacityFrames, pending, s.renderWindow, cpu);
            String placement = RuntimeBridge.placementReport();
            if (placement != null) s.placement = placement;
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
        s.monitor = new Thread(() -> monitor(s), "RiOsTIrus-Mon");
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
            s.health.start(s.playbackStartedNanos);
            s.lastCpuMs = Process.getElapsedCpuTime();
            s.lastCpuWallMs = SystemClock.elapsedRealtime();
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
                    "Audio worker did not stop. Close and reopen RiOsTIrus before restarting.");
        }
    }

    public static String performanceName(int mode) {
        if (mode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) return "LOW_LATENCY";
        if (mode == AudioTrack.PERFORMANCE_MODE_POWER_SAVING) return "POWER_SAVING";
        return "NORMAL";
    }
}
