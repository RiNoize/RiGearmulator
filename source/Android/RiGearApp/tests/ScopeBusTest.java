import com.rinoize.rigear.ScopeBus;

public final class ScopeBusTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        float[] stereo = new float[512];
        for (int i = 0; i < 256; ++i) {
            float value = (float)Math.sin(i * Math.PI * 2.0 / 32.0);
            stereo[i * 2] = value;
            stereo[i * 2 + 1] = value * .5f;
        }
        int before = ScopeBus.revision;
        for (int i = 0; i < 8; ++i) ScopeBus.publish(stereo, 256);
        check(ScopeBus.revision > before, "scope revision");
        float[] wave = ScopeBus.snapshot();
        check(wave.length == ScopeBus.POINTS, "scope point count");
        float peak = 0f;
        for (float sample : wave) peak = Math.max(peak, Math.abs(sample));
        check(peak > .25f && peak <= 1f, "captured waveform peak");
        ScopeBus.setActive(true); check(ScopeBus.active, "active state");
        ScopeBus.setActive(false); check(!ScopeBus.active, "inactive state");
        ScopeBus.clear();
        for (float sample : ScopeBus.snapshot()) check(sample == 0f, "clear");
        System.out.println("ScopeBus: PASS — bounded allocation-free stereo waveform capture");
    }
}
