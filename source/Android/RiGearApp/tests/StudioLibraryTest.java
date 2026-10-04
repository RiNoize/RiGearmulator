import com.rinoize.rigear.SoundCodec;
import com.rinoize.rigear.SoundLibrary;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class StudioLibraryTest {
    private static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    private static byte[] single(String name,int bank,int program){
        byte[] p=new byte[267];p[0]=(byte)0xf0;p[2]=0x20;p[3]=0x33;p[4]=1;p[5]=0x10;p[6]=0x10;p[7]=(byte)bank;p[8]=(byte)program;p[9]=6;p[266]=(byte)0xf7;
        for(int i=0;i<10;++i)p[9+240+i]=(byte)' ';for(int i=0;i<name.length();++i)p[9+240+i]=(byte)name.charAt(i);SoundCodec.repair(p);return p;
    }
    private interface Bad {void run() throws Exception;}
    private static void rejects(Bad b)throws Exception{boolean failed=false;try{b.run();}catch(IllegalArgumentException|IOException e){failed=true;}check(failed,"invalid input was accepted");}
    public static void main(String[]args)throws Exception{
        byte[] original=single("Avenues JS",1,1);check(SoundCodec.name(original).equals("Avenues JS"),"name");
        byte[] renamed=SoundCodec.rename(original,"Mi Pad");check(SoundCodec.name(renamed).equals("Mi Pad"),"rename");check(SoundCodec.name(original).equals("Avenues JS"),"rename must not mutate original");
        byte[] broken=original.clone();broken[20]^=1;rejects(()->SoundCodec.validate(broken));
        rejects(()->SoundCodec.split(Arrays.copyOf(original,original.length-1)));
        rejects(()->SoundCodec.rename(original,"Nombre demasiado largo"));
        byte[] firmware=original.clone();firmware[6]=0x50;SoundCodec.repair(firmware);rejects(()->SoundCodec.validate(firmware));
        for(int a=0;a<128;++a)for(int b=0;b<128;++b)for(int value:new int[]{0,64,127}){
            int[] pair=SoundCodec.linked(a,b,value);check(pair[0]>=0&&pair[0]<=127&&pair[1]>=0&&pair[1]<=127,"pair bounds");check(pair[1]-pair[0]==b-a,"preserve filter offset");
        }
        List<byte[]> bank=new ArrayList<>();for(int i=0;i<128;++i)bank.add(single("Test",2,i));
        List<byte[]> exported=SoundCodec.split(SoundCodec.exportBank(bank));check(exported.size()==128,"bank count");for(int i=0;i<128;++i)check((exported.get(i)[8]&127)==i,"bank order");
        byte[] multi=single("Internal",0,0);multi[6]=0x11;for(int i=0;i<10;++i)multi[9+4+i]=(byte)' ';multi[13]='M';multi[14]='u';multi[15]='l';multi[16]='t';multi[17]='i';SoundCodec.repair(multi);
        byte[] p0=single("Part One",0,0),ps=single("Single",0,64);
        SoundCodec.Snapshot state=new SoundCodec.Snapshot(SoundCodec.join(Arrays.asList(ps,multi,p0)));check(state.multiMode,"multi mode from final arrangement order");
        check(SoundCodec.split(state.arrangement()).size()==2,"multi includes referenced single");
        SoundCodec.Snapshot singleState=new SoundCodec.Snapshot(SoundCodec.join(Arrays.asList(multi,p0,ps)));check(!singleState.multiMode,"single mode");
        Path dir=Files.createTempDirectory("rigear-library-test");File file=dir.resolve("sounds.rigearlib").toFile();SoundLibrary lib=new SoundLibrary(file);
        lib.indexFactory(new byte[][]{original},"Virus B / test");String factoryId=lib.all().get(0).id;lib.favorite(factoryId);
        lib.indexFactory(new byte[][]{original},"Virus B / test");check(lib.all().size()==1,"reindex not duplicate");check(lib.get(factoryId).favorite,"reindex keeps favorite");
        rejects(()->lib.delete(factoryId));rejects(()->lib.rename(factoryId,"Forbidden"));
        SoundLibrary.Entry copy=lib.add(original,"Usuario","Mis sonidos",true);lib.rename(copy.id,"Mi Pad");lib.favorite(copy.id);lib.addToSet("Live",factoryId);lib.addToSet("Live",copy.id);lib.reorder("Live",copy.id,-1);
        byte[] backup=lib.backup();SoundLibrary reopened=new SoundLibrary(file);check(reopened.get(copy.id).favorite,"favorite persists");check(reopened.get(copy.id).name().equals("Mi Pad"),"sound persists");check(reopened.set("Live").get(0).id.equals(copy.id),"setlist order persists");
        lib.delete(copy.id);check(lib.set("Live").size()==1,"delete cleans setlist");lib.mergeBackup(backup);check(lib.get(copy.id)!=null,"backup merge restores missing sound");
        byte[] corrupt=backup.clone();corrupt[20]^=1;rejects(()->lib.mergeBackup(corrupt));check(lib.get(factoryId).favorite,"failed import preserves existing favorite");
        System.out.println("StudioLibrary: PASS — CRC, persistence, read-only Factory, favorite reindex, copy/rename, bank export, setlist order, Multi snapshots and linked filter bounds");
    }
}
