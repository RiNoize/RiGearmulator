package com.rinoize.riostirus;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** TOCAR: patch display with navigation and 25 performance knobs.
 * Pages and indices come from osTIrusJucePlugin/parameterDescriptions_TI.json
 * (112 = page A 0x70, 113 = page B 0x71, 110 = page 0x6E). */
final class PlayPanel {
    static final int A = 0x70, B = 0x71, P6E = 0x6e;

    static final class Param {
        final int page, index, min, max; final String label; final boolean bipolar;
        Param(int page, int index, String label, boolean bipolar) { this(page, index, label, 0, 127, bipolar); }
        Param(int page, int index, String label, int min, int max, boolean bipolar) {
            this.page = page; this.index = index; this.label = label; this.min = min; this.max = max; this.bipolar = bipolar;
        }
    }
    static final class Section {
        final String title; final Param[] params;
        Section(String title, Param... params) { this.title = title; this.params = params; }
    }

    static final Section[] TOP = {
        new Section("Osciladores",
            new Param(A, 17, "Shape 1", true), new Param(A, 22, "Shape 2", true), new Param(A, 33, "Balance", true),
            new Param(A, 34, "Sub", false), new Param(A, 37, "Noise", false)),
        new Section("Filtros",
            new Param(A, 40, "Cutoff", false), new Param(A, 42, "Reso", false), new Param(A, 44, "Env Amt", false),
            new Param(A, 46, "Keyfollow", true), new Param(A, 41, "Cutoff 2", false)),
        new Section("Master",
            new Param(A, 67, "LFO1 Rate", false), new Param(A, 5, "Glide", false), new Param(A, 91, "Volume", false)),
    };
    static final Section[] BOTTOM = {
        new Section("Env filtro",
            new Param(A, 54, "Attack", false), new Param(A, 55, "Decay", false),
            new Param(A, 56, "Sustain", false), new Param(A, 58, "Release", false)),
        new Section("Env amp",
            new Param(A, 59, "Attack", false), new Param(A, 60, "Decay", false),
            new Param(A, 61, "Sustain", false), new Param(A, 63, "Release", false)),
        new Section("Efectos",
            new Param(A, 105, "Chorus", false), new Param(A, 113, "Delay", false),
            new Param(P6E, 2, "Reverb", false), new Param(B, 101, "Distort", false)),
    };

    interface Host {
        void step(int delta);
        void bankStep(int delta);
        void toggleFavoriteCurrent();
        void openLibrary();
        boolean sendParameter(int page, int index, int value);
    }

    final LinearLayout view;
    final int[] pages, indices;
    private final List<SnowKnob> knobs = new ArrayList<>();
    private final TextView slot, name, info;
    private final Button favorite;
    private int revision = -1;

