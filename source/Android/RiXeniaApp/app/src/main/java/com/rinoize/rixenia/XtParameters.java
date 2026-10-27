package com.rinoize.rixenia;

/** Single-mode indices and labels from Gearmulator parameterDescriptions_xt.json.
 * Raw values are sent unchanged; display formatting must never change MIDI values.
 */
public final class XtParameters {
    private XtParameters() {}
    public static final class Param {
        public final int index, min, max;
        public final String name;
        public final boolean bipolar;
        private Param(int i, int lo, int hi, String n, boolean signed) {
            index=i; min=lo; max=hi; name=n; bipolar=signed;
        }
        public int clamp(int raw) { return Math.max(min,Math.min(max,raw)); }
        public String format(int raw) {
            if(raw<min || raw>max) return "—";
            if(index==84) return raw==64?"Centro":raw<64?"L "+(64-raw):"R "+(raw-64);
            if(index==65 || index==75) return KEY_TRACK[raw];
            if(index==161 || index==168) return LFO_DELAY[raw];
            if(bipolar) { int v=raw-64; return v>0?"+"+v:Integer.toString(v); }
            return Integer.toString(raw);
        }
    }
    private static final Param[] PARAMETERS=new Param[256];
    static {
        PARAMETERS[2]=new Param(2,52,76,"O1Semi",true);
        PARAMETERS[3]=new Param(3,0,127,"O1Detune",true);
        PARAMETERS[7]=new Param(7,0,127,"O1FmAmount",false);
        PARAMETERS[13]=new Param(13,52,76,"O2Semi",true);
        PARAMETERS[14]=new Param(14,0,127,"O2Detune",true);
        PARAMETERS[16]=new Param(16,0,1,"O2Sync",false);
        PARAMETERS[25]=new Param(25,0,127,"Wave",false);
        PARAMETERS[26]=new Param(26,0,63,"W1StartW",false);
        PARAMETERS[36]=new Param(36,0,63,"W2StartW",false);
        PARAMETERS[47]=new Param(47,0,127,"MixW1",false);
        PARAMETERS[48]=new Param(48,0,127,"MixW2",false);
        PARAMETERS[49]=new Param(49,0,127,"MixRingMod",false);
        PARAMETERS[50]=new Param(50,0,127,"MixNoise",false);
        PARAMETERS[62]=new Param(62,0,127,"F1Cutoff",false);
        PARAMETERS[63]=new Param(63,0,127,"F1Resonance",false);
        PARAMETERS[64]=new Param(64,0,12,"F1Type",false);
        PARAMETERS[65]=new Param(65,0,127,"F1KeyTrack",false);
        PARAMETERS[66]=new Param(66,0,127,"F1EnvAmount",true);
        PARAMETERS[73]=new Param(73,0,127,"F2Cutoff",false);
        PARAMETERS[74]=new Param(74,0,1,"F2Type",false);
        PARAMETERS[75]=new Param(75,0,127,"F2KeyTrack",false);
        PARAMETERS[77]=new Param(77,0,127,"AmpVolume",false);
        PARAMETERS[84]=new Param(84,0,127,"Pan",true);
        PARAMETERS[113]=new Param(113,0,127,"F1EnvAttack",false);
        PARAMETERS[114]=new Param(114,0,127,"F1EnvDecay",false);
        PARAMETERS[115]=new Param(115,0,127,"F1EnvSustain",false);
        PARAMETERS[116]=new Param(116,0,127,"F1EnvRelease",false);
        PARAMETERS[119]=new Param(119,0,127,"AmpEnvAttack",false);
        PARAMETERS[120]=new Param(120,0,127,"AmpEnvDecay",false);
        PARAMETERS[121]=new Param(121,0,127,"AmpEnvSustain",false);
        PARAMETERS[122]=new Param(122,0,127,"AmpEnvRelease",false);
        PARAMETERS[159]=new Param(159,0,127,"Lfo1Rate",false);
        PARAMETERS[160]=new Param(160,0,5,"Lfo1Shape",false);
        PARAMETERS[161]=new Param(161,0,127,"Lfo1Delay",false);
        PARAMETERS[162]=new Param(162,0,3,"Lfo1Sync",false);
        PARAMETERS[163]=new Param(163,0,127,"Lfo1Symmetry",true);
        PARAMETERS[164]=new Param(164,0,127,"Lfo1Humanize",false);
        PARAMETERS[166]=new Param(166,0,127,"Lfo2Rate",false);
        PARAMETERS[167]=new Param(167,0,5,"Lfo2Shape",false);
        PARAMETERS[168]=new Param(168,0,127,"Lfo2Delay",false);
        PARAMETERS[169]=new Param(169,0,3,"Lfo2Sync",false);
        PARAMETERS[170]=new Param(170,0,127,"Lfo2Symmetry",true);
        PARAMETERS[171]=new Param(171,0,127,"Lfo2Humanize",false);
        PARAMETERS[192]=new Param(192,0,31,"Slot1Source",false);
        PARAMETERS[193]=new Param(193,0,127,"Slot1Amount",true);
        PARAMETERS[194]=new Param(194,0,35,"Slot1Destination",false);
        PARAMETERS[195]=new Param(195,0,31,"Slot2Source",false);
        PARAMETERS[196]=new Param(196,0,127,"Slot2Amount",true);
        PARAMETERS[197]=new Param(197,0,35,"Slot2Destination",false);
        PARAMETERS[198]=new Param(198,0,31,"Slot3Source",false);
        PARAMETERS[199]=new Param(199,0,127,"Slot3Amount",true);
        PARAMETERS[200]=new Param(200,0,35,"Slot3Destination",false);
        PARAMETERS[201]=new Param(201,0,31,"Slot4Source",false);
        PARAMETERS[202]=new Param(202,0,127,"Slot4Amount",true);
        PARAMETERS[203]=new Param(203,0,35,"Slot4Destination",false);
        PARAMETERS[204]=new Param(204,0,31,"Slot5Source",false);
        PARAMETERS[205]=new Param(205,0,127,"Slot5Amount",true);
        PARAMETERS[206]=new Param(206,0,35,"Slot5Destination",false);
        PARAMETERS[207]=new Param(207,0,31,"Slot6Source",false);
        PARAMETERS[208]=new Param(208,0,127,"Slot6Amount",true);
        PARAMETERS[209]=new Param(209,0,35,"Slot6Destination",false);
        PARAMETERS[210]=new Param(210,0,31,"Slot7Source",false);
        PARAMETERS[211]=new Param(211,0,127,"Slot7Amount",true);
        PARAMETERS[212]=new Param(212,0,35,"Slot7Destination",false);
        PARAMETERS[213]=new Param(213,0,31,"Slot8Source",false);
        PARAMETERS[214]=new Param(214,0,127,"Slot8Amount",true);
        PARAMETERS[215]=new Param(215,0,35,"Slot8Destination",false);
        PARAMETERS[216]=new Param(216,0,31,"Slot9Source",false);
        PARAMETERS[217]=new Param(217,0,127,"Slot9Amount",true);
        PARAMETERS[218]=new Param(218,0,35,"Slot9Destination",false);
        PARAMETERS[219]=new Param(219,0,31,"Slot10Source",false);
        PARAMETERS[220]=new Param(220,0,127,"Slot10Amount",true);
        PARAMETERS[221]=new Param(221,0,35,"Slot10Destination",false);
        PARAMETERS[222]=new Param(222,0,31,"Slot11Source",false);
        PARAMETERS[223]=new Param(223,0,127,"Slot11Amount",true);
        PARAMETERS[224]=new Param(224,0,35,"Slot11Destination",false);
        PARAMETERS[225]=new Param(225,0,31,"Slot12Source",false);
        PARAMETERS[226]=new Param(226,0,127,"Slot12Amount",true);
        PARAMETERS[227]=new Param(227,0,35,"Slot12Destination",false);
        PARAMETERS[228]=new Param(228,0,31,"Slot13Source",false);
        PARAMETERS[229]=new Param(229,0,127,"Slot13Amount",true);
        PARAMETERS[230]=new Param(230,0,35,"Slot13Destination",false);
        PARAMETERS[231]=new Param(231,0,31,"Slot14Source",false);
        PARAMETERS[232]=new Param(232,0,127,"Slot14Amount",true);
        PARAMETERS[233]=new Param(233,0,35,"Slot14Destination",false);
        PARAMETERS[234]=new Param(234,0,31,"Slot15Source",false);
        PARAMETERS[235]=new Param(235,0,127,"Slot15Amount",true);
        PARAMETERS[236]=new Param(236,0,35,"Slot15Destination",false);
        PARAMETERS[237]=new Param(237,0,31,"Slot16Source",false);
        PARAMETERS[238]=new Param(238,0,127,"Slot16Amount",true);
        PARAMETERS[239]=new Param(239,0,35,"Slot16Destination",false);
    }
    public static Param get(int index) {
        if(index<0 || index>=256 || PARAMETERS[index]==null) throw new IllegalArgumentException("Unknown XT editor parameter: "+index);
        return PARAMETERS[index];
    }
    public static boolean accepts(int index,int value) {
        if(index<0 || index>=256 || PARAMETERS[index]==null) return false;
        Param p=PARAMETERS[index]; return value>=p.min && value<=p.max;
    }
    public static final String[] WAVES={
        "Resonant", "Resonant 2", "Mallet Syn", "Square-Sweep", "Bellish", "Pulse-Sweep", "Saw-Sweep", "Mellow Saw",
        "Feedback", "Add Harm", "Reso 3 HP", "Wind Syn", "High Harm", "Clipper", "Organ Syn", "Square Saw",
        "Formant 1", "Polated", "Transient", "E Piano", "Robotic", "Strong Harm", "Perc Organ", "Clip Sweep",
        "Reso Harms", "2 Echoes", "Formant 2", "Formant Vocal", "Micro Sync", "Micro PWM", "Glassy", "Square HP",
        "Saw Sync 1", "Saw Sync 2", "Saw Sync 3", "Pul Sync 1", "Pul Sync 2", "Pul Sync 3", "Sin Sync 1", "Sin Sync 2",
        "Sin Sync 3", "PWM Pulse", "PWM Saw", "Fuzz Wave", "Distorted", "Heavy Fuzz", "Fuzz Sync", "K+Strong 1",
        "K+Strong 2", "K+Strong 3", "1-2-3-4-5", "19/twenty", "Wavetrip 1", "Wavetrip 2", "Wavetrip 3", "Wavetrip 4",
        "Male Voice", "Low Piano", "Reso Sweep", "Xmas Bell", "FM Piano", "Fat Organ", "Vibes", "Chorus 2",
        "True PWM", "", "", "", "", "", "", "",
        "", "", "", "", "", "", "", "",
        "", "", "", "", "", "", "", "",
        "", "", "", "", "", "", "", "",
        "User 1", "User 2", "User 3", "User 4", "User 5", "User 6", "User 7", "User 8",
        "User 9", "User 10", "User 11", "User 12", "User 13", "User 14", "User 15", "User 16",
        "User 17", "User 18", "User 19", "User 20", "User 21", "User 22", "User 23", "User 24",
        "User 25", "User 26", "User 27", "User 28", "User 29", "User 30", "User 31", "User 32"
    };
    public static final String[] FILTER1={
        "24 dB LP", "12 dB LP", "24 dB BP", "12 dB BP", "12 dB HP", "Sin(x) > 12 dB LP", "12 dB LP > Shaper", "Dual 12 dB LP/BP",
        "12 dB LP FM", "12 dB LP S&H", "24 dB Notch", "12 dB Notch", "12 dB Band Stop"
    };
    public static final String[] FILTER2={
        "6 dB LP", "6 dB HP"
    };
    public static final String[] LFO_SHAPES={
        "Sine", "Triangle", "Square", "Saw", "Random", "S&H"
    };
    public static final String[] LFO_SYNC={
        "Off", "On", "", "Clock"
    };
    public static final String[] SOURCES={
        "Off", "LFO 1", "LFO 1 * MW", "LFO 1 * AT", "LFO 2", "Filter Env", "Amp Env", "Wave Env",
        "Free Env", "Key Follow", "Keytrack", "Velocity", "Release Vel", "Aftertouch", "Poly Pressure", "Pitch Bend",
        "Modwheel", "Sustain Pedal", "Foot Control", "Breath Control", "Control W", "Control X", "Control Y", "Control Z",
        "Control Delay", "Modifier #1", "Modifier #2", "Modifier #3", "Modifier #4", "MIDI Clock", "Min", "Max"
    };
    public static final String[] DESTINATIONS={
        "Pitch", "Osc 1 Pitch", "Osc 2 Pitch", "Wave 1 Startwave", "Wave 2 Startwave", "Mix Wave 1", "Mix Wave 2", "Mix Ring Mod",
        "Mix Noise", "Filter 1 Cutoff", "Filter 1 Resonance", "Filter 2 Cutoff", "Volume", "Pan", "Filter Env Attack", "Filter Env Decay",
        "Filter Env Sustain", "Filter Env Release", "Amp Env Attack", "Amp Env Decay", "Amp Env Sustain", "Amp Env Release", "Wave Env Times", "Wave Env Levels",
        "Free Env Times", "Free Env Levels", "LFO 1 Rate", "LFO 1 Level", "LFO 2 Rate", "LFO 2 Level", "Mod #1 Amount", "Mod #2 Amount",
        "Mod #3 Amount", "Mod #4 Amount", "FM Amount", "Filter 1 Extra"
    };
    public static final String[] KEY_TRACK={
        "-200%", "-197%", "-194%", "-191%", "-188%", "-185%", "-182%", "-179%",
        "-175%", "-172%", "-169%", "-166%", "-163%", "-160%", "-157%", "-154%",
        "-150%", "-147%", "-144%", "-141%", "-138%", "-135%", "-132%", "-129%",
        "-125%", "-122%", "-119%", "-116%", "-113%", "-110%", "-107%", "-104%",
        "-100%", "-97%", "-94%", "-91%", "-88%", "-85%", "-82%", "-79%",
        "-75%", "-72%", "-69%", "-66%", "-63%", "-60%", "-57%", "-54%",
        "-50%", "-47%", "-44%", "-41%", "-38%", "-35%", "-32%", "-29%",
        "-25%", "-22%", "-19%", "-16%", "-13%", "-10%", "-7%", "-4%",
        "0%", "+3%", "+6%", "+9%", "+12%", "+15%", "+18%", "+21%",
        "+25%", "+28%", "+31%", "+34%", "+37%", "+40%", "+43%", "+46%",
        "+50%", "+53%", "+56%", "+59%", "+62%", "+65%", "+68%", "+71%",
        "+75%", "+78%", "+81%", "+84%", "+87%", "+90%", "+93%", "+96%",
        "+100%", "+103%", "+106%", "+109%", "+112%", "+115%", "+118%", "+121%",
        "+125%", "+128%", "+131%", "+134%", "+137%", "+140%", "+143%", "+146%",
        "+150%", "+153%", "+156%", "+159%", "+162%", "+165%", "+168%", "+171%",
        "+175%", "+178%", "+181%", "+184%", "+187%", "+190%", "+193%", "+197%"
    };
    public static final String[] LFO_DELAY={
        "Off", "Retrigger", "1", "2", "3", "4", "5", "6",
        "7", "8", "9", "10", "11", "12", "13", "14",
        "15", "16", "17", "18", "19", "20", "21", "22",
        "23", "24", "25", "26", "27", "28", "29", "30",
        "31", "32", "33", "34", "35", "36", "37", "38",
        "39", "40", "41", "42", "43", "44", "45", "46",
        "47", "48", "49", "50", "51", "52", "53", "54",
        "55", "56", "57", "58", "59", "60", "61", "62",
        "63", "64", "65", "66", "67", "68", "69", "70",
        "71", "72", "73", "74", "75", "76", "77", "78",
        "79", "80", "81", "82", "83", "84", "85", "86",
        "87", "88", "89", "90", "91", "92", "93", "94",
        "95", "96", "97", "98", "99", "100", "101", "102",
        "103", "104", "105", "106", "107", "108", "109", "110",
        "111", "112", "113", "114", "115", "116", "117", "118",
        "119", "120", "121", "122", "123", "124", "125", "126"
    };
    public static final String[] LFO_CLOCK={
        "128 Bars", "", "", "", "96 Bars", "", "", "",
        "64 Bars", "", "", "", "48 Bars", "", "", "",
        "36 Bars", "", "", "", "32 Bars", "", "", "",
        "24 Bars", "", "", "", "18 Bars", "", "", "",
        "16 Bars", "", "", "", "12 Bars", "", "", "",
        "9 Bars", "", "", "", "8 Bars", "", "", "",
        "6 Bars", "", "", "", "4 Bars", "", "", "",
        "3 Bars", "", "", "", "2 Bars", "", "", "",
        "1/1", "", "", "", "1/2 D", "", "", "",
        "1/2 T", "", "", "", "1/2", "", "", "",
        "1/4 D", "", "", "", "1/4 T", "", "", "",
        "1/4", "", "", "", "1/8 D", "", "", "",
        "1/8 T", "", "", "", "1/8", "", "", "",
        "1/16 D", "", "", "", "1/16 T", "", "", "",
        "1/16", "", "", "", "1/32 D", "", "", "",
        "1/32", "", "", "", "1/64", "", "", "1/64"
    };
    public static String choice(String[] labels,int raw) {
        return raw>=0 && raw<labels.length && !labels[raw].isEmpty()?labels[raw]:"#"+raw;
    }
    public static String rate(int raw, int sync) {
        if(raw<0 || raw>127) return "—";
        return sync==3?choice(LFO_CLOCK,(raw/4)*4):Integer.toString(raw);
    }
}
