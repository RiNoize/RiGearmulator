package com.rinoize.riostirus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** ROM singles with the same metadata the OsTIrus plugin's patch manager uses:
 * Category 1/2 (page B 123/124), model from the preset version, and the Arp, Unison,
 * Chorus and Phaser tags. Pure Java, no Android types: unit-testable on the host. */
public final class PatchLibrary {
    /** Category names from parameterDescriptions_TI.json, index 0 = none. */
    public static final String[] CATEGORIES = {"--", "Lead", "Bass", "Pad", "Decay", "Pluck", "Acid", "Classic",
            "Arpeggiator", "Effects", "Drums", "Percussion", "Input", "Vocoder", "Favourite 1", "Favourite 2",
            "Favourite 3", "Organ", "Piano", "String", "FM", "Digital", "Atomizer"};
    public static final int TAG_ARP = 1, TAG_UNISON = 2, TAG_CHORUS = 4, TAG_PHASER = 8;
    public static final String[] TAG_NAMES = {"Arp", "Unison", "Chorus", "Phaser"};
    public static final int[] TAG_BITS = {TAG_ARP, TAG_UNISON, TAG_CHORUS, TAG_PHASER};

    public static final class Patch {
        public final int bank, program, category1, category2, version, flags;
        public final String name, nameLower;
        Patch(int bank, int program, String name, int c1, int c2, int version, int flags) {
            this.bank = bank; this.program = program; this.name = name; nameLower = name.toLowerCase(Locale.ROOT);
            category1 = c1; category2 = c2; this.version = version; this.flags = flags;
        }
        public String key() { return bank + ":" + program; }
        public boolean hasCategory(int c) { return category1 == c || category2 == c; }
        /** TI2 presets use version 0x0C or higher (virusLib PresetVersion::D2). */
        public String model() { return version >= 0x0c ? "TI2" : version >= 0x08 ? "TI" : "A/B/C"; }
        public String categories() {
            String a = category1 > 0 && category1 < CATEGORIES.length ? CATEGORIES[category1] : "";
            String b = category2 > 0 && category2 < CATEGORIES.length && category2 != category1 ? CATEGORIES[category2] : "";
            return a.isEmpty() ? b : b.isEmpty() ? a : a + " · " + b;
        }
    }

    public enum Source { ALL, FAVORITES, RECENT, BANK }
    public enum Sort { BANK, NAME }

    /** Current filter. Category 0 = all; tags are AND-combined. */
    public static final class Filter {
        public Source source = Source.ALL;
        public int bank = -1, category = 0, tags = 0;
        public String query = "";
        public Sort sort = Sort.BANK;
        /** The TI, TI2 and Snow sets repeat most sounds: show each name once (first bank wins).
         * Not applied inside a single bank or to the recent list. */
        public boolean unique = true;
    }

    private final List<Patch> all = new ArrayList<>();
    private int bankCount;

    public static PatchLibrary parse(String text) {
        PatchLibrary library = new PatchLibrary();
        if (text == null) return library;
        for (String line : text.split("\n")) {
            String[] f = line.split("\t");
            if (f.length != 7) continue;
            try {
                Patch p = new Patch(Integer.parseInt(f[0]), Integer.parseInt(f[1]), f[2],
                        Integer.parseInt(f[3]), Integer.parseInt(f[4]), Integer.parseInt(f[5]), Integer.parseInt(f[6]));
                library.all.add(p);
                library.bankCount = Math.max(library.bankCount, p.bank + 1);
            } catch (NumberFormatException ignored) {}
        }
        return library;
    }

    public int size() { return all.size(); }
    public int bankCount() { return bankCount; }
    public List<Patch> all() { return Collections.unmodifiableList(all); }

    public Patch find(int bank, int program) {
        for (Patch p : all) if (p.bank == bank && p.program == program) return p;
        return null;
    }

    /** Same names as the plugin: 26 banks or fewer are lettered; a full TI firmware lists
     * TI (19), then TI2 (25), then Snow ROM banks. */
    public String bankName(int bank) {
        if (bankCount <= 26) return "Banco " + (char) ('A' + bank);
        if (bank < 19) return "TI " + (char) ('A' + bank);
        if (bank < 19 + 25) return "TI2 " + (char) ('A' + bank - 19);
        return "Snow " + (char) ('A' + bank - 44);
    }
    public String bankShort(int bank) {
        if (bankCount <= 26) return String.valueOf((char) ('A' + bank));
        if (bank < 19) return "TI-" + (char) ('A' + bank);
        if (bank < 44) return "T2-" + (char) ('A' + bank - 19);
        return "SN-" + (char) ('A' + bank - 44);
    }
    public String slot(Patch p) { return String.format(Locale.ROOT, "%s %03d", bankShort(p.bank), p.program + 1); }

    /** Applies source, category, tags and search; recent keeps most-recent-first order. */
    public List<Patch> filter(Filter f, Set<String> favorites, List<String> recent) {
        List<Patch> out = new ArrayList<>();
        String q = f.query == null ? "" : f.query.trim().toLowerCase(Locale.ROOT);
        if (f.source == Source.RECENT) {
            for (String key : recent) {
                Patch p = byKey(key);
                if (p != null && matches(p, f, q)) out.add(p);
            }
            return out;
        }
        boolean unique = f.unique && f.source != Source.BANK;
        Set<String> seen = unique ? new java.util.HashSet<>() : null;
        for (Patch p : all) {
            if (f.source == Source.FAVORITES && !favorites.contains(p.key())) continue;
            if (f.source == Source.BANK && p.bank != f.bank) continue;
            if (!matches(p, f, q)) continue;
            if (unique && !seen.add(p.name)) continue;
            out.add(p);
        }
        if (f.sort == Sort.NAME) out.sort(Comparator.comparing((Patch p) -> p.nameLower).thenComparingInt(p -> p.bank * 128 + p.program));
        return out;
    }

    /** Count per category for the current source, search and tags (category filter ignored). */
    public int[] categoryCounts(Filter f, Set<String> favorites, List<String> recent) {
        Filter any = copy(f); any.category = 0;
        int[] counts = new int[CATEGORIES.length];
        for (Patch p : filter(any, favorites, recent)) {
            if (p.category1 > 0 && p.category1 < counts.length) counts[p.category1]++;
            if (p.category2 > 0 && p.category2 < counts.length && p.category2 != p.category1) counts[p.category2]++;
            counts[0]++;
        }
        return counts;
    }

    public static Filter copy(Filter f) {
        Filter c = new Filter();
        c.source = f.source; c.bank = f.bank; c.category = f.category; c.tags = f.tags; c.query = f.query; c.sort = f.sort;
        c.unique = f.unique;
        return c;
    }

    private static boolean matches(Patch p, Filter f, String q) {
        if (f.category > 0 && !p.hasCategory(f.category)) return false;
        if ((p.flags & f.tags) != f.tags) return false;
        return q.isEmpty() || p.nameLower.contains(q);
    }

    private Patch byKey(String key) {
        int colon = key.indexOf(':');
        if (colon <= 0) return null;
        try { return find(Integer.parseInt(key.substring(0, colon)), Integer.parseInt(key.substring(colon + 1))); }
        catch (NumberFormatException e) { return null; }
    }

    /** Most recent first, unique, bounded. */
    public static List<String> pushRecent(List<String> recent, String key, int limit) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        ordered.add(key); ordered.addAll(recent);
        List<String> out = new ArrayList<>(ordered);
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }
}
