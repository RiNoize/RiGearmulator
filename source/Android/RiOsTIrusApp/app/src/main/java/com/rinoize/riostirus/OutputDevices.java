package com.rinoize.riostirus;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Audio outputs the user can pick. Android normally prefers the headphone jack over a
 * USB interface; AudioTrack.setPreferredDevice overrides that per track. The choice is
 * remembered as "type|product name" because device ids change on every reconnection. */
final class OutputDevices {
    private OutputDevices() {}

    private static final int[] USEFUL = {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    };

    static boolean isUsb(int type) {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    static String label(AudioDeviceInfo d) {
        String name = d.getProductName() == null ? "" : d.getProductName().toString().trim();
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "Parlante de la tablet";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Auriculares (jack de la tablet)";
            case AudioDeviceInfo.TYPE_LINE_ANALOG: return "Salida de línea (jack)";
            case AudioDeviceInfo.TYPE_HDMI: return "HDMI";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth" + (name.isEmpty() ? "" : " · " + name) + " (latencia alta)";
            default: return "USB" + (name.isEmpty() ? "" : " · " + name);
        }
    }

    static String key(AudioDeviceInfo d) {
        return d.getType() + "|" + (d.getProductName() == null ? "" : d.getProductName().toString().trim());
    }

    /** Sinks worth offering, one per type and name, USB first. */
    static List<AudioDeviceInfo> list(AudioManager am) {
        Map<String, AudioDeviceInfo> unique = new LinkedHashMap<>();
        AudioDeviceInfo[] all = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (int type : USEFUL)
            for (AudioDeviceInfo d : all)
                if (d.isSink() && d.getType() == type && !unique.containsKey(key(d))) unique.put(key(d), d);
        return new ArrayList<>(unique.values());
    }

    static AudioDeviceInfo find(AudioManager am, String key) {
        if (key == null || key.isEmpty()) return null;
        for (AudioDeviceInfo d : list(am)) if (key(d).equals(key)) return d;
        return null;
    }

    /** Engine rate that avoids Android resampling on this output (resampling forbids the fast
     * mixer). An empty rate list means the device accepts any rate. */
    static int bestRate(AudioDeviceInfo d, int current) {
        int[] rates = d.getSampleRates();
        if (rates == null || rates.length == 0) return current;
        boolean has44 = false, has48 = false;
        for (int r : rates) { if (r == current) return current; has44 |= r == 44100; has48 |= r == 48000; }
        return has48 ? 48000 : has44 ? 44100 : current;
    }

    static String rates(AudioDeviceInfo d) {
        int[] rates = d.getSampleRates();
        if (rates == null || rates.length == 0) return "cualquiera";
        StringBuilder out = new StringBuilder();
        for (int r : rates) { if (out.length() > 0) out.append('/'); out.append(r); }
        return out + " Hz";
    }
}
