package com.rinoize.rigear;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.CRC32;

/** Private on-device library. All file operations run on the storage executor, not the audio/UI thread. */
public final class SoundLibrary {
    private static final int MAGIC = 0x52474c42, VERSION = 1, MAX_FILE = 64 * 1024 * 1024;
    public static final class Entry {
        public final String id, source, collection;
        public final boolean factory, favorite;
        public final long used;
        public final byte[] data;
        public Entry(String id, String source, String collection, boolean factory, boolean favorite, long used, byte[] data) {
            byte[] primary = SoundCodec.primary(data);
            if (data.length > SoundCodec.SIZE * 17) throw new IllegalArgumentException("Sonido demasiado grande");
            this.id = id; this.source = source; this.collection = collection;
            this.factory = factory; this.favorite = favorite; this.used = used; this.data = data.clone();
        }
        public String name() { return SoundCodec.name(SoundCodec.primary(data)); }
        public boolean multi() { return SoundCodec.primary(data)[6] == 0x11; }
        public String category() { return SoundCodec.category(SoundCodec.primary(data)); }
        public String slot() { byte[] p = SoundCodec.primary(data); return factory ? String.format(Locale.ROOT, "%c%03d", 'A' + Math.max(0, p[7] - 1), (p[8] & 127) + 1) : multi() ? "MULTI" : "USER"; }
        Entry with(boolean favorite, long used, String collection, byte[] data) {
            return new Entry(id, source, collection, factory, favorite, used, data);
        }
    }
    private final File file;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final LinkedHashMap<String, ArrayList<String>> sets = new LinkedHashMap<>();
    public SoundLibrary(File file) throws IOException {
        this.file = file;
        if (file.exists()) decode(Files.readAllBytes(file.toPath()));
    }
    public synchronized List<Entry> all() { return new ArrayList<>(entries.values()); }
    public synchronized Entry get(String id) { return entries.get(id); }
    public synchronized List<String> setNames() { return new ArrayList<>(sets.keySet()); }
    public synchronized List<Entry> set(String name) {
        List<Entry> result = new ArrayList<>();
        for (String id : sets.getOrDefault(name, new ArrayList<>())) if (entries.containsKey(id)) result.add(entries.get(id));
        return result;
    }
    public synchronized int indexFactory(byte[][] sounds, String firmware) throws IOException {
        int added = 0;
        for (byte[] data : sounds) {
            if (data == null) continue;
            String id = "f:" + SoundCodec.hash((firmware + ":" + SoundCodec.hash(data)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!entries.containsKey(id)) {
                if (entries.size() >= 8192) throw new IOException("Biblioteca llena (8192 entradas)");
                entries.put(id, new Entry(id, firmware, "Factory", true, false, 0, data)); ++added;
            }
        }
        if (added > 0) save(); return added;
    }
    public synchronized Entry add(byte[] data, String source, String collection, boolean unique) throws IOException {
        String id = unique ? "u:" + UUID.randomUUID() : "i:" + SoundCodec.hash(data);
        Entry old = entries.get(id); if (old != null) return old;
        if (entries.size() >= 8192) throw new IOException("Biblioteca llena (8192 entradas)");
        Entry entry = new Entry(id, source, collection, false, false, 0, data);
        entries.put(id, entry); save(); return entry;
    }
    public synchronized void favorite(String id) throws IOException {
        Entry e = required(id); entries.put(id, e.with(!e.favorite, e.used, e.collection, e.data)); save();
    }
    public synchronized void used(String id) throws IOException {
        Entry e = required(id); entries.put(id, e.with(e.favorite, System.currentTimeMillis(), e.collection, e.data)); save();
    }
    public synchronized void rename(String id, String name) throws IOException {
        Entry e = editable(id); entries.put(id, e.with(e.favorite, e.used, e.collection, SoundCodec.rename(e.data, name))); save();
    }
    public synchronized void move(String id, String collection) throws IOException {
        Entry e = editable(id); String clean = checkedText(collection);
        entries.put(id, e.with(e.favorite, e.used, clean, e.data)); save();
    }
    public synchronized void delete(String id) throws IOException {
        editable(id); entries.remove(id); for (List<String> list : sets.values()) list.remove(id); save();
    }
    public synchronized void addToSet(String name, String id) throws IOException {
        required(id); name = checkedText(name);
        ArrayList<String> list = sets.computeIfAbsent(name, key -> new ArrayList<>());
        if (!list.contains(id)) list.add(id); save();
    }
    public synchronized void reorder(String name, String id, int step) throws IOException {
        ArrayList<String> list = sets.get(name); if (list == null) return;
        int from = list.indexOf(id), to = from + step;
        if (from >= 0 && to >= 0 && to < list.size()) { Collections.swap(list, from, to); save(); }
    }
    public synchronized void removeFromSet(String name, String id) throws IOException {
        if (sets.containsKey(name)) { sets.get(name).remove(id); save(); }
    }
    private Entry required(String id) { Entry e = entries.get(id); if (e == null) throw new IllegalArgumentException("Sonido no encontrado"); return e; }
    private Entry editable(String id) { Entry e = required(id); if (e.factory) throw new IllegalArgumentException("Factory es solo lectura. Crear una copia de usuario."); return e; }
    private static String checkedText(String s) { s = s.trim(); if (s.isEmpty() || s.length() > 100) throw new IllegalArgumentException("Nombre de 1 a 100 caracteres"); return s; }
    public synchronized byte[] backup() throws IOException { return encode(); }
    public synchronized int mergeBackup(byte[] data) throws IOException {
        SoundLibrary incoming = new SoundLibrary(new File(file.getParentFile(), "__not_a_library__"));
        incoming.decode(data); int added = 0;
        if (entries.size() + incoming.entries.size() > 16384) throw new IOException("Respaldo demasiado grande");
        for (Entry e : incoming.entries.values()) {
            Entry old = entries.get(e.id);
            if (old == null) {
                if (entries.size() >= 8192) throw new IOException("Biblioteca llena");
                entries.put(e.id, e); ++added;
            } else if (e.favorite && !old.favorite) entries.put(old.id, old.with(true, Math.max(old.used, e.used), old.collection, old.data));
        }
        for (Map.Entry<String, ArrayList<String>> set : incoming.sets.entrySet()) {
            ArrayList<String> ids = sets.computeIfAbsent(set.getKey(), key -> new ArrayList<>());
            for (String id : set.getValue()) if (entries.containsKey(id) && !ids.contains(id)) ids.add(id);
        }
        save(); return added;
    }
    private byte[] encode() throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(payload); out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(entries.size());
        for (Entry e : entries.values()) {
            out.writeUTF(e.id); out.writeUTF(e.source); out.writeUTF(e.collection); out.writeBoolean(e.factory); out.writeBoolean(e.favorite);
            out.writeLong(e.used); out.writeInt(e.data.length); out.write(e.data);
        }
        out.writeInt(sets.size());
        for (Map.Entry<String, ArrayList<String>> set : sets.entrySet()) {
            out.writeUTF(set.getKey()); out.writeInt(set.getValue().size()); for (String id : set.getValue()) out.writeUTF(id);
        }
        out.flush(); byte[] body = payload.toByteArray();
        if (body.length > MAX_FILE - 8) throw new IOException("Biblioteca demasiado grande");
        CRC32 crc = new CRC32(); crc.update(body); out.writeLong(crc.getValue()); out.flush(); return payload.toByteArray();
    }
    private void decode(byte[] bytes) throws IOException {
        if (bytes.length < 24 || bytes.length > MAX_FILE) throw new IOException("Respaldo de biblioteca inválido");
        CRC32 crc = new CRC32(); crc.update(bytes, 0, bytes.length - 8);
        DataInputStream checksum = new DataInputStream(new ByteArrayInputStream(bytes, bytes.length - 8, 8));
        if (checksum.readLong() != crc.getValue()) throw new IOException("Biblioteca dañada: checksum incorrecto. No se sobrescribió.");
        LinkedHashMap<String, Entry> next = new LinkedHashMap<>(); LinkedHashMap<String, ArrayList<String>> nextSets = new LinkedHashMap<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - 8))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Formato de biblioteca no compatible");
            int count = bounded(in.readInt(), 0, 8192);
            for (int i = 0; i < count; ++i) {
                String id = in.readUTF(), source = in.readUTF(), collection = in.readUTF(); boolean factory = in.readBoolean(), favorite = in.readBoolean();
                long used = in.readLong(); int length = bounded(in.readInt(), 267, 267 * 17); byte[] data = new byte[length]; in.readFully(data);
                if (id.length() > 150 || source.length() > 500 || collection.length() > 100 || next.containsKey(id)) throw new IOException("Metadatos inválidos");
                next.put(id, new Entry(id, source, collection, factory, favorite, used, data));
            }
            int setCount = bounded(in.readInt(), 0, 256);
            for (int i = 0; i < setCount; ++i) {
                String name = checkedText(in.readUTF()); int countIds = bounded(in.readInt(), 0, 8192); ArrayList<String> ids = new ArrayList<>();
                for (int j = 0; j < countIds; ++j) { String id = in.readUTF(); if (next.containsKey(id) && !ids.contains(id)) ids.add(id); }
                nextSets.put(name, ids);
            }
            if (in.available() != 0) throw new IOException("Contenido extra en biblioteca");
        } catch (IllegalArgumentException e) { throw new IOException("Contenido de biblioteca inválido", e); }
        entries.clear(); entries.putAll(next); sets.clear(); sets.putAll(nextSets);
    }
    private static int bounded(int n, int min, int max) throws IOException { if (n < min || n > max) throw new IOException("Límite de biblioteca excedido"); return n; }
    private void save() throws IOException {
        byte[] bytes = encode(); File dir = file.getParentFile(); if (!dir.exists() && !dir.mkdirs()) throw new IOException("No se pudo crear biblioteca");
        File tmp = new File(dir, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(bytes); out.getFD().sync(); }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
