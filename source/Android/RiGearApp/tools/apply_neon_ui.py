#!/usr/bin/env python3
"""Apply the checked-in RiGear 0.12 black/blue UI integration before Android compilation.

The legacy StudioActivity remains a readable functional baseline in git. This script makes
small, asserted source transformations so the generated APK uses the richer UI without
forking the engine/control logic. A missing marker fails CI instead of silently producing
an incomplete interface.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[4]
ACTIVITY = ROOT / "source/Android/RiGearApp/app/src/main/java/com/rinoize/rigear/StudioActivity.java"
AUDIO = ROOT / "source/Android/RiGearApp/app/src/main/java/com/rinoize/rigear/RealtimeAudio.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"UI integration marker missing: {label}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    result, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"UI integration regex failed ({count}): {label}")
    return result


activity = ACTIVITY.read_text(encoding="utf-8")
activity = replace_once(activity, 'TextView brand = skin.text("RiGear 0.11", 21, StudioUi.TEXT);',
                        'TextView brand = skin.text("RiGear 0.12", 21, StudioUi.TEXT);', "version label")
activity = replace_once(activity,
    'topStats=skin.text("Inicializando biblioteca y motor…", 11, StudioUi.MUTED); root.addView(topStats);',
    'topStats=skin.text("Inicializando biblioteca y motor…", 11, StudioUi.MUTED); topStats.setTypeface(Typeface.MONOSPACE);',
    "move telemetry to bottom")
activity = replace_once(activity,
    'status=skin.text("",11,StudioUi.MUTED); status.setMaxLines(2); root.addView(status);\n'
    '        footer=skin.text("MIDI desconectado · pantalla encendida mientras RiGear esté visible",11,StudioUi.MUTED); footer.setMaxLines(2); root.addView(footer);',
    'topStats.setPadding(skin.dp(8),skin.dp(3),skin.dp(8),skin.dp(3)); topStats.setBackground(skin.box(StudioUi.BLUE,false)); root.addView(topStats);\n'
    '        status=skin.text("",11,StudioUi.MUTED); status.setMaxLines(2); root.addView(status);\n'
    '        footer=skin.text("MIDI desconectado · pantalla encendida mientras RiGear esté visible",11,StudioUi.MUTED); footer.setMaxLines(2); root.addView(footer);',
    "bottom telemetry bar")
activity = replace_once(activity, 'patchBar.setPadding(0,skin.dp(4),0,skin.dp(4));',
                        'patchBar.setPadding(0,skin.dp(5),0,skin.dp(5)); patchBar.setGravity(Gravity.CENTER_VERTICAL);',
                        "patch bar alignment")

new_editor = r'''    private View editPage(boolean fx){
        if(fx)return fxPage();
        LinearLayout result=skin.column();
        String[] groups={"Osciladores","Filtros","Envolventes","LFO / Mod","Arpegiador","Otros"};
        String[] labels={"OSC / MIX","FILTER","ENV","MOD","ARP","OTROS"};
        editGroup=Math.max(0,Math.min(editGroup,groups.length-1));
        LinearLayout tabsRow=skin.row();
        for(int i=0;i<labels.length;++i){final int groupIndex=i;Button tab=skin.button(labels[i],StudioUi.BLUE,v->{editGroup=groupIndex;showPage(1);});tab.setSelected(i==editGroup);skin.equal(tabsRow,tab);}
        result.addView(tabsRow,new LinearLayout.LayoutParams(-1,skin.dp(48)));
        LinearLayout panel=skin.card(groups[editGroup].toUpperCase(Locale.ROOT),StudioUi.BLUE);
        ScrollView scroll=new ScrollView(this);LinearLayout rows=skin.column();scroll.addView(rows);
        LinearLayout row=null;int count=0;
        for(Param p:parameters){if(!p.group.equals(groups[editGroup]))continue;if(count%4==0){row=skin.row();rows.addView(row,new LinearLayout.LayoutParams(-1,skin.dp(126)));}
            StudioUi.Dial d=dial(p.name.toUpperCase(Locale.ROOT),p.page,p.index,p.min,p.max,StudioUi.BLUE,p::format);row.addView(d,new LinearLayout.LayoutParams(0,-1,1));++count;}
        if(row!=null)while(count++%4!=0)row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        if(count==0)rows.addView(skin.text("No hay parámetros públicos en esta sección para la ROM cargada.",13,StudioUi.MUTED));
        rows.addView(skin.text("Arrastrá verticalmente cada control; un toque permite introducir el valor exacto.",11,StudioUi.MUTED));
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));result.addView(panel,new LinearLayout.LayoutParams(-1,0,1));controls();return result;
    }
    private View fxPage(){
        LinearLayout root=skin.column(),upper=skin.row(),lower=skin.row();
        skin.equal(upper,fxCard("CHORUS / PHASER",new String[]{"chorus","phaser","flanger"}));
        skin.equal(upper,fxCard("DELAY",new String[]{"delay"}));
        skin.equal(lower,fxCard("REVERB",new String[]{"reverb"}));
        skin.equal(lower,fxCard("DISTORSIÓN / COLOR",new String[]{"distortion","saturation","analog boost","bass boost","punch","eq"}));
        root.addView(upper,new LinearLayout.LayoutParams(-1,0,1));root.addView(lower,new LinearLayout.LayoutParams(-1,0,1));controls();return root;
    }
    private View fxCard(String title,String[] keywords){
        LinearLayout card=skin.card(title,StudioUi.BLUE),row=skin.row();int count=0;
        for(Param p:parameters){if(!p.group.equals("FX")||!matchesFx(p.name,keywords))continue;if(count>=4)break;
            StudioUi.Dial d=dial(p.name.toUpperCase(Locale.ROOT),p.page,p.index,p.min,p.max,StudioUi.BLUE,p::format);row.addView(d,new LinearLayout.LayoutParams(0,-1,1));++count;}
        if(count==0){TextView empty=skin.text("No disponible en esta ROM",12,StudioUi.MUTED);row.addView(empty,new LinearLayout.LayoutParams(0,-1,1));}
        while(count>0&&count++<4)row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        card.addView(row,new LinearLayout.LayoutParams(-1,0,1));return card;
    }
    private boolean matchesFx(String name,String[] keywords){String value=name.toLowerCase(Locale.ROOT);for(String keyword:keywords)if(value.contains(keyword))return true;return false;}
'''
activity = regex_once(activity,
    r'    private View editPage\(boolean fx\)\{.*?\n    private View multiPage\(\)\{',
    new_editor + '    private View multiPage(){', "Edit and FX pages")

activity = replace_once(activity, 'search.setHint("Buscar nombre, categoría o banco…");',
                        'search.setHint("Buscar patches por nombre, categoría o banco…");', "library search hint")
activity = replace_once(activity,
    'middle.addView(search,new LinearLayout.LayoutParams(-1,skin.dp(44)));',
    'skin.field(search);middle.addView(search,new LinearLayout.LayoutParams(-1,skin.dp(46)));',
    "library search field")
activity = replace_once(activity,
    'Spinner cat=spinner(cats,Math.max(0,Arrays.asList(cats).indexOf(category)),p->{category=cats[p];refreshLibrary();});filtersRow.addView(cat,new LinearLayout.LayoutParams(0,skin.dp(40),1));middle.addView(filtersRow);',
    'TextView categoryLabel=skin.text("CATEGORÍA",11,StudioUi.MUTED);skin.space(filtersRow,categoryLabel);Spinner cat=spinner(cats,Math.max(0,Arrays.asList(cats).indexOf(category)),p->{category=cats[p];refreshLibrary();});skin.styleSpinner(cat);filtersRow.addView(cat,new LinearLayout.LayoutParams(0,skin.dp(42),1));middle.addView(filtersRow);',
    "category filter")
activity = replace_once(activity,
    'libraryCount=skin.text("",11,StudioUi.MUTED);middle.addView(libraryCount);',
    'LinearLayout listHeader=skin.row();TextView h1=skin.text("Nº / NOMBRE",11,StudioUi.BLUE);TextView h2=skin.text("CATEGORÍA",11,StudioUi.BLUE);listHeader.addView(h1,new LinearLayout.LayoutParams(0,skin.dp(24),2));listHeader.addView(h2,new LinearLayout.LayoutParams(0,skin.dp(24),1));middle.addView(listHeader);libraryCount=skin.text("",11,StudioUi.MUTED);middle.addView(libraryCount);',
    "library table header")
activity = activity.replace('new LinearLayout.LayoutParams(skin.dp(130),-1)', 'new LinearLayout.LayoutParams(skin.dp(150),-1)', 1)
activity = activity.replace('new LinearLayout.LayoutParams(skin.dp(205),-1)', 'new LinearLayout.LayoutParams(skin.dp(235),-1)', 1)
activity = replace_once(activity,
    'private Spinner spinner(String[] choices,int selected,Selection change){Spinner sp=new Spinner(this);ArrayAdapter<String>a=',
    'private Spinner spinner(String[] choices,int selected,Selection change){Spinner sp=new Spinner(this);skin.styleSpinner(sp);ArrayAdapter<String>a=',
    "spinner styling")
ACTIVITY.write_text(activity,encoding="utf-8")

# Add a very small, allocation-free tap after native render. It never changes the audio data.
audio=AUDIO.read_text(encoding="utf-8")
audio=replace_once(audio,'        stop();\n        if (outputMode != CONSERVATIVE',
                   '        stop();\n        ScopeBus.clear();\n        if (outputMode != CONSERVATIVE',"scope reset")
audio=replace_once(audio,'            s.meter.reset(); s.track.play(); s.playing = true;',
                   '            s.meter.reset(); s.track.play(); s.playing = true; ScopeBus.setActive(true);',"scope active")
audio=replace_once(audio,
    '                if (rendered <= 0 || rendered > s.maxOutputFrames)\n                    throw new IllegalStateException("Invalid converted output block");\n                final int sampleCount = rendered * 2;',
    '                if (rendered <= 0 || rendered > s.maxOutputFrames)\n                    throw new IllegalStateException("Invalid converted output block");\n                ScopeBus.publish(block, rendered);\n                final int sampleCount = rendered * 2;',
    "scope publish")
audio=replace_once(audio,'            s.playing = false;\n            stopMonitor(s);',
                   '            ScopeBus.setActive(false);\n            s.playing = false;\n            stopMonitor(s);',"scope stop")
AUDIO.write_text(audio,encoding="utf-8")
print("RiGear 0.12 UI integrated: live blue LCD, hardware skin, tabbed editor, grouped FX and category library")
