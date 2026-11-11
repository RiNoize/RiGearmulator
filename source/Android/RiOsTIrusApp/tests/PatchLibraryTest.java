import com.rinoize.riostirus.PatchLibrary;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Host test: javac -d out app/src/main/java/com/rinoize/riostirus/PatchLibrary.java tests/PatchLibraryTest.java */
public final class PatchLibraryTest {
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        String text = String.join("\n",
                "0\t0\tHyperLead\t1\t6\t8\t1",     // Lead + Acid, TI, Arp
                "0\t1\tFat Bass\t2\t0\t8\t2",      // Bass, Unison
                "0\t2\tSoft Pad\t3\t19\t12\t4",    // Pad + String, TI2, Chorus
                "1\t0\tAcid Line\t6\t2\t12\t9",    // Acid + Bass, Arp + Phaser
                "1\t1\tNo Cat\t0\t0\t8\t0",
                "bad line",
                "2\tx\tBroken\t0\t0\t0\t0") + "\n";
        PatchLibrary lib = PatchLibrary.parse(text);
        require(lib.size() == 5, "parse skips malformed lines: " + lib.size());
        require(lib.bankCount() == 2, "bank count");
        require(lib.bankName(1).equals("Banco B"), "small firmware letters banks");
        require(lib.find(0, 2).model().equals("TI2") && lib.find(0, 0).model().equals("TI"), "model from version");
        require(lib.find(0, 0).categories().equals("Lead · Acid"), "categories text");
        require(lib.find(1, 1).categories().isEmpty(), "no category");

        Set<String> fav = new HashSet<>(Arrays.asList("0:1", "1:0"));
        List<String> recent = new ArrayList<>(Arrays.asList("1:1", "0:0", "9:9"));
        PatchLibrary.Filter f = new PatchLibrary.Filter();
        require(lib.filter(f, fav, recent).size() == 5, "all");

        f.category = 2;
        List<PatchLibrary.Patch> bass = lib.filter(f, fav, recent);
        require(bass.size() == 2 && bass.get(0).name.equals("Fat Bass") && bass.get(1).name.equals("Acid Line"), "category matches cat1 or cat2");

        int[] counts = lib.categoryCounts(f, fav, recent);
        require(counts[0] == 5 && counts[2] == 2 && counts[6] == 2 && counts[3] == 1, "counts ignore category filter");

        f.category = 0; f.unique = false; f.tags = PatchLibrary.TAG_ARP;
        require(lib.filter(f, fav, recent).size() == 2, "arp tag");
        f.tags = PatchLibrary.TAG_ARP | PatchLibrary.TAG_PHASER;
        require(lib.filter(f, fav, recent).size() == 1, "tags are AND-combined");

        f.tags = 0; f.query = "  aci ";
        require(lib.filter(f, fav, recent).size() == 1, "search trims and ignores case");

        f.query = ""; f.source = PatchLibrary.Source.FAVORITES;
        require(lib.filter(f, fav, recent).size() == 2, "favourites");
        f.source = PatchLibrary.Source.BANK; f.bank = 1;
        require(lib.filter(f, fav, recent).size() == 2, "bank source");
        f.source = PatchLibrary.Source.RECENT;
        List<PatchLibrary.Patch> r = lib.filter(f, fav, recent);
        require(r.size() == 2 && r.get(0).name.equals("No Cat") && r.get(1).name.equals("HyperLead"), "recent keeps order, skips unknown");

        f.source = PatchLibrary.Source.ALL; f.sort = PatchLibrary.Sort.NAME;
        require(lib.filter(f, fav, recent).get(0).name.equals("Acid Line"), "sort by name");

        List<String> pushed = PatchLibrary.pushRecent(recent, "0:0", 2);
        require(pushed.equals(Arrays.asList("0:0", "1:1")), "push moves to front, unique, bounded: " + pushed);

        PatchLibrary dup = PatchLibrary.parse("0\t0\tSame\t1\t0\t8\t0\n1\t5\tSame\t1\t0\t9\t0\n1\t6\tOther\t1\t0\t9\t0\n");
        PatchLibrary.Filter u = new PatchLibrary.Filter();
        List<PatchLibrary.Patch> uniq = dup.filter(u, fav, recent);
        require(uniq.size() == 2 && uniq.get(0).bank == 0, "duplicates hidden, first bank wins");
        require(dup.categoryCounts(u, fav, recent)[1] == 2, "counts follow duplicate filter");
        u.unique = false;
        require(dup.filter(u, fav, recent).size() == 3, "duplicates shown when disabled");
        u.unique = true; u.source = PatchLibrary.Source.BANK; u.bank = 1;
        require(dup.filter(u, fav, recent).size() == 2, "a bank always shows all its sounds");

        StringBuilder big = new StringBuilder();
        for (int b = 0; b < 52; b++) big.append(b).append("\t0\tX\t0\t0\t8\t0\n");
        PatchLibrary full = PatchLibrary.parse(big.toString());
        require(full.bankName(0).equals("TI A") && full.bankName(18).equals("TI S"), "TI ROM banks");
        require(full.bankName(19).equals("TI2 A") && full.bankName(43).equals("TI2 Y"), "TI2 ROM banks");
        require(full.bankName(44).equals("Snow A") && full.bankName(51).equals("Snow H"), "Snow ROM banks");
        System.out.println("PatchLibraryTest: parse, categories, tags, search, collections, sort, recent and bank names passed");
    }
}
