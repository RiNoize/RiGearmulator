package com.rinoize.rigear;

/** JNI additions for the instrumented 0.7 runtime. */
public final class RuntimeBridge {
    static { System.loadLibrary("rigear"); }
    private RuntimeBridge() {}
    public static native String prepare(int frames, int clockPercent, int extraBlocks);
    public static native int render(float[] stereo, int frames);
    public static native void panic();
    public static native void setGainDb(int db);
    public static native void setParameter(int page, int index, int value);
    public static native int[] panel(); // revision + page A[128] + page B[128]
    public static native void requestPanel();
    public static native long[] signalStats(boolean reset); // raw clips, NaN/Inf, float peak bits
}
