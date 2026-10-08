package com.rinoize.rigear;

public final class StudioBridge {
    static { System.loadLibrary("rigear"); }
    private StudioBridge() {}
    public static native byte[] snapshot();
    public static native byte[][] factory();
    public static native String firmwareInfo();
    public static native void load(byte[] sound, int part, int kind);
    /** Select a Factory Single using the Virus bank/program mechanism. bank=1..8, program=0..127. */
    public static native void selectFactory(int bank, int program, int part);
    public static native void parameters(int part, int[] triples);
    public static native void mode(boolean multi);
}
