package com.rinoize.rixenia;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Read-only, bounded ZIP import. Archive paths never become filesystem paths. */
public final class RomFile {
    public static final int MAX_ARCHIVE_BYTES=8*1024*1024;
    public static final int ROM_BYTES=262144,HALF_BYTES=131072;
    public final byte[] bytes;
    public final String name,notice;
    private RomFile(byte[] bytes,String name,String notice){this.bytes=bytes;this.name=name;this.notice=notice;}
    public static void validate(byte[] rom)throws IOException {
        if(rom==null||rom.length!=ROM_BYTES||(rom[0]&255)!=0xc0||(rom[1]&255)!=0xde)
            throw new IOException("Se necesita la ROM completa .bin de 256 KiB, o un ZIP con las dos mitades de 128 KiB. Un update MIDI por sí solo no incluye las wavetables.");
        int a=rom[0x2f]&255,b=rom[0x31]&255,c=rom[0x32]&255;
        if(rom[0x30]!='.'||a<'0'||a>'9'||b<'0'||b>'9'||c<'0'||c>'9'||(a-'0')*100+(b-'0')*10+c-'0'<=200)
            throw new IOException("Cabecera o versión de ROM Microwave II/XT inválida");
    }
    public static String version(byte[] rom){return ""+(char)rom[0x2f]+'.'+(char)rom[0x31]+(char)rom[0x32];}
    public static RomFile parse(byte[] selected,String selectedName)throws IOException {
        if(selected==null||selected.length==0||selected.length>MAX_ARCHIVE_BYTES)throw new IOException("ROM/ZIP vacío o mayor de 8 MiB");
        String name=selectedName==null?"Microwave-II-XT.bin":selectedName;
        boolean zip=name.toLowerCase(Locale.ROOT).endsWith(".zip")||
            (selected.length>=4&&selected[0]==0x50&&selected[1]==0x4b&&selected[2]==3&&selected[3]==4);
        if(!zip){validate(selected);return new RomFile(selected.clone(),name,"ROM completa OS "+version(selected));}
        List<byte[]> full=new ArrayList<>(),even=new ArrayList<>(),odd=new ArrayList<>();
        boolean midiUpdate=false;int entries=0,expanded=0;
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(selected))){
            ZipEntry entry;byte[] scratch=new byte[8192];
            while((entry=in.getNextEntry())!=null){
                if(++entries>128)throw new IOException("ZIP con demasiadas entradas (máximo 128)");
                String path=entry.getName().toLowerCase(Locale.ROOT);
                if(path.endsWith(".mid")||path.endsWith(".midi"))midiUpdate=true;
                boolean bin=!entry.isDirectory()&&path.endsWith(".bin");
                ByteArrayOutputStream out=bin?new ByteArrayOutputStream():null;
                int count,size=0;
                while((count=in.read(scratch))!=-1){
                    if(count==0)continue;expanded+=count;size+=count;
                    if(expanded>MAX_ARCHIVE_BYTES)throw new IOException("ZIP supera 8 MiB al descomprimir");
                    if(bin&&size<=ROM_BYTES)out.write(scratch,0,count);
                }
                if(bin&&size<=ROM_BYTES){
                    byte[] data=out.toByteArray();
                    if(data.length==ROM_BYTES){
                        try{validate(data);unique(full,data);}catch(IOException ignored){}
                    }else if(data.length==HALF_BYTES&&data[1]==0){
                        if((data[0]&255)==0xc0)unique(even,data);
                        else if((data[0]&255)==0xde)unique(odd,data);
                    }
                }
                in.closeEntry();
            }
        }
        byte[] rom;String source;
        if(full.size()==1){rom=full.get(0);source="ROM completa dentro del ZIP";}
        else if(full.size()>1)throw new IOException("El ZIP contiene varias ROM completas distintas; importá solo la que querés usar");
        else{
            if(even.size()!=1||odd.size()!=1)throw new IOException("El ZIP debe contener una ROM completa o exactamente una mitad C0 y una mitad DE de 128 KiB cada una");
            rom=new byte[ROM_BYTES];byte[] a=even.get(0),b=odd.get(0);
            for(int i=0;i<HALF_BYTES;++i){rom[2*i]=a[i];rom[2*i+1]=b[i];}
            validate(rom);source="Dos mitades de ROM combinadas";
        }
        String notice=source+" · OS "+version(rom);
        if(midiUpdate)notice+=". El update MIDI incluido no se aplica; se usa el sistema de la ROM BIN.";
        return new RomFile(rom,name,notice);
    }
    private static void unique(List<byte[]> list,byte[] data){for(byte[] existing:list)if(Arrays.equals(existing,data))return;list.add(data);}
}
