package com.rinoize.rigear;

/** 0.9: render returns OUTPUT frames, while its argument remains Virus input frames. */
public final class RuntimeBridge {
    static { System.loadLibrary("rigear"); }
    private RuntimeBridge() {}
    public static native String prepare(int frames, int clockPercent, int extraFrames);
    public static native void configureOutput(int outputSampleRate);
    public static native int render(float[] stereo, int nativeFrames);
    public static native void panic();
    public static native void setGainDb(int db);
    public static native void setParameter(int page, int index, int value);
    public static native int[] panel();
    public static native void requestPanel();
    public static native long[] signalStats(boolean reset);
    // SRC wall nanoseconds: last and max; cumulative native input and converted output frames.
    public static native long[] conversionStats();
}
