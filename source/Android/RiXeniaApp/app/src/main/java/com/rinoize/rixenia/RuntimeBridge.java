package com.rinoize.rixenia;

public final class RuntimeBridge {
    static { System.loadLibrary("rixenia"); }
    private RuntimeBridge() {}
    public static native String prepare(int frames, int clockPercent, int extraFrames);
    public static native void configureOutput(int sampleRate);
    /** Returns output frames; frames is always measured at the 40000 Hz engine rate. */
    public static native int render(float[] stereo, int frames);
    public static native void panic();
    public static native void setGainDb(int db);
    public static native void setParameter(int index, int value);
    /** revision, bank, program, then 256 parameters, phase, last confirmation ms, pending ms, MIDI queue, SysEx queue.
     * Values are confirmed through firmware RX, not the editor cache; unknown values are -1. */
    public static native int[] panel();
    public static native void requestPanel();
    public static native long[] signalStats(boolean reset);
    public static native long[] conversionStats();
}
