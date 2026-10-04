package com.rinoize.rigear;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Strict A/B/C sound-dump codec. Firmware and arbitrary SysEx never enter the sound library. */
public final class SoundCodec {
    public static final int SIZE = 267;
    public static final String[] CATEGORIES = {"Sin categoría", "Lead", "Bass", "Pad", "Decay", "Pluck",
            "Acid", "Classic", "Arpeggiator", "Effects", "Drums", "Percussion", "Input", "Vocoder",
            "Favourite 1", "Favourite 2", "Favourite 3"};
    private SoundCodec() {}
    public static List<byte[]> split(byte[] input) {
        if (input == null || input.length == 0 || input.length > 8 * 1024 * 1024)
            throw new IllegalArgumentException("Archivo de sonidos vacío o demasiado grande");
        ArrayList<byte[]> result = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < input.length; ++i) {
            int b = input[i] & 255;
            if (start < 0) {
                if (b == 0xf0) start = i;
                else if (b != 0 && b != 10 && b != 13 && b != 32 && b < 0xf8)
                    throw new IllegalArgumentException("No es un archivo SysEx de sonidos (.syx)");
            } else {
                if (b == 0xf7) {
                    byte[] packet = Arrays.copyOfRange(input, start, i + 1); validate(packet); result.add(packet); start = -1;
                    if (result.size() > 8192) throw new IllegalArgumentException("Demasiados sonidos");
                } else if (b >= 128 || i - start >= SIZE)
                    throw new IllegalArgumentException("Paquete SysEx incompleto o no compatible");
            }
        }
        if (start >= 0 || result.isEmpty()) throw new IllegalArgumentException("SysEx incompleto o sin sonidos");
        return result;
    }
    public static void validate(byte[] p) {
        if (p == null || p.length != SIZE || (p[0] & 255) != 0xf0 || p[1] != 0 || p[2] != 0x20 || p[3] != 0x33 ||
                p[4] != 1 || (p[6] != 0x10 && p[6] != 0x11) || (p[266] & 255) != 0xf7)
            throw new IllegalArgumentException("Se admiten dumps Single/Multi Virus A/B/C de 256 bytes, no firmware ni presets TI");
        for (int i = 1; i < 266; ++i) if ((p[i] & 255) > 127) throw new IllegalArgumentException("Datos MIDI inválidos");
        if ((p[265] & 127) != checksum(p)) throw new IllegalArgumentException("Checksum SysEx incorrecto");
    }
    public static int checksum(byte[] p) { int sum = 0; for (int i = 5; i < 265; ++i) sum += p[i] & 127; return sum & 127; }
    public static void repair(byte[] p) { p[265] = (byte)checksum(p); }
    public static String name(byte[] p) {
        validate(p); int offset = 9 + (p[6] == 0x11 ? 4 : 240);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 10; ++i) { int c = p[offset + i] & 127; text.append(c >= 32 && c < 127 ? (char)c : ' '); }
        String s = text.toString().trim(); return s.isEmpty() ? "Sin nombre" : s;
    }
    public static byte[] rename(byte[] data, String name) {
        String clean = name.trim();
        if (clean.isEmpty() || clean.length() > 10) throw new IllegalArgumentException("El Virus admite de 1 a 10 caracteres");
        for (int i = 0; i < clean.length(); ++i) if (clean.charAt(i) < 32 || clean.charAt(i) > 126)
            throw new IllegalArgumentException("El nombre del Virus usa caracteres ASCII sin acentos");
        List<byte[]> packets = split(data);
        // In an arrangement rename only the Multi, never its constituent Singles.
        boolean multi = packets.stream().anyMatch(p -> p[6] == 0x11);
        for (byte[] p : packets) {
            if ((multi && p[6] != 0x11) || (!multi && packets.size() != 1)) continue;
            int offset = 9 + (multi ? 4 : 240);
            Arrays.fill(p, offset, offset + 10, (byte)' ');
            byte[] bytes = clean.getBytes(StandardCharsets.US_ASCII); System.arraycopy(bytes, 0, p, offset, bytes.length); repair(p);
        }
        return join(packets);
    }
    public static String category(byte[] single) {
        if (single[6] != 0x10) return "Multi";
        int a = single[9 + 251] & 127, b = single[9 + 252] & 127;
        String first = a < CATEGORIES.length ? CATEGORIES[a] : "Categoría " + a;
        return b != 0 && b != a && b < CATEGORIES.length ? first + " / " + CATEGORIES[b] : first;
    }
    public static byte[] join(List<byte[]> packets) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] p : packets) { validate(p); result.write(p, 0, p.length); }
        return result.toByteArray();
    }
    public static byte[] primary(byte[] data) {
        List<byte[]> packets = split(data);
        for (byte[] p : packets) if (p[6] == 0x11) return p;
        if (packets.size() != 1) throw new IllegalArgumentException("Seleccionar un sonido, no un banco completo");
        return packets.get(0);
    }
    public static String hash(byte[] data) {
        try { byte[] hash = MessageDigest.getInstance("SHA-256").digest(data); StringBuilder s = new StringBuilder();
            for (byte b : hash) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return s.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static byte[] exportBank(List<byte[]> sounds) {
        if (sounds.isEmpty() || sounds.size() > 128) throw new IllegalArgumentException("Un banco requiere 1–128 Singles");
        List<byte[]> bank = new ArrayList<>(); int n = 0;
        for (byte[] data : sounds) {
            byte[] p = primary(data).clone();
            if (p[6] != 0x10 || data.length != SIZE) throw new IllegalArgumentException("Exportar banco admite únicamente Singles");
            p[5] = 0x10; p[7] = 1; p[8] = (byte)n++; repair(p); bank.add(p);
        }
        return join(bank);
    }
    /** Linked relative motion preserves the pair's offset and clamps the common delta. */
    public static int[] linked(int first, int second, int requestedFirst) {
        int delta = Math.max(-Math.min(first, second), Math.min(127 - Math.max(first, second), requestedFirst - first));
        return new int[]{first + delta, second + delta};
    }
    public static final class Snapshot {
        public final byte[][] singles = new byte[17][];
        public byte[] multi;
        public boolean multiMode;
        public Snapshot(byte[] data) {
            for (byte[] p : split(data)) {
                if (p[7] != 0) continue;
                if (p[6] == 0x11) { multi = p; multiMode = true; }
                else if (p[8] == 64) { singles[16] = p; multiMode = false; }
                else if (p[8] >= 0 && p[8] < 16) singles[p[8]] = p;
            }
        }
        public byte[] single(int part) { return singles[part == 64 ? 16 : part]; }
        public byte[] arrangement() {
            if (multi == null) throw new IllegalStateException("No hay configuración Multi disponible");
            List<byte[]> packets = new ArrayList<>(); packets.add(multi);
            for (int i = 0; i < 16; ++i) if (singles[i] != null) packets.add(singles[i]);
            return join(packets);
        }
    }
}
