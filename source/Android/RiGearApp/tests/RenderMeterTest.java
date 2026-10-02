import com.rinoize.rigear.RenderMeter;

/** Host JVM test: no device, ROM or Android SDK required. */
public final class RenderMeterTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        for (int frames : new int[]{256, 512, 1024}) {
            RenderMeter meter = new RenderMeter();
            long deadline = frames * 1_000_000_000L / 46875;
            meter.record(deadline - 1, frames, 46875);
            meter.record(deadline, frames, 46875);
            meter.record(deadline + 1, frames, 46875);
            long[] s = meter.snapshot();
            check(s[0] == 3, "blocks");
            check(s[1] == frames * 3L, "frames");
            check(s[2] == deadline * 3, "sum time");
            check(s[3] == deadline + 1, "peak");
            check(s[4] == 1, "OV threshold");
            meter.reset();
            for (long value : meter.snapshot()) check(value == 0, "reset");
            boolean rejected = false;
            try { meter.record(-1, frames, 46875); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "negative duration rejected");
        }
        System.out.println("RenderMeter: PASS (256/512/1024, deadlines, reset, invalid input)");
    }
}
