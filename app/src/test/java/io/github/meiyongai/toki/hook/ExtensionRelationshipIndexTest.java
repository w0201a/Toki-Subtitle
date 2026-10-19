package io.github.meiyongai.toki.subtitle.hook;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.writer.pool.DexPool;
import org.jf.dexlib2.writer.io.MemoryDataStore;

public class ExtensionRelationshipIndexTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private ImmutableMethod method(String owner,String name,int register,String tag,boolean instrumented) {
        List<org.jf.dexlib2.iface.instruction.Instruction> code=new ArrayList<>();
        if(instrumented) {
            code.add(new ImmutableInstruction11n(Opcode.CONST_4,register,1));
            code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC,1,register,0,0,0,0,
                new ImmutableMethodReference("Lcom/bytedance/pumbaa/utility/method_id/MethodIDManager;","push",List.of("I"),"Z")));
        }
        for(String anchor:List.of(tag,"caption_visible","caption_translation")) code.add(new ImmutableInstruction21c(Opcode.CONST_STRING,register,new ImmutableStringReference(anchor)));
        code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC,1,register,0,0,0,0,
            new ImmutableMethodReference("Landroid/text/TextUtils;","isEmpty",List.of("Ljava/lang/CharSequence;"),"Z")));
        code.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT,register));
        return new ImmutableMethod(owner,name,List.of(),"Ljava/lang/String;",9,Set.of(),Set.of(),new ImmutableMethodImplementation(register+1,code,List.of(),List.of()));
    }
    private ClassDef type(String owner,int register,boolean instrumented) {
        return new ImmutableClassDef(owner,1,"Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),
            List.of(method(owner,"a",register,"first-caption-path",instrumented),method(owner,"b",register,"second-caption-path",instrumented)));
    }
    private String apk(ClassDef... classes) throws IOException {
        var pool=new DexPool(Opcodes.getDefault());for(var type:classes) pool.internClass(type);
        var data=new MemoryDataStore();pool.writeTo(data);var path=temporary.newFile();
        try(var zip=new ZipOutputStream(new FileOutputStream(path))) {zip.putNextEntry(new ZipEntry("classes.dex"));zip.write(data.getData());zip.closeEntry();}
        data.close();return path.getAbsolutePath();
    }
    @Test public void registerChangesAndInstrumentationKeepIndependentRelationshipEvidence() throws Exception {
        var profile=ExtensionDexIndex.profile(List.of(apk(type("LX/Old;",0,false))),Set.of("X.Old"));
        var result=ExtensionDexIndex.scan(List.of(apk(type("LX/New;",1,true))),profile);
        assertEquals("X.New",result.getProperty("class.X.Old"));
        assertEquals("a()Ljava/lang/String;",result.getProperty("X.Old:method.a()Ljava/lang/String;"));
    }
    @Test public void duplicateRelationshipCandidatesDoNotSelectArbitrarily() throws Exception {
        var profile=ExtensionDexIndex.profile(List.of(apk(type("LX/Old;",0,false))),Set.of("X.Old"));
        var result=ExtensionDexIndex.scan(List.of(apk(type("LX/One;",1,true),type("LX/Two;",1,true))),profile);
        assertNull(result.getProperty("class.X.Old"));
    }
    @Test public void detachedParametersStillResolveReactiveSubscription() {
        String owner="LX/Rx;",consumer="LX/Consumer;",disposable="LX/Disposable;";
        var target=new ImmutableMethodReference(owner,"forward",List.of(consumer,consumer,"Ljava/lang/Object;",consumer),disposable);
        var method=new ImmutableMethod(owner,"subscribe",List.of(
            new ImmutableMethodParameter(consumer,Set.of(),null),new ImmutableMethodParameter(consumer,Set.of(),null)),disposable,1,Set.of(),Set.of(),
            new ImmutableMethodImplementation(5,List.of(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,5,0,1,2,3,4,target),
                new ImmutableInstruction11x(Opcode.RETURN_OBJECT,0)),List.of(),List.of()));
        var type=new ImmutableClassDef(owner,1,"Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),List.of(method));
        Properties result=new Properties();result.setProperty("class.X.02tQ","X.Rx");
        ExtensionSemanticContracts.resolve(Map.of("X.Rx",type),result);
        assertEquals("X.Consumer",result.getProperty("class.X.02u8"));
        assertEquals("X.Disposable",result.getProperty("class.X.02tV"));
        assertEquals("subscribe(LX/Consumer;LX/Consumer;)LX/Disposable;",result.getProperty("X.02tQ:method.LJJZ(LX/02u8;LX/02u8;)LX/02tV;"));
    }
    @Test public void changedBusinessAnchorIsNotIgnoredAsInstrumentation() {
        assertNotEquals(ExtensionRelationshipIndex.evidence(method("LX/A;","a",0,"captions",false)),
            ExtensionRelationshipIndex.evidence(method("LX/B;","a",1,"comments",true)));
    }
}
