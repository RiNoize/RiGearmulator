package com.rinoize.riostirus;

public final class RuntimeBridge {
    static { System.loadLibrary("riostirus"); }
    private RuntimeBridge() {}
    /** frames 256/512/1024 at the engine rate; clockPercent 50/75/100. */
    public static native String prepare(int frames, int clockPercent);
    /** Returns frames rendered (stereo interleaved, outputs 1-2). */
    public static native int render(float[] stereo, int frames);
    public static native void panic();
    public static native void setGainDb(int db);
    public static native long[] signalStats(boolean reset);
    /** 0 Automatico, 1 Rendimiento (nucleos rapidos), 2 Repartido (un nucleo rapido por DSP).
     * Applies to the engine's DSP threads. Audio must be stopped. Returns a summary. */
    public static native String setCpuPlacement(int mode);
    /** Monitor thread only: per-thread usage since the previous call, CPU, mask, nice, MIPS per DSP. */
    public static native String placementReport();
    /** page 0x70/0x71/0x6E/0x6F, index 0..127, value 0..127. Coalesced; never blocks on the engine. */
    public static native boolean setParameter(int page, int index, int value);
    /** [revision, flags (1 known, 2 external program change pending), bank, program, values...]. */
    public static native int[] values(int[] pages, int[] indices);
    public static native String currentName();
    /** Control thread: re-read the firmware edit buffer. external = after a keyboard Program Change. */
    public static native boolean syncFromEngine(boolean external);
}
