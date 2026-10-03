package com.rinoize.rigear;

/** Native runtime. In 0.8 extraFrames is an absolute frame count, NOT a block count. */
public final class RuntimeBridge {
    static { System.loadLibrary("rigear"); }
    private RuntimeBridge() {}
    public static native String prepare(int frames, int clockPercent, int extraFrames);
    public static native int render(float[] stereo, int frames);
    public static native void panic();
    public static native void setGainDb(int db);
    public static native void setParameter(int page, int index, int value);
    public static native int[] panel();
    public static native void requestPanel();
    public static native long[] signalStats(boolean reset);
}