    PlayPanel(Context c, Host host) {
        List<Param> all = new ArrayList<>();
        for (Section s : TOP) for (Param p : s.params) all.add(p);
        for (Section s : BOTTOM) for (Param p : s.params) all.add(p);
        pages = new int[all.size()]; indices = new int[all.size()];
        for (int i = 0; i < all.size(); i++) { pages[i] = all.get(i).page; indices[i] = all.get(i).index; }

        view = Snow.column(c);
        LinearLayout display = Snow.row(c);
        display.addView(nav(c, "BANK −", v -> host.bankStep(-1)));
        display.addView(nav(c, "◀ PATCH", v -> host.step(-1)));
        LinearLayout lcd = Snow.column(c); lcd.setBackground(new Snow.Lcd(c));
        lcd.setPadding(Snow.dp(c, 16), Snow.dp(c, 8), Snow.dp(c, 16), Snow.dp(c, 10)); lcd.setGravity(Gravity.CENTER_VERTICAL);
        lcd.setClickable(true); lcd.setOnClickListener(v -> host.openLibrary());
        slot = Snow.text(c, "", 13, Snow.LCD_DIM); slot.setTypeface(Snow.MEDIUM); lcd.addView(slot);
        name = Snow.text(c, "Sin patch", 30, Snow.LCD_TEXT); name.setTypeface(Snow.CONDENSED); name.setLetterSpacing(0.04f);
        name.setPadding(0, Snow.dp(c, 3), 0, Snow.dp(c, 3)); lcd.addView(name);
        info = Snow.text(c, "", 12, Snow.LCD_DIM); lcd.addView(info);
        LinearLayout.LayoutParams lcdParams = new LinearLayout.LayoutParams(0, Snow.dp(c, 96), 1);
        lcdParams.setMargins(Snow.dp(c, 6), 0, Snow.dp(c, 6), 0);
        display.addView(lcd, lcdParams);
        favorite = nav(c, "☆", v -> host.toggleFavoriteCurrent()); favorite.setTextSize(20);
        display.addView(favorite);
        display.addView(nav(c, "PATCH ▶", v -> host.step(1)));
        display.addView(nav(c, "BANK +", v -> host.bankStep(1)));
        view.addView(display);

        view.addView(sectionRow(c, TOP, host), rowParams(c));
        view.addView(sectionRow(c, BOTTOM, host), rowParams(c));
        TextView hint = Snow.text(c, "Arrastrá las perillas verticalmente · tocá una para escribir el valor exacto · tocá la pantalla para abrir la biblioteca", 11, Snow.MUTED);
        hint.setPadding(Snow.dp(c, 2), Snow.dp(c, 6), 0, 0);
        view.addView(hint);
    }

    private static Button nav(Context c, String label, View.OnClickListener l) {
        Button b = Snow.button(c, label, false, l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, Snow.dp(c, 96)); p.setMargins(0, 0, Snow.dp(c, 4), 0);
        b.setLayoutParams(p); return b;
    }

    private static LinearLayout.LayoutParams rowParams(Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 0, 1); p.setMargins(0, Snow.dp(c, 8), 0, 0); return p;
    }

    private LinearLayout sectionRow(Context c, Section[] sections, Host host) {
        LinearLayout row = Snow.row(c); row.setGravity(Gravity.FILL_VERTICAL);
        for (int s = 0; s < sections.length; s++) {
            Section section = sections[s];
            LinearLayout card = Snow.card(c, section.title);
            LinearLayout line = Snow.row(c);
            for (Param p : section.params) {
                SnowKnob knob = new SnowKnob(c, p.label, p.min, p.max, p.bipolar,
                        v -> p.bipolar ? signed(v - 64) : Integer.toString(v),
                        v -> host.sendParameter(p.page, p.index, v));
                knobs.add(knob);
                line.addView(knob, new LinearLayout.LayoutParams(0, -1, 1));
            }
            card.addView(line, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -1, section.params.length);
            if (s > 0) cp.setMargins(Snow.dp(c, 8), 0, 0, 0);
            row.addView(card, cp);
        }
        return row;
    }

    private static String signed(int v) { return v > 0 ? "+" + v : v < 0 ? "−" + (-v) : "0"; }

    /** values = RuntimeBridge.values(pages, indices): [revision, flags, bank, program, values...]. */
    void bind(int[] values, boolean enabled) {
        if (values == null || values.length != knobs.size() + 4) return;
        boolean known = (values[1] & 1) != 0;
        if (values[0] == revision && known) { for (SnowKnob k : knobs) k.setEnabled(enabled && k.value() >= 0); return; }
        revision = values[0];
        for (int i = 0; i < knobs.size(); i++) knobs.get(i).bind(known ? values[i + 4] : -1, enabled);
    }

    void showPatch(String slotText, String patchName, String infoText, boolean isFavorite) {
        if (!slotText.contentEquals(slot.getText())) slot.setText(slotText);
        if (!patchName.contentEquals(name.getText())) name.setText(patchName);
        if (!infoText.contentEquals(info.getText())) info.setText(infoText);
        favorite.setText(isFavorite ? "★" : "☆");
        Snow.style(favorite, false, isFavorite);
    }
}
