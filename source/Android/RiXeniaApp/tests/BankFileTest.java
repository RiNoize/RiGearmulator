import com.rinoize.rixenia.BankFile;
import com.rinoize.rixenia.PatchLibrary;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

public final class BankFileTest {
    private static int checks;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean test,String message){++checks;if(!test)throw new AssertionError(message);}
    private static void rejected(Checked test,String message)throws Exception{++checks;try{test.run();}catch(IOException expected){return;}throw new AssertionError(message);}
    private static byte[] single(int bank,int program,String name){
        byte[] m=new byte[265];m[0]=(byte)0xf0;m[1]=0x3e;m[2]=0x0e;m[3]=1;m[4]=0x10;m[5]=(byte)bank;m[6]=(byte)program;m[264]=(byte)0xf7;
        Arrays.fill(m,247,263,(byte)' ');for(int i=0;i<Math.min(16,name.length());++i)m[247+i]=(byte)name.charAt(i);
        int sum=0;for(int i=7;i<263;++i)sum+=m[i]&127;m[263]=(byte)(sum&127);return m;
    }
    private static byte[] concat(byte[]... arrays)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] a:arrays)b.write(a);return b.toByteArray();}
    private static void vlq(ByteArrayOutputStream out,int n){int buffer=n&127;while((n>>>=7)!=0){buffer<<=8;buffer|=(n&127)|128;}while(true){out.write(buffer&255);if((buffer&128)==0)break;buffer>>>=8;}}
    private static byte[] midi(byte[] patch,boolean split)throws Exception{
        ByteArrayOutputStream events=new ByteArrayOutputStream();events.write(0);events.write(0xff);events.write(3);events.write(4);events.write("test".getBytes("US-ASCII"));
        // Channel events + running status must not be treated as SysEx.
        events.write(new byte[]{0,(byte)0x90,60,100,0,64,100});
        if(split){
            events.write(0);events.write(0xf0);vlq(events,100);events.write(patch,1,100);
            events.write(0);events.write(0xf7);vlq(events,patch.length-101);events.write(patch,101,patch.length-101);
        }else{events.write(0);events.write(0xf0);vlq(events,patch.length-1);events.write(patch,1,patch.length-1);}
        events.write(new byte[]{0,(byte)0xff,0x2f,0});
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(bytes);
        d.writeBytes("MThd");d.writeInt(6);d.writeShort(0);d.writeShort(1);d.writeShort(96);d.writeBytes("MTrk");d.writeInt(events.size());events.writeTo(d);return bytes.toByteArray();
    }
    public static void main(String[] args)throws Exception{
        byte[] a=single(0,0,"A first"),b=single(1,127,"B last");
        BankFile bank=BankFile.parse(concat(a,b),0,0);check(bank.messages.size()==2,"Both bank messages");
        check(bank.messages.get(0)[3]==127,"Device ID normalized");
        PatchLibrary lib=new PatchLibrary().merged(bank);check(lib.slots().size()==2,"Two distinct slots");
        check(lib.name(0,0).equals("A first")&&lib.name(1,127).equals("B last"),"A/B names don't overlap");
        check(!lib.has(0,127)&&lib.name(0,127).isEmpty(),"No fabricated names");
        BankFile edit=BankFile.parse(single(0x20,0,"Edited"),1,70);check(edit.messages.get(0)[5]==1&&edit.messages.get(0)[6]==70,"Edit destination retained");
        PatchLibrary changed=lib.merged(edit);check(changed.name(1,70).equals("Edited")&&!lib.has(1,70),"Immutable merge");
        PatchLibrary restored=new PatchLibrary().merged(BankFile.parse(changed.bytes(),0,0));check(restored.name(1,70).equals("Edited"),"Persisted edit restores slot");
        check(Arrays.equals(BankFile.parse(midi(a,false),0,0).bytes(),BankFile.parse(a,0,0).bytes()),"SMF extracts exact patch");
        check(Arrays.equals(BankFile.parse(midi(b,true),0,0).bytes(),BankFile.parse(b,0,0).bytes()),"Split SMF SysEx continuation");
        byte[] bad=a.clone();bad[20]=1;rejected(()->BankFile.parse(bad,0,0),"Checksum rejection");
        rejected(()->BankFile.parse(Arrays.copyOf(a,264),0,0),"Truncated SysEx");
        rejected(()->BankFile.parse(concat(a,new byte[]{1}),0,0),"Trailing raw bytes");
        byte[] foreign=a.clone();foreign[2]=0;rejected(()->BankFile.parse(foreign,0,0),"Microwave I is not MWII");
        byte[] wrongSize=Arrays.copyOf(a,264);wrongSize[263]=(byte)0xf7;rejected(()->BankFile.parse(wrongSize,0,0),"Exact payload size");
        byte[] truncatedMidi=Arrays.copyOf(midi(a,false),50);rejected(()->BankFile.parse(truncatedMidi,0,0),"Truncated track bounded");
        byte[] tooBig=new byte[BankFile.MAX_BYTES+1];rejected(()->BankFile.parse(tooBig,0,0),"Size cap");
        byte[] multi=a.clone();multi[4]=0x11;BankFile mixed=BankFile.parse(concat(multi,a),0,0);check(mixed.skipped==1&&mixed.messages.size()==1,"Unsupported Multi reported");
        ByteArrayOutputStream interrupted=new ByteArrayOutputStream();interrupted.write(a,0,30);interrupted.write(0xf8);interrupted.write(a,30,a.length-30);check(BankFile.parse(interrupted.toByteArray(),0,0).messages.size()==1,"Realtime interleaving");
        byte[] table=new byte[265];table[0]=(byte)0xf0;table[1]=0x3e;table[2]=0x0e;table[3]=0x7f;table[4]=0x13;table[6]=96;table[264]=(byte)0xf7;
        byte[] wave=new byte[137];wave[0]=(byte)0xf0;wave[1]=0x3e;wave[2]=0x0e;wave[3]=0x7f;wave[4]=0x12;wave[5]=7;wave[6]=104;wave[136]=(byte)0xf7;
        byte[] dependencies=new PatchLibrary().merged(BankFile.parse(concat(a,table,wave),0,0)).bytes();check((dependencies[4]&255)==0x12&&(dependencies[137+4]&255)==0x13,"Wave dependencies before singles");
        byte[] badWave=wave.clone();badWave[7]=16;badWave[135]=16;rejected(()->BankFile.parse(badWave,0,0),"Nibble payload rejected even with valid checksum");
        System.out.println("BankFileTest: "+checks+" checks passed");
    }
}
