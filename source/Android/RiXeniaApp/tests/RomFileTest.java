import com.rinoize.rixenia.RomFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class RomFileTest {
    private static int checks;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean ok,String name){++checks;if(!ok)throw new AssertionError(name);}
    private static void rejected(Checked test,String name)throws Exception{++checks;try{test.run();}catch(IOException expected){return;}throw new AssertionError(name);}
    private static byte[] rom(){byte[] r=new byte[262144];for(int i=0;i<r.length;++i)r[i]=(byte)(i*31);r[0]=(byte)0xc0;r[1]=(byte)0xde;r[2]=r[3]=0;r[0x2f]='2';r[0x30]='.';r[0x31]='3';r[0x32]='3';return r;}
    private static byte[] zip(String[] names,byte[][] data)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(out)){for(int i=0;i<names.length;++i){z.putNextEntry(new ZipEntry(names[i]));z.write(data[i]);z.closeEntry();}}return out.toByteArray();
    }
    public static void main(String[] args)throws Exception{
        byte[] r=rom();check(Arrays.equals(RomFile.parse(r,"full.bin").bytes,r),"Full ROM retained");
        byte[] a=new byte[131072],b=new byte[131072];for(int i=0;i<a.length;++i){a[i]=r[2*i];b[i]=r[2*i+1];}
        byte[] archive=zip(new String[]{"arbitrary/upper.bin","../lower.bin","update.mid"},new byte[][]{a,b,new byte[]{1,2,3}});
        RomFile result=RomFile.parse(archive,"firmware.zip");check(Arrays.equals(result.bytes,r),"Header-based half interleave independent of filename or archive path");
        check(result.notice.contains("2.33")&&result.notice.contains("no se aplica"),"Existing ROM version and ignored update disclosed");
        check(Arrays.equals(RomFile.parse(zip(new String[]{"full.bin"},new byte[][]{r}),"data").bytes,r),"ZIP signature detection");
        rejected(()->RomFile.parse(a,"half.bin"),"A half cannot boot alone");
        rejected(()->RomFile.parse(zip(new String[]{"update.mid"},new byte[][]{new byte[100]}),"firmware.zip"),"MIDI alone lacks waves");
        rejected(()->RomFile.parse(zip(new String[]{"half.bin"},new byte[][]{a}),"firmware.zip"),"Incomplete pair rejected");
        byte[] damaged=r.clone();damaged[0x30]='x';rejected(()->RomFile.parse(damaged,"bad.bin"),"Bad version header rejected");
        byte[] other=r.clone();other[1000]^=1;rejected(()->RomFile.parse(zip(new String[]{"a.bin","b.bin"},new byte[][]{r,other}),"ambiguous.zip"),"Ambiguous complete ROMs rejected");
        byte[] expanded=new byte[RomFile.MAX_ARCHIVE_BYTES+1];rejected(()->RomFile.parse(zip(new String[]{"ignored.txt"},new byte[][]{expanded}),"oversized.zip"),"Skipped entries count against expansion cap");
        if(args.length>0){
            RomFile real=RomFile.parse(Files.readAllBytes(Paths.get(args[0])),"XeniaFirmware.zip");StringBuilder hash=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(real.bytes))hash.append(String.format("%02x",value));
            check(real.bytes.length==262144,"User archive full size");
            System.out.println("Local archive verified: "+real.notice+" · SHA-256 "+hash);
        }
        System.out.println("RomFileTest: "+checks+" checks passed");
    }
}
