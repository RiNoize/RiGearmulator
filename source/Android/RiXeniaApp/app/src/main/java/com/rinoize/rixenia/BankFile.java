package com.rinoize.rixenia;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Strict, bounded reader for Microwave II/XT single patches and their waves/tables.
 * SMF is a container here, not a sequencer; non-SysEx events are never played.
 */
public final class BankFile {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_MESSAGES = 4096, MAX_EVENTS = 200000;
    public final List<byte[]> messages;
    public final int skipped;
    private BankFile(List<byte[]> messages, int skipped) { this.messages = messages; this.skipped = skipped; }

    public static BankFile parse(byte[] source, int targetBank, int targetProgram) throws IOException {
        if (source == null || source.length == 0 || source.length > MAX_BYTES)
            throw new IOException("Banco vacío o mayor de 4 MB");
        if (targetBank < 0 || targetBank > 1 || targetProgram < 0 || targetProgram > 127)
            throw new IOException("Destino de banco inválido");
        byte[] raw = starts(source, 0, "MThd") ? fromMidi(source) : source;
        List<byte[]> result = new ArrayList<>();
        ByteArrayOutputStream active = null;
        int skipped = 0, count = 0;
        for (byte value : raw) {
            int b = value & 255;
            if (b >= 0xf8) continue; // MIDI realtime may interrupt SysEx.
            if (b == 0xf0) {
                if (active != null) throw new IOException("SysEx incompleto antes del siguiente F0");
                active = new ByteArrayOutputStream(265); active.write(b);
            } else if (b == 0xf7) {
                if (active == null) throw new IOException("Fin SysEx sin comienzo");
                active.write(b);
                byte[] message = active.toByteArray(); active = null;
                if (++count > MAX_MESSAGES) throw new IOException("Demasiados mensajes SysEx");
                if (validate(message)) {
                    // A single edit-buffer file becomes a browsable slot and restores to the same slot.
                    if ((message[4] & 255) == 0x10 && (message[5] & 255) == 0x20) {
                        message[5] = (byte)targetBank; message[6] = (byte)targetProgram;
                    }
                    result.add(message);
                } else ++skipped;
            } else {
                if (active == null || b >= 0x80) throw new IOException("Datos fuera de un mensaje SysEx");
                if (active.size() >= MAX_BYTES) throw new IOException("SysEx demasiado grande");
                active.write(b);
            }
        }
        if (active != null) throw new IOException("El archivo termina en un SysEx incompleto");
        if (result.isEmpty()) throw new IOException("No hay sonidos, waves o wavetables de Microwave II/XT. Los dumps de Microwave I y Multi no se importan en esta versión.");
        return new BankFile(result, skipped);
    }

    private static boolean validate(byte[] m) throws IOException {
        if (m.length < 6 || (m[1] & 255) != 0x3e || (m[2] & 255) != 0x0e) return false;
        int command = m[4] & 255;
        if (command != 0x10 && command != 0x12 && command != 0x13) return false;
        int expected = command == 0x12 ? 137 : 265;
        if (m.length != expected) throw new IOException("Longitud incorrecta en dump Microwave II/XT");
        int checksum = 0;
        for (int i = 7; i < m.length - 2; ++i) checksum += m[i] & 127;
        if ((checksum & 127) != (m[m.length - 2] & 127))
            throw new IOException("Checksum incorrecto: el banco está incompleto o dañado");
        int high = m[5] & 127, low = m[6] & 127;
        if (command == 0x10) {
            if (high != 0 && high != 1 && high != 0x20) return false;
            if (high == 0x20 && low != 0) return false;
        } else {
            int index = (high << 7) | low;
            if (command == 0x13 && index >= 128) throw new IOException("Índice de wavetable inválido");
            if (command == 0x12 && !(index < 506 || (index >= 1000 && index < 1250)))
                throw new IOException("Índice de wave inválido");
            for (int i = 7; i < m.length - 2; ++i)
                if ((m[i] & 255) >= 16) throw new IOException("Datos de wave/wavetable inválidos: se esperaban nibbles de 4 bits");
        }
        // Broadcast avoids banks captured from another hardware Device ID being ignored.
        m[3] = 0x7f;
        return true;
    }

