import com.rinoize.rixenia.XtParameters;

public final class XtParametersTest {
    private static void check(boolean x,String text){if(!x)throw new AssertionError(text);}
    public static void main(String[] args){
        check(XtParameters.accepts(26,63)&&!XtParameters.accepts(26,64),"Wave 1 bounds");
        check(XtParameters.accepts(36,63)&&!XtParameters.accepts(36,127),"Wave 2 bounds");
        check(!XtParameters.accepts(62,1024)&&XtParameters.accepts(62,127),"Cutoff is 7-bit, not mockup 1024");
        check(XtParameters.accepts(64,12)&&!XtParameters.accepts(64,13),"Filter 1 types");
        check(XtParameters.accepts(74,1)&&!XtParameters.accepts(74,2),"Filter 2 types");
        check(XtParameters.get(84).format(64).equals("Centro"),"Pan center");
        check(XtParameters.get(3).format(64).equals("0"),"Detune zero");
        check(XtParameters.get(193).format(0).equals("-64"),"Mod depth -64");
        check(XtParameters.get(193).format(127).equals("+63"),"Mod depth +63");
        check(!XtParameters.accepts(2,51)&&XtParameters.accepts(2,52)&&!XtParameters.accepts(2,77),"Oscillator semitone bounds");
        for(int slot=0;slot<16;slot++){int b=192+slot*3;check(XtParameters.get(b).max==31,"Source range");check(XtParameters.get(b+1).bipolar,"Depth is bipolar");check(XtParameters.get(b+2).max==35,"Destination range");}
        check(XtParameters.WAVES.length==128&&XtParameters.FILTER1.length==13,"Choice lengths");
        check(XtParameters.LFO_SYNC[2].isEmpty(),"Reserved sync value must not appear in choices");
        check(XtParameters.rate(88,3).equals("1/4"),"Clock rate mapping");
        check(XtParameters.rate(88,0).equals("88"),"Free rate mapping");
        check(XtParameters.get(161).format(1).equals("Retrigger"),"LFO delay sentinel");
        check(!XtParameters.accepts(240,65)&&!XtParameters.accepts(-1,0),"Unknown index rejected");
        System.out.println("XtParametersTest: ranges, bipolar raw/display, both filters/LFOs, 16 matrix slots, reserved enums and clock labels passed");
    }
}
