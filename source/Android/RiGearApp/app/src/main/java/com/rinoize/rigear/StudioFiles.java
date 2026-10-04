package com.rinoize.rigear;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Storage Access Framework import/export and app-private ROM retention. No file-path conversion of content:// URIs. */
public final class StudioFiles {
    private StudioFiles() {}
    public static byte[] read(Context context, Uri uri, int limit) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("No se pudo abrir el archivo seleccionado");
            return limited(in, limit);
        }
    }
    public static byte[] limited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] block = new byte[8192]; int n;
        while ((n = in.read(block)) != -1) {
            if (n == 0) continue;
            if (out.size() > limit - n) throw new IOException("Archivo demasiado grande");
            out.write(block, 0, n);
        }
        return out.toByteArray();
    }
    public static void write(Context context, Uri uri, byte[] data) throws IOException {
        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("No se pudo crear el archivo"); out.write(data); out.flush();
        }
    }
    public static void retainRom(Context context, RomImporter.Bundle bundle) throws IOException {
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "retained-rom-v1.bin"));
        FileOutputStream raw = null;
        try {
            raw = file.startWrite(); DataOutputStream out = new DataOutputStream(raw);
            out.writeInt(0x5247524d); out.writeInt(bundle.data.length);
            for (int i = 0; i < bundle.data.length; ++i) { out.writeUTF(bundle.names[i]); out.writeInt(bundle.data[i].length); out.write(bundle.data[i]); }
            out.flush(); file.finishWrite(raw);
        } catch (IOException | RuntimeException error) { if (raw != null) file.failWrite(raw); throw error; }
    }
    public static RomImporter.Bundle retainedRom(Context context) throws IOException {
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "retained-rom-v1.bin"));
        try (DataInputStream in = new DataInputStream(file.openRead())) {
            if (in.readInt() != 0x5247524d) throw new IOException("Copia de ROM inválida");
            int count = in.readInt(); if (count <= 0 || count > 64) throw new IOException("ROM: cantidad de archivos inválida");
            List<byte[]> data = new ArrayList<>(); List<String> names = new ArrayList<>(); long total = 0;
            for (int i = 0; i < count; ++i) {
                String name = in.readUTF(); int size = in.readInt(); total += size;
                if (size <= 0 || size > 16 * 1024 * 1024 || total > 64 * 1024 * 1024) throw new IOException("ROM: límite excedido");
                byte[] bytes = new byte[size]; in.readFully(bytes); names.add(name); data.add(bytes);
            }
            return new RomImporter.Bundle(data, names);
        } catch (FileNotFoundException e) { return null; }
    }
    /** ZIP library imports only inspect .syx entries; ALL expanded bytes count toward the bound. */
    public static List<byte[]> importSounds(byte[] input) throws IOException {
        List<byte[]> result = new ArrayList<>();
        if (input.length > 3 && input[0] == 0x50 && input[1] == 0x4b) {
            int entries = 0, total = 0; byte[] buffer = new byte[8192];
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(input))) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    if (++entries > 512) throw new IOException("ZIP con demasiadas entradas");
                    boolean use = !entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".syx");
                    ByteArrayOutputStream out = use ? new ByteArrayOutputStream() : null; int n;
                    while ((n = in.read(buffer)) != -1) {
                        total += n; if (total > 16 * 1024 * 1024) throw new IOException("ZIP supera límite descomprimido");
                        if (use) out.write(buffer, 0, n);
                    }
                    if (use) result.addAll(group(SoundCodec.split(out.toByteArray())));
                    if (result.size() > 8192) throw new IOException("Demasiados sonidos");
                    in.closeEntry();
                }
            }
        } else result.addAll(group(SoundCodec.split(input)));
        if (result.isEmpty()) throw new IOException("No se encontraron sonidos .syx en el archivo");
        return result;
    }
    private static List<byte[]> group(List<byte[]> packets) {
        // An edit-buffer Multi followed by unique per-part Singles is a portable arrangement.
        boolean arrangement = packets.get(0)[6] == 0x11 && packets.get(0)[7] == 0 && packets.size() <= 17;
        boolean[] parts = new boolean[16];
        for (int i = 1; i < packets.size() && arrangement; ++i) {
            byte[] p = packets.get(i); int part = p[8] & 127;
            if (p[6] != 0x10 || p[7] != 0 || part >= 16 || parts[part]) arrangement = false;
            else parts[part] = true;
        }
        List<byte[]> result = new ArrayList<>();
        if (arrangement) result.add(SoundCodec.join(packets)); else result.addAll(packets);
        return result;
    }
}
