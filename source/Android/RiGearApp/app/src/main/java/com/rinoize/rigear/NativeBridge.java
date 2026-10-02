package com.rinoize.rigear;

public final class NativeBridge {
    static {
        System.loadLibrary("rigear");
    }

    private NativeBridge() {}

    public static native String nativeGetCoreInfo();
    public static native int nativeSelfTest();
    public static native String nativeLoadRom(byte[] romData, String romName);
    public static native int nativeGetDeviceSampleRate();
    public static native float[] nativeRenderTestNote(int note, int velocity, int durationMs);

    public static native boolean nativeNoteOn(int note, int velocity);
    public static native boolean nativeNoteOff(int note);
    public static native void nativePanic();
    public static native int nativeProcessAudio(float[] stereoBuffer, int frames);

    public static native boolean nativeSendMidiBytes(byte[] data, int offset, int count);
    public static native void nativeResetMidiStats();
    public static native String nativeGetMidiStats();

    public static native void nativeRelease();
}
