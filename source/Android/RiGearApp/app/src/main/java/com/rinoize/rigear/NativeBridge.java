package com.rinoize.rigear;

public final class NativeBridge {
    static {
        System.loadLibrary("rigear");
    }

    private NativeBridge() {}

    public static native String nativeGetCoreInfo();
    public static native int nativeSelfTest();
}
