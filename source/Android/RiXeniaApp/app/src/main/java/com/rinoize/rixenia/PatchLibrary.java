package com.rinoize.rixenia;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Last imported dump wins per address. No firmware/factory sounds are fabricated. */
public final class PatchLibrary {
    private final TreeMap<Integer,byte[]> dumps=new TreeMap<>();
    public PatchLibrary() {}
    private PatchLibrary(PatchLibrary original) {
        for(Map.Entry<Integer,byte[]> e:original.dumps.entrySet())dumps.put(e.getKey(),e.getValue().clone());
    }
    public PatchLibrary merged(BankFile bank) {
        PatchLibrary next=new PatchLibrary(this);
        for(byte[] m:bank.messages)next.dumps.put(key(m),m.clone());
        return next;
    }
    private static int key(byte[] m){return ((m[4]&127)<<14)|((m[5]&127)<<7)|(m[6]&127);}
    public String name(int bank,int program) {
        byte[] m=dumps.get((0x10<<14)|(bank<<7)|program);
        if(m==null)return "";
        StringBuilder name=new StringBuilder();
        for(int i=247;i<263;++i){int b=m[i]&127;name.append(b>=32 && b<127 ? (char)b:' ');}
        return name.toString().trim();
    }
    public boolean has(int bank,int program){return dumps.containsKey((0x10<<14)|(bank<<7)|program);}
    public List<Integer> slots() {
        List<Integer> result=new ArrayList<>();
        for(int b=0;b<2;++b)for(int p=0;p<128;++p)if(has(b,p))result.add(b*128+p);
        return result;
    }
    public byte[] bytes() {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        // Dependencies arrive before patches on restore, matching Xenia state ordering.
        for(int command:new int[]{0x12,0x13,0x10})for(byte[] m:dumps.values())
            if((m[4]&127)==command)out.write(m,0,m.length);
        return out.toByteArray();
    }
}
