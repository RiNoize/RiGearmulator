import com.rinoize.rixenia.AudioSizing;

public final class AudioSizingTest {
    public static void main(String[] args){
        int[] blocks={256,512,1024},expected={308,615,1229};
        for(int i=0;i<blocks.length;++i){
            if(AudioSizing.maxOutputFrames(blocks[i],40000,48000)!=expected[i])throw new AssertionError("6/5 conversion size");
            if(AudioSizing.maxOutputFrames(blocks[i],40000,40000)!=blocks[i])throw new AssertionError("Native path");
        }
        try{AudioSizing.maxOutputFrames(512,46875,48000);throw new AssertionError("Virus rate must not be accepted");}catch(IllegalArgumentException expectedError){}
        try{AudioSizing.maxOutputFrames(-1,40000,48000);throw new AssertionError("Negative block");}catch(IllegalArgumentException expectedError){}
        System.out.println("AudioSizingTest: native and 40000→48000 blocks passed");
    }
}
