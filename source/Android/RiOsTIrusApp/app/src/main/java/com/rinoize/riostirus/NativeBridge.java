package com.rinoize.riostirus;

public final class NativeBridge {
    static { System.loadLibrary("riostirus"); }
    private NativeBridge() {}
    public static final int MODEL_TI = 0, MODEL_TI2 = 1, MODEL_SNOW = 2;
    public static native String nativeGetCoreInfo();
    /** Virus TI OS firmware .bin (6-9 MiB). model: 0 TI, 1 TI2, 2 Snow. sampleRate: 44100 or 48000. */
    public static native String nativeLoadRom(byte[] rom, String name, int model, int sampleRate);
    public static native int nativeGetDeviceSampleRate();
    public static native boolean nativeSendShort(int status, int data1, int data2);
    public static native boolean nativeSendMidiBytes(byte[] data, int offset, int count);
    public static native void nativeResetMidiStats();
    public static native String nativeGetMidiStats();
    public static native void nativeRelease();
}
