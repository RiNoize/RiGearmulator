package com.rinoize.rixenia;

/** Keep allocation and the native converter's 6/5 ratio in agreement. */
public final class AudioSizing {
    private AudioSizing() {}
    public static int maxOutputFrames(int frames, int nativeRate, int outputRate) {
        if (frames <= 0 || frames > 8192 || nativeRate != 40000 ||
                (outputRate != 40000 && outputRate != 48000))
            throw new IllegalArgumentException("Configuración de audio Xenia inválida");
        return (int)(((long)frames * outputRate + nativeRate - 1) / nativeRate);
    }
}
