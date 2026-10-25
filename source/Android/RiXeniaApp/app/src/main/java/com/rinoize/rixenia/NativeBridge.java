package com.rinoize.rixenia;

public final class NativeBridge {
    static { System.loadLibrary("rixenia"); }
    private NativeBridge() {}
    public static native String nativeGetCoreInfo();
    public static native int nativeSelfTest();
    public static native String nativeLoadRom(byte[] rom, String name);
    public static native int nativeGetDeviceSampleRate();
    public static native boolean nativeNoteOn(int note, int velocity);
    public static native boolean nativeNoteOff(int note);
    public static native void nativePanic();
    public static native boolean nativeSendMidiBytes(byte[] data, int offset, int count);
    public static native void nativeResetMidiStats();
    public static native String nativeGetMidiStats();
    public static native int nativeGetPatchBankCount();
    public static native String nativeGetPatchName(int bank, int program);
    public static native String nativeSelectPatch(int bank, int program);
    public static native int nativeImportSysex(byte[] data);
    public static native void nativeRelease();
}
