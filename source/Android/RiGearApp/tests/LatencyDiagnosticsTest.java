import com.rinoize.rigear.PlaybackQueueMeter;
import com.rinoize.rigear.MeterWindow;
import com.rinoize.rigear.MidiTelemetry;

/** No Android/ROM dependency. Exercises queue accounting, wrap/reset and MIDI sustain semantics. */
public final class LatencyDiagnosticsTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void near(double a, double b, String name) {
        check(Math.abs(a - b) < 0.0001, name + ": " + a + " != " + b);
    }
    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < result.length; ++i) result[i] = (byte)values[i];
        return result;
    }
    private static void send(MidiTelemetry t, int... data) {
        byte[] b = bytes(data); t.accept(b, 0, b.length);
    }
    public static void main(String[] args) {
        PlaybackQueueMeter q = new PlaybackQueueMeter();
        q.accepted(1024 * 2); // Startup silence is included, not forgotten.
        check(q.pending(0) == 1024, "startup queue");
        q.accepted(512 * 2);
        check(q.pending(256) == 1280, "accepted minus playback");
        check(q.pending(1536) == 0, "empty queue");
        check(q.pending(1500) == -1, "backward/reset invalidates estimate");
        check(q.pending(1536) == -1, "do not silently recover a bad timeline");
        PlaybackQueueMeter partial = new PlaybackQueueMeter();
        partial.accepted(1); check(partial.pending(0) == 0, "partial stereo frame");
        partial.accepted(1); check(partial.pending(0) == 1, "partial writes accumulate");
        check(partial.pending(2) == -1, "playback ahead of accepted data is invalid");
        PlaybackQueueMeter wrap = new PlaybackQueueMeter();
        for (int i = 0; i < 5; ++i) wrap.accepted(Integer.MAX_VALUE);
        long written = (5L * Integer.MAX_VALUE) / 2;
        check(wrap.pending(0x7ffffff0) == written - 0x7ffffff0L, "positive boundary");
        check(wrap.pending(0x80000010) == written - 0x80000010L, "unsigned head");
        check(wrap.pending(0xfffffff0) == written - 0xfffffff0L, "pre-wrap");
        check(wrap.pending(0x00000010) == written - 0x100000010L, "32 bit wrap");

        MeterWindow window = new MeterWindow();
        window.sample(1_000_000_000L, new long[]{0,0,0,0,0}, 46875);
        double[] rates = window.sample(1_500_000_000L, new long[]{10,5120,60_000_000,12_000_000,3}, 46875);
        near(rates[0], 100.0 * 60_000_000 * 46875 / (5120 * 1e9), "weighted render");
        near(rates[1], 6.0, "OV per second");
        rates = window.sample(2_000_000_000L, new long[]{0,0,0,0,0}, 46875);
        near(rates[0], 0, "meter reset render"); near(rates[1], 0, "meter reset OV rate");
        window.reset();
        near(window.sample(3_000_000_000L, new long[]{1,256,1_000_000,1_000_000,1}, 46875)[1], 0, "new session baseline");

        MidiTelemetry t = new MidiTelemetry();
        send(t, 0xb0,64,127); // Sustain on channel 1.
        send(t, 0x90,60,100,64,100,67,100,71,100,74,100); // Running status: 5 keys.
        check(t.snapshot()[3] == 5, "five physical USB keys");
        send(t, 0x80,60,0,64,0,67,0,71,0,74,0);
        long[] snapshot = t.snapshot();
        check(snapshot[1] == 5 && snapshot[2] == 5 && snapshot[3] == 0, "five Note Offs");
        check(snapshot[4] == 1, "sustain remains ON with zero keys");
        send(t, 0xb0,64,0); check(t.snapshot()[4] == 0, "pedal release");
        t.reset(true);
        send(t, 0x90,60); send(t, 0xf8); send(t, 100); // Fragmented message + realtime.
        check(t.snapshot()[3] == 1, "split Note On");
        send(t, 60,0); check(t.snapshot()[3] == 0 && t.snapshot()[2] == 1, "velocity-zero Note Off");
        send(t, 0xb1,64,64); check(t.snapshot()[4] == 2, "independent sustain channels");
        send(t, 0xb1,121,0); check(t.snapshot()[4] == 0, "reset controllers releases sustain");
        send(t, 0xc0,7,8); // One-byte program running status.
        send(t, 0xf0,1,2,3,0xf8,4,0xf7,60,100);
        check(t.snapshot()[3] == 0, "SysEx cancels running status");
        send(t, 0x90,61,100,61,100); check(t.snapshot()[3] == 1, "unique key states, not voice count");
        send(t, 0xb0,123,0); check(t.snapshot()[3] == 0, "all notes off");
        send(t, 0xb0,64,127); t.reset(false);
        check(t.snapshot()[3] == 0 && t.snapshot()[4] == 0 && t.snapshot()[1] > 0, "panic resets state, not totals");
        boolean rejected = false;
        try { t.accept(new byte[2], 1, 2); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "invalid MIDI slice rejected");
        System.out.println("LatencyDiagnostics: PASS (queue, partial writes, wrap, resets, OV/s, five notes + sustain, MIDI parser)");
    }
}