    public byte[] bytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] m : messages) out.write(m, 0, m.length);
        return out.toByteArray();
    }

    private static boolean starts(byte[] bytes, int offset, String text) {
        if (offset < 0 || offset > bytes.length - text.length()) return false;
        for (int i = 0; i < text.length(); ++i) if ((bytes[offset + i] & 255) != text.charAt(i)) return false;
        return true;
    }
    private static final class Cursor {
        final byte[] data; int pos, end;
        Cursor(byte[] bytes, int pos, int end) { data=bytes; this.pos=pos; this.end=end; }
        int u8() throws IOException { if (pos >= end) throw new IOException("MIDI truncado"); return data[pos++] & 255; }
        int u16() throws IOException { return (u8()<<8)|u8(); }
        int u32() throws IOException {
            long n=((long)u16()<<16)|u16();
            if(n>MAX_BYTES)throw new IOException("Longitud MIDI fuera de rango");return (int)n;
        }
        int vlq() throws IOException {
            int value=0;
            for(int i=0;i<4;++i){int b=u8();value=(value<<7)|(b&127);if((b&128)==0)return value;}
            throw new IOException("VLQ MIDI inválido");
        }
        byte[] take(int count) throws IOException {
            if(count<0 || count>end-pos)throw new IOException("Evento MIDI truncado");
            byte[] r=Arrays.copyOfRange(data,pos,pos+count);pos+=count;return r;
        }
        void skip(int count) throws IOException {
            if(count<0 || count>end-pos)throw new IOException("Evento MIDI truncado");pos+=count;
        }
    }
    private static byte[] fromMidi(byte[] source) throws IOException {
        Cursor c=new Cursor(source,4,source.length);
        int header=c.u32(); if(header<6 || header>c.end-c.pos)throw new IOException("Cabecera MIDI inválida");
        int format=c.u16(),tracks=c.u16();c.u16();c.skip(header-6);
        if(format>1 || tracks<1 || tracks>128 || (format==0 && tracks!=1))
            throw new IOException("Formato MIDI no admitido");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        int events=0;
        for(int track=0;track<tracks;++track){
            if(!starts(source,c.pos,"MTrk"))throw new IOException("Falta bloque MTrk");c.skip(4);
            int length=c.u32();if(length>c.end-c.pos)throw new IOException("Pista MIDI truncada");
            Cursor t=new Cursor(source,c.pos,c.pos+length);c.skip(length);
            int running=0; boolean sysexOpen=false;
            while(t.pos<t.end){
                if(++events>MAX_EVENTS)throw new IOException("Demasiados eventos MIDI");
                t.vlq();int b=t.u8(),status=b;
                if(b<128){if(running==0)throw new IOException("Running status MIDI inválido");status=running;--t.pos;}
                if(status>=0x80 && status<=0xef){
                    running=status;int count=(status&0xf0)==0xc0 || (status&0xf0)==0xd0 ? 1:2;
                    for(int j=0;j<count;++j)if(t.u8()>=128)throw new IOException("Datos MIDI inválidos");
                }else if(status==0xff){
                    t.u8();t.skip(t.vlq());
                }else if(status==0xf0 || status==0xf7){
                    running=0;byte[] payload=t.take(t.vlq());
                    if(status==0xf0){if(sysexOpen)throw new IOException("SysEx MIDI sin terminar");out.write(0xf0);sysexOpen=true;}
                    else if(!sysexOpen && (payload.length==0 || (payload[0]&255)!=0xf0))
                        throw new IOException("Escape MIDI sin SysEx completo");
                    out.write(payload,0,payload.length);
                    if(payload.length>0)sysexOpen=(payload[payload.length-1]&255)!=0xf7;
                    if(out.size()>MAX_BYTES)throw new IOException("SysEx MIDI demasiado grande");
                }else throw new IOException("Evento de sistema MIDI no admitido");
            }
            if(sysexOpen)throw new IOException("SysEx dividido sin continuación MIDI");
        }
        if(c.pos!=c.end)throw new IOException("Datos adicionales al final del archivo MIDI");
        return out.toByteArray();
    }
}
