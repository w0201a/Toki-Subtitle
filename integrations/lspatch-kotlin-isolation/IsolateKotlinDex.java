import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.Adler32;

/** Build-time framework-private Kotlin runtime isolation; never touches a target application. */
public final class IsolateKotlinDex {
    private record Entry(int start,int end,String text) {}
    private static int u32(byte[] data,int offset) { return ByteBuffer.wrap(data,offset,4).order(ByteOrder.LITTLE_ENDIAN).getInt(); }
    private static void put32(byte[] data,int offset,int value) { ByteBuffer.wrap(data,offset,4).order(ByteOrder.LITTLE_ENDIAN).putInt(value); }
    private static int[] uleb(byte[] data,int start) {
        int value=0,at=start;
        for(int shift=0;shift<35;shift+=7) { if(at>=data.length)throw new IllegalArgumentException("Truncated ULEB128");int b=data[at++]&255;value|=(b&127)<<shift;if(b<128)return new int[]{value,at}; }
        throw new IllegalArgumentException("Oversized ULEB128");
    }
    private static String mutf(byte[] data,int start,int end) {
        StringBuilder text=new StringBuilder();int i=start;
        while(i<end) { int a=data[i++]&255;
            if((a&128)==0)text.append((char)a);
            else if((a&224)==192) { if(i>=end)throw new IllegalArgumentException("Truncated MUTF8");text.append((char)(((a&31)<<6)|(data[i++]&63))); }
            else if((a&240)==224) { if(i+1>=end)throw new IllegalArgumentException("Truncated MUTF8");text.append((char)(((a&15)<<12)|((data[i++]&63)<<6)|(data[i++]&63))); }
            else throw new IllegalArgumentException("Unsupported DEX MUTF8 sequence");
        }
        return text.toString();
    }
    private static byte[] sha1(byte[] data) throws Exception { MessageDigest h=MessageDigest.getInstance("SHA-1");h.update(data,32,data.length-32);return h.digest(); }
    public static byte[] isolate(byte[] data) throws Exception {
        if(data.length<112||data[0]!='d'||data[1]!='e'||data[2]!='x'||data[3]!='\n'||data[7]!=0)throw new IllegalArgumentException("Not a DEX file");
        if(u32(data,32)!=data.length||u32(data,40)!=0x12345678)throw new IllegalArgumentException("Unsupported DEX header");
        if(!Arrays.equals(sha1(data),Arrays.copyOfRange(data,12,32)))throw new IllegalArgumentException("DEX SHA1 mismatch");
        Adler32 crc=new Adler32();crc.update(data,12,data.length-12);if((int)crc.getValue()!=u32(data,8))throw new IllegalArgumentException("DEX checksum mismatch");
        int count=u32(data,56),table=u32(data,60);if(count<0||table<112||(long)table+4L*count>data.length)throw new IllegalArgumentException("Invalid DEX string table");
        List<Entry> entries=new ArrayList<>();String previous=null;boolean intrinsics=false;
        for(int n=0;n<count;n++) { int[] size=uleb(data,u32(data,table+4*n));int end=size[1];while(end<data.length&&data[end]!=0)end++;if(end==data.length)throw new IllegalArgumentException("Unterminated string");String text=mutf(data,size[1],end);
            if(text.length()!=size[0])throw new IllegalArgumentException("UTF16 size mismatch");if(previous!=null&&previous.compareTo(text)>=0)throw new IllegalArgumentException("Unsorted DEX strings");previous=text;
            if(text.contains("kotlim/")||text.contains("kotlim.")||text.contains("kotlimx/")||text.contains("kotlimx."))throw new IllegalArgumentException("Reserved isolated namespace already present");
            intrinsics|=text.equals("Lkotlin/jvm/internal/Intrinsics;");entries.add(new Entry(size[1],end,text));
        }
        if(!intrinsics)throw new IllegalArgumentException("Bundled Kotlin Intrinsics not found");
        byte[] output=data.clone();previous=null;int changed=0;
        for(Entry entry:entries) { String isolated=entry.text.replace("kotlin","kotlim");if(previous!=null&&previous.compareTo(isolated)>=0)throw new IllegalArgumentException("Relocation changes DEX ordering; full rewriter required");previous=isolated;
            if(!entry.text.equals(isolated)) { changed++;for(int p=entry.start;p+5<entry.end;p++)if(output[p]=='k'&&output[p+1]=='o'&&output[p+2]=='t'&&output[p+3]=='l'&&output[p+4]=='i'&&output[p+5]=='n')output[p+5]='m'; }
        }
        System.arraycopy(sha1(output),0,output,12,20);crc.reset();crc.update(output,12,output.length-12);put32(output,8,(int)crc.getValue());
        System.out.println("Kotlin runtime isolated: "+changed+" strings; all DEX offsets and sorted IDs retained");return output;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Usage: IsolateKotlinDex original-loader.dex isolated-loader.dex");Path input=Path.of(args[0]),output=Path.of(args[1]);if(input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize()))throw new IllegalArgumentException("Use separate input/output");byte[] fixed=isolate(Files.readAllBytes(input));Files.createDirectories(output.toAbsolutePath().getParent());Files.write(output,fixed);
    }
}
