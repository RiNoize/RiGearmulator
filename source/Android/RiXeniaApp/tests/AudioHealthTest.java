import com.rinoize.rixenia.AudioHealth;
import com.rinoize.rixenia.RenderMeter;

public final class AudioHealthTest {
    private static void check(boolean ok,String why) { if(!ok) throw new AssertionError(why); }
    public static void main(String[] args) {
        RenderMeter m=new RenderMeter(); long[] window=new long[4];
        m.record(10_000_000,512,40000); m.record(30_000_000,512,40000);
        m.drainWindow(window);
        check(window[0]==1024 && window[1]==40_000_000 && window[2]==30_000_000 && window[3]==1,"Window values");
        check(m.snapshot()[4]==1,"Sampling reset lifetime OV");
        m.drainWindow(window); check(window[0]==0 && window[2]==0,"Window did not drain");
        m.reset(); check(m.snapshot()[0]==0,"Session reset");
        AudioHealth h=new AudioHealth(512,40000,48000,1024);
        h.start(1_000_000_000L);
        long[] w={1024,40_000_000,30_000_000,1};
        h.observe(1_500_000_000L,6,1024,1280,4104,20,w,148.9);
        String r=h.report();
        check(r.contains("underruns +6 = 6") && r.contains("1024->1280/4104"),"Batch counts or resize lost");
        check(r.contains("t=0.000..0.500 s") && r.contains("pico 30.00 ms"),"Timing range or render peak wrong");
        h.observe(2_000_000_000L,6,1280,1280,4104,100,w,99);
        check(h.report().contains("Grupos conservados 1/1"),"Stable sample logged as a new fault");
        for(int i=1;i<=40;i++) h.observe(2_000_000_000L+i*500_000_000L,6+i,1280,1280,4104,0,w,100);
        check(h.report().contains("Grupos conservados 32/41"),"Event history is not bounded");
        check(!h.report().contains("underruns +6 = 6"),"Oldest event retained after overflow");
        h.observe(23_000_000_000L,0,1280,1280,4104,0,w,3);
        check(h.report().contains("contador reiniciado"),"Counter reset ignored");
        h.start(30_000_000_000L);check(h.report().contains("Grupos conservados 0/0"),"New session retained old events");
        System.out.println("AudioHealthTest: window/lifetime isolation, batch underruns, observation intervals, adaptive sizes, bounded history and reset passed");
    }
}
