package com.rinoize.rigear;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Read-only import. No archive paths are written to the filesystem. */
public final class RomImporter {
    public static final class Bundle {
        public final byte[][] data;
        public final String[] names;
        Bundle(List<byte[]> data, List<String> names) {
            this.data = data.toArray(new byte[0][]); this.names = names.toArray(new String[0]);
        }
    }
    private static final int FILE_LIMIT = 16 * 1024 * 1024;
    private static final int ARCHIVE_LIMIT = 64 * 1024 * 1024;
    private RomImporter() {}
    public static Bundle read(Context context, Uri uri) throws IOException {
        String name = "selected.bin";
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
        }
        byte[] selected;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Android no pudo abrir el archivo");
            selected = readBytes(in, ARCHIVE_LIMIT);
        }
        List<String> names = new ArrayList<>(); List<byte[]> files = new ArrayList<>();
        boolean zip = name.toLowerCase(Locale.ROOT).endsWith(".zip") ||
                (selected.length >= 4 && selected[0] == 0x50 && selected[1] == 0x4b && selected[2] == 3 && selected[3] == 4);
        if (!zip) {
            if (!candidate(name) || selected.length > FILE_LIMIT)
                throw new IOException("Elegir .zip, .mid, .midi o .bin (hasta 16 MB por archivo)");
            files.add(selected); names.add(name);
        } else {
            long expanded = 0; int entries = 0;
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(selected))) {
                ZipEntry entry;
                byte[] scratch = new byte[8192];
                while ((entry = in.getNextEntry()) != null) {
                    if (++entries > 512) throw new IOException("ZIP: demasiadas entradas");
                    boolean keep = !entry.isDirectory() && candidate(entry.getName());
                    ByteArrayOutputStream out = keep ? new ByteArrayOutputStream() : null;
                    int n, size = 0;
                    // Count EVERY expanded entry, including skipped documents and other files.
                    while ((n = in.read(scratch)) != -1) {
                        if (n == 0) continue;
                        expanded += n; size += n;
                        if (expanded > ARCHIVE_LIMIT || (keep && size > FILE_LIMIT))
                            throw new IOException("ZIP supera el limite de descompresion");
                        if (keep) out.write(scratch, 0, n);
                    }
                    if (keep) {
                        if (files.size() >= 64) throw new IOException("ZIP: demasiados candidatos ROM");
                        files.add(out.toByteArray()); names.add(entry.getName());
                    }
                    in.closeEntry();
                }
            }
        }
        if (files.isEmpty()) throw new IOException("No se encontro firmware MIDI/BIN dentro del archivo");
        return new Bundle(files, names);
    }
    private static boolean candidate(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".bin") || lower.endsWith(".mid") || lower.endsWith(".midi");
    }
    private static byte[] readBytes(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) {
            if (n == 0) continue;
            if (out.size() > limit - n) throw new IOException("Archivo demasiado grande");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
