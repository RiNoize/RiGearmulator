package com.rinoize.riostirus;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** BIBLIOTECA, modelled on the OsTIrus plugin's patch manager: collections (all, favourites,
 * recent, each ROM bank), categories with counts, Arp/Unison/Chorus/Phaser tags, name search
 * and sort. Tap loads the sound immediately; long-press toggles the favourite. */
final class LibraryPanel {
    interface Host {
        PatchLibrary library();
        Set<String> favorites();
        List<String> recent();
        String currentKey();
        void select(PatchLibrary.Patch patch);
        void toggleFavorite(PatchLibrary.Patch patch);
        void filterChanged(List<PatchLibrary.Patch> results, String description);
    }

    final LinearLayout view;
    final PatchLibrary.Filter filter = new PatchLibrary.Filter();
    private final Context c;
    private final Host host;
    private final LinearLayout collections, categories, tags;
    private final TextView count;
    private final TextView sortButton;
    private final GridView grid;
    private final Adapter adapter = new Adapter();
    private List<PatchLibrary.Patch> results = new ArrayList<>();

    LibraryPanel(Context context, Host host) {
        c = context; this.host = host;
        view = Snow.row(c); view.setGravity(Gravity.FILL_VERTICAL);

        // Left: collections
        LinearLayout left = Snow.card(c, "Colecciones");
        ScrollView leftScroll = new ScrollView(c); collections = Snow.column(c); leftScroll.addView(collections);
        left.addView(leftScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        view.addView(left, new LinearLayout.LayoutParams(Snow.dp(c, 190), -1));

        // Right: search, categories, tags, grid
        LinearLayout right = Snow.column(c);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 1); rp.setMargins(Snow.dp(c, 8), 0, 0, 0);
        view.addView(right, rp);

        LinearLayout searchRow = Snow.row(c);
        EditText search = new EditText(c);
        search.setHint("Buscar por nombre…"); search.setSingleLine(true); search.setTextSize(15);
        search.setTextColor(Snow.TEXT); search.setHintTextColor(Snow.FAINT);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setPadding(Snow.dp(c, 12), 0, Snow.dp(c, 12), 0);
        search.setBackground(Snow.surface(c, Snow.PANEL, Snow.EDGE, 8));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            public void onTextChanged(CharSequence s, int a, int b, int d) {}
            public void afterTextChanged(Editable s) { filter.query = s.toString(); refresh(); }
        });
        searchRow.addView(search, new LinearLayout.LayoutParams(0, Snow.dp(c, 42), 1));
        sortButton = Snow.chip(c, "Orden: banco", false, v -> {
            filter.sort = filter.sort == PatchLibrary.Sort.BANK ? PatchLibrary.Sort.NAME : PatchLibrary.Sort.BANK; refresh();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, Snow.dp(c, 42)); sp.setMargins(Snow.dp(c, 8), 0, 0, 0);
        searchRow.addView(sortButton, sp);
        count = Snow.text(c, "", 13, Snow.MUTED); count.setPadding(Snow.dp(c, 12), 0, Snow.dp(c, 4), 0);
        searchRow.addView(count);
        right.addView(searchRow);

        HorizontalScrollView catScroll = new HorizontalScrollView(c); catScroll.setHorizontalScrollBarEnabled(false);
        categories = Snow.row(c); categories.setPadding(0, Snow.dp(c, 8), 0, 0); catScroll.addView(categories);
        right.addView(catScroll);
        tags = Snow.row(c); tags.setPadding(0, Snow.dp(c, 6), 0, Snow.dp(c, 6));
        right.addView(tags);

        grid = new GridView(c);
        grid.setColumnWidth(Snow.dp(c, 196)); grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Snow.dp(c, 6)); grid.setVerticalSpacing(Snow.dp(c, 6));
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setAdapter(adapter);
        grid.setFastScrollEnabled(true);
        grid.setOnItemClickListener((parent, v, position, id) -> host.select(results.get(position)));
        grid.setOnItemLongClickListener((parent, v, position, id) -> { host.toggleFavorite(results.get(position)); return true; });
        right.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView hint = Snow.text(c, "Tocá un patch para cargarlo · mantené apretado para marcarlo como favorito ★", 11, Snow.MUTED);
        hint.setPadding(0, Snow.dp(c, 4), 0, 0);
        right.addView(hint);
    }

    /** Rebuild everything: call after the library, favourites or recent list change. */
    void refresh() {
        PatchLibrary library = host.library();
        Set<String> favorites = host.favorites();
        List<String> recent = host.recent();
        if (filter.source == PatchLibrary.Source.BANK && filter.bank >= library.bankCount()) {
            filter.source = PatchLibrary.Source.ALL; filter.bank = -1;
        }
        results = library.filter(filter, favorites, recent);
        buildCollections(library, favorites, recent);
        buildCategories(library.categoryCounts(filter, favorites, recent));
        buildTags();
        sortButton.setText(filter.sort == PatchLibrary.Sort.BANK ? "Orden: banco" : "Orden: nombre");
        count.setText(String.format(Locale.ROOT, "%d patches", results.size()));
        adapter.notifyDataSetChanged();
        host.filterChanged(results, description(library));
    }

    /** Repaint selection/favourite marks without refiltering. */
    void repaint() { adapter.notifyDataSetChanged(); }

    void reveal(String key) {
        for (int i = 0; i < results.size(); i++) if (results.get(i).key().equals(key)) { grid.smoothScrollToPosition(i); return; }
    }

    String description(PatchLibrary library) {
        String source;
        switch (filter.source) {
            case FAVORITES: source = "Favoritos"; break;
            case RECENT: source = "Recientes"; break;
            case BANK: source = library.bankName(filter.bank); break;
            default: source = "Todos";
        }
        StringBuilder out = new StringBuilder(source);
        if (filter.category > 0) out.append(" · ").append(PatchLibrary.CATEGORIES[filter.category]);
        for (int i = 0; i < PatchLibrary.TAG_BITS.length; i++)
            if ((filter.tags & PatchLibrary.TAG_BITS[i]) != 0) out.append(" · ").append(PatchLibrary.TAG_NAMES[i]);
        if (filter.query != null && !filter.query.trim().isEmpty()) out.append(" · “").append(filter.query.trim()).append("”");
        return out.toString();
    }

    private void buildCollections(PatchLibrary library, Set<String> favorites, List<String> recent) {
        collections.removeAllViews();
        collections.addView(entry("Todos", library.size(), filter.source == PatchLibrary.Source.ALL, () -> {
            filter.source = PatchLibrary.Source.ALL; filter.bank = -1; refresh(); }));
        collections.addView(entry("★ Favoritos", favorites.size(), filter.source == PatchLibrary.Source.FAVORITES, () -> {
            filter.source = PatchLibrary.Source.FAVORITES; filter.bank = -1; refresh(); }));
        collections.addView(entry("Recientes", recent.size(), filter.source == PatchLibrary.Source.RECENT, () -> {
            filter.source = PatchLibrary.Source.RECENT; filter.bank = -1; refresh(); }));
        TextView banks = Snow.label(c, "Bancos ROM"); banks.setPadding(Snow.dp(c, 4), Snow.dp(c, 12), 0, Snow.dp(c, 4));
        collections.addView(banks);
        for (int b = 0; b < library.bankCount(); b++) {
            final int bank = b;
            collections.addView(entry(library.bankName(b), -1,
                    filter.source == PatchLibrary.Source.BANK && filter.bank == b, () -> {
                        filter.source = PatchLibrary.Source.BANK; filter.bank = bank; refresh(); }));
        }
    }

    private View entry(String label, int n, boolean selected, Runnable action) {
        LinearLayout row = Snow.row(c);
        row.setPadding(Snow.dp(c, 10), 0, Snow.dp(c, 8), 0); row.setMinimumHeight(Snow.dp(c, 38));
        row.setBackground(Snow.surface(c, selected ? Snow.RED_SOFT : 0, selected ? Snow.RED : 0, 6));
        TextView t = Snow.text(c, label, 14, selected ? Snow.RED : Snow.TEXT); t.setTypeface(selected ? Snow.BOLD : Snow.MEDIUM);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (n >= 0) row.addView(Snow.text(c, Integer.toString(n), 12, Snow.MUTED));
        row.setClickable(true); row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, Snow.dp(c, 2), 0, 0);
        row.setLayoutParams(p);
        return row;
    }

    private void buildCategories(int[] counts) {
        categories.removeAllViews();
        addChip(categories, "Todas " + counts[0], filter.category == 0, () -> { filter.category = 0; refresh(); });
        for (int i = 1; i < PatchLibrary.CATEGORIES.length; i++) {
            if (counts[i] == 0 && filter.category != i) continue;
            final int category = i;
            addChip(categories, PatchLibrary.CATEGORIES[i] + " " + counts[i], filter.category == i, () -> {
                filter.category = filter.category == category ? 0 : category; refresh(); });
        }
    }

    private void buildTags() {
        tags.removeAllViews();
        TextView title = Snow.label(c, "Etiquetas"); title.setPadding(0, 0, Snow.dp(c, 8), 0); tags.addView(title);
        for (int i = 0; i < PatchLibrary.TAG_BITS.length; i++) {
            final int bit = PatchLibrary.TAG_BITS[i];
            addChip(tags, PatchLibrary.TAG_NAMES[i], (filter.tags & bit) != 0, () -> { filter.tags ^= bit; refresh(); });
        }
        View spacer = new View(c); tags.addView(spacer, new LinearLayout.LayoutParams(Snow.dp(c, 12), 1));
        addChip(tags, filter.unique ? "Sin duplicados ✓" : "Sin duplicados", filter.unique,
                () -> { filter.unique = !filter.unique; refresh(); });
    }

    private void addChip(LinearLayout parent, String label, boolean selected, Runnable action) {
        TextView chip = Snow.chip(c, label, selected, v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, Snow.dp(c, 34)); p.setMargins(0, 0, Snow.dp(c, 6), 0);
        parent.addView(chip, p);
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return results.size(); }
        @Override public Object getItem(int i) { return results.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int position, View convert, ViewGroup parent) {
            Cell cell = convert instanceof Cell ? (Cell) convert : new Cell(c);
            PatchLibrary.Patch p = results.get(position);
            cell.bind(host.library().slot(p), p.name, p.categories(), host.favorites().contains(p.key()),
                    p.key().equals(host.currentKey()));
            return cell;
        }
    }

    private static final class Cell extends LinearLayout {
        private final TextView slot, name, cats;
        Cell(Context c) {
            super(c); setOrientation(VERTICAL);
            setPadding(Snow.dp(c, 10), Snow.dp(c, 7), Snow.dp(c, 10), Snow.dp(c, 7));
            slot = Snow.text(c, "", 11, Snow.MUTED); addView(slot);
            name = Snow.text(c, "", 17, Snow.TEXT); name.setTypeface(Snow.CONDENSED); name.setSingleLine(true);
            name.setPadding(0, Snow.dp(c, 3), 0, Snow.dp(c, 3)); addView(name);
            cats = Snow.text(c, "", 11, Snow.MUTED); cats.setSingleLine(true); addView(cats);
        }
        void bind(String slotText, String patch, String categories, boolean favorite, boolean current) {
            slot.setText(favorite ? slotText + "   ★" : slotText);
            slot.setTextColor(favorite || current ? Snow.RED : Snow.MUTED);
            name.setText(patch);
            name.setTextColor(current ? Snow.RED_DARK : Snow.TEXT);
            cats.setText(categories.isEmpty() ? "Sin categoría" : categories);
            setBackground(Snow.surface(getContext(), current ? Snow.RED_SOFT : Snow.PANEL, current ? Snow.RED : Snow.EDGE, 8));
        }
    }
}
