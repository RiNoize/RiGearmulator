package com.rinoize.rigear;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.HardwarePropertiesManager;
import android.os.PowerManager;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Slow platform diagnostics on a separate worker, never on the render thread. */
public final class ThermalMonitor implements AutoCloseable {
    public static final class Reading {
        public final int status;
        public final float headroom, cpuC, batteryC;
        public final long sampled;
        Reading(int status, float headroom, float cpu, float battery) {
            this.status = status; this.headroom = headroom; cpuC = cpu; batteryC = battery; sampled = System.nanoTime();
        }
        public String compact() {
            return "THERMAL " + statusName(status) + " | CPU °C " + value(cpuC) + " | HR " +
                (Float.isNaN(headroom) ? "N/D" : String.format(Locale.ROOT, "%.2f", headroom));
        }
        public String detail() {
            return compact() + "\nBatería: " + value(batteryC) + " °C (no es temperatura CPU)\n" +
                "Headroom: 1.0 corresponde al umbral SEVERE; puede superar 1. No es °C ni porcentaje libre.\n" +
                "CPU N/D = Android no permite leer ese sensor. No se sustituye por un valor inventado.";
        }
    }
    private final Context context;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private boolean cpuAllowed = true;
    private long nextSlow;
    private float headroom = Float.NaN, battery = Float.NaN;
    public volatile Reading reading = new Reading(-1, Float.NaN, Float.NaN, Float.NaN);
    public ThermalMonitor(Context context) {
        this.context = context.getApplicationContext(); worker.scheduleWithFixedDelay(this::sample, 0, 3, TimeUnit.SECONDS);
    }
    private void sample() {
        int status = -1; float cpu = Float.NaN;
        PowerManager manager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        try { if (manager != null && Build.VERSION.SDK_INT >= 29) status = manager.getCurrentThermalStatus(); } catch (RuntimeException ignored) {}
        long now = System.nanoTime();
        if (now >= nextSlow) {
            nextSlow = now + 15_000_000_000L;
            try { headroom = manager != null && Build.VERSION.SDK_INT >= 30 ? manager.getThermalHeadroom(0) : Float.NaN; }
            catch (RuntimeException ignored) { headroom = Float.NaN; }
            try {
                Intent i = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                battery = i != null && i.hasExtra(BatteryManager.EXTRA_TEMPERATURE) ? i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f : Float.NaN;
            } catch (RuntimeException ignored) { battery = Float.NaN; }
        }
        if (cpuAllowed && Build.VERSION.SDK_INT >= 24) {
            try {
                HardwarePropertiesManager hp = (HardwarePropertiesManager)context.getSystemService(Context.HARDWARE_PROPERTIES_SERVICE);
                if (hp != null) {
                    float[] values = hp.getDeviceTemperatures(HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU, HardwarePropertiesManager.TEMPERATURE_CURRENT);
                    for (float v : values) if (v > -20 && v < 160 && (Float.isNaN(cpu) || v > cpu)) cpu = v;
                }
            } catch (SecurityException e) { cpuAllowed = false; }
              catch (RuntimeException ignored) {}
        }
        reading = new Reading(status, headroom, cpu, battery);
    }
    private static String value(float f) { return Float.isNaN(f) ? "N/D" : String.format(Locale.ROOT, "%.1f", f); }
    public static String statusName(int status) {
        String[] labels = {"NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN"};
        return status >= 0 && status < labels.length ? labels[status] : "N/D";
    }
    @Override public void close() { worker.shutdownNow(); }
}
