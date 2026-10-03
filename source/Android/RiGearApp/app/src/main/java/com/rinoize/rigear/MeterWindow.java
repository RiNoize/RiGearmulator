package com.rinoize.rigear;

/** A window for render cost and OV/s. Meter resets never produce negative rates. */
public final class MeterWindow {
    private boolean valid;
    private long lastWall, lastFrames, lastWork, lastOv;
    public void reset() { valid = false; }
    public double[] sample(long wallNanos, long[] total, int sampleRate) {
        if (total == null || total.length < 5 || sampleRate <= 0)
            throw new IllegalArgumentException("Invalid meter snapshot");
        double render = 0, rate = 0;
        if (valid && wallNanos > lastWall && total[1] >= lastFrames &&
                total[2] >= lastWork && total[4] >= lastOv) {
            long frames = total[1] - lastFrames;
            if (frames > 0) render = 100.0 * (total[2] - lastWork) * sampleRate / (frames * 1e9);
            rate = (total[4] - lastOv) * 1e9 / (wallNanos - lastWall);
        }
        lastWall = wallNanos; lastFrames = total[1]; lastWork = total[2]; lastOv = total[4];
        valid = true;
        return new double[]{render, rate};
    }
}
