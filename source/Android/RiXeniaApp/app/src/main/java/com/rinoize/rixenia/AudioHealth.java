package com.rinoize.rixenia;

import java.util.Locale;

/** Monitor/UI only; no formatting, allocations or event logging on the render thread.
 * Underruns are observations of AudioTrack's cumulative counter, not sample-exact timestamps.
 * The ring retains the most recent 32 event groups, never grows with session duration.
 */
public final class AudioHealth {
    private static final int LIMIT = 32;
    private final int block, engineRate, outputRate, requested;
    private final String[] events = new String[LIMIT];
    private int eventCount, next, totalEvents, underruns, effective, capacity;
    private long started, previous, lastChange, lastNow;
    private double peakMs, renderPercent, cpuPercent;
    private long queued;
    private boolean running;

    public AudioHealth(int frames, int engineHz, int outputHz, int requestedFrames) {
        if(frames<=0 || engineHz<=0 || outputHz<=0 || requestedFrames<0)
            throw new IllegalArgumentException("Invalid audio diagnostic configuration");
        block=frames; engineRate=engineHz; outputRate=outputHz; requested=requestedFrames;
    }
    public synchronized void start(long now) {
        started=previous=lastChange=lastNow=now; running=true;
        eventCount=next=totalEvents=underruns=effective=capacity=0;
        queued=0; peakMs=renderPercent=cpuPercent=0;
        for(int i=0;i<LIMIT;i++) events[i]=null;
    }
    public synchronized void observe(long now, int count, int before, int after, int cap,
                                     long pending, long[] window, double cpu) {
        if(!running || now<previous || count<0 || window==null || window.length<4) return;
        peakMs=window[2]/1e6;
        renderPercent=window[0]>0 ? 100.0*window[1]*engineRate/(window[0]*1e9) : 0;
        cpuPercent=cpu; effective=after; capacity=cap; queued=pending;
        int delta=Math.max(0,count-underruns);
        if(delta>0 || before!=after || count<underruns) {
            String event=String.format(Locale.ROOT,
                "t=%.3f..%.3f s | underruns +%d = %d | buffer %d->%d/%d | cola aprox %d fr | render pico %.2f ms, media %.1f%%, OV %d | CPU app %.1f%%%s",
                (previous-started)/1e9,(now-started)/1e9,delta,count,before,after,cap,pending,
                peakMs,renderPercent,window[3],cpu,count<underruns?" | contador reiniciado":"");
            events[next]=event; next=(next+1)%LIMIT;
            eventCount=Math.min(LIMIT,eventCount+1); totalEvents++;
        }
        if(delta>0 || count<underruns) lastChange=now;
        underruns=count; previous=lastNow=now;
    }
    public synchronized String summary() {
        if(!running) return "Diagnóstico pendiente de iniciar audio";
        return String.format(Locale.ROOT,
            "Pico render (última ventana): %.2f ms / plazo %.2f ms\nSin nuevos underruns observados: %.0f s · registro %d grupos",
            peakMs,1000.0*block/engineRate,(lastNow-lastChange)/1e9,totalEvents);
    }
    public synchronized String report() {
        StringBuilder out=new StringBuilder();
        out.append("Diagnóstico de sesión (muestreo nominal 500 ms; intervalos reales en cada línea).\n");
        out.append("No mide latencia tecla-audio ni identifica por sí solo la causa de un underrun.\n");
        out.append(String.format(Locale.ROOT,
            "Motor %d fr @ %d Hz; salida %d Hz; solicitado %d fr (0=Conservador); efectivo %d/%d fr; cola aprox %d fr\n",
            block,engineRate,outputRate,requested,effective,capacity,queued));
        out.append(String.format(Locale.ROOT,
            "Sesión %.1f s; underruns %d; render reciente %.1f%%; CPU app reciente %.1f%%\n",
            running?(lastNow-started)/1e9:0,underruns,renderPercent,cpuPercent));
        out.append(summary()).append('\n');
        out.append("Grupos conservados ").append(eventCount).append('/').append(totalEvents).append('\n');
        for(int i=0;i<eventCount;i++) out.append(events[(next-eventCount+i+LIMIT)%LIMIT]).append('\n');
        out.append("Los picos de render corresponden a bloques terminados en esa ventana.\n");
        return out.toString();
    }
}
