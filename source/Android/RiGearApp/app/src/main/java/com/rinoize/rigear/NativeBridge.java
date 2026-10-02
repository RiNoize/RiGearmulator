package com.rinoize.rigear;

public final class NativeBridge {
    static {
        System.loadLibrary("rigear");
    }

    private NativeBridge() {}

    public static native String nativeGetCoreInfo();
    public static native int nativeSelfTest();
    public static native String nativeLoadRom(byte[] romData, String romName);
    public static native void nativeRelease();
}
