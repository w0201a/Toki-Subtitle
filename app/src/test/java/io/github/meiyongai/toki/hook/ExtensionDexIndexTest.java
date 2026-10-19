package io.github.meiyongai.toki.subtitle.hook;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.jf.dexlib2.writer.pool.DexPool;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

/** Real DEX fixtures cover renaming, splits and rejection of ambiguous or changed code. */
public class ExtensionDexIndexTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private ClassDef type(String owner, String methodName, String fieldName, int value, int register) {
        var body = new ImmutableMethodImplementation(2, List.of(
            new ImmutableInstruction11n(Opcode.CONST_4, register, value),
            new ImmutableInstruction21c(Opcode.SPUT, register, new ImmutableFieldReference(owner, fieldName, "I")),
            new ImmutableInstruction11x(Opcode.RETURN, register)), List.of(), List.of());
        var method = new ImmutableMethod(owner, methodName, List.of(), "I", 9, Set.of(), Set.of(), body);
        var field = new ImmutableField(owner, fieldName, "I", 9, null, Set.of(), Set.of());
        return new ImmutableClassDef(owner, 1, "Ljava/lang/Object;", List.of(), null, Set.of(), List.of(field), List.of(method));
    }
    private String apk(ClassDef... types) throws IOException {
        var pool = new DexPool(Opcodes.getDefault());
        for (var type : types) pool.internClass(type);
        var store = new MemoryDataStore();
        pool.writeTo(store);
        File target=temporary.newFile();
        try(var zip=new ZipOutputStream(new FileOutputStream(target))) {
            zip.putNextEntry(new ZipEntry("classes.dex")); zip.write(store.getData()); zip.closeEntry();
        }
        store.close(); return target.getAbsolutePath();
    }
    private Properties profile(ClassDef... types) throws IOException {
        Set<String> names=new HashSet<>();
        for(var type:types) names.add(type.getType().substring(1,type.getType().length()-1).replace('/','.'));
        return ExtensionDexIndex.profile(List.of(apk(types)),names);
    }
    @Test public void discoversRenamedClassesMethodsAndFieldsAcrossSplits() throws IOException {
        var reference=profile(type("LX/A;","LIZ","LIZIZ",1,0), type("LX/B;","LIZ","LIZIZ",2,0));
        var result=ExtensionDexIndex.scan(List.of(apk(type("LX/NewA;","newMethod","newField",1,0)),
            apk(type("LX/NewB;","changed","field",2,0))),reference);
        assertEquals("X.NewA",result.getProperty("class.X.A"));
        assertEquals("newMethod()I",result.getProperty("X.A:method.LIZ()I"));
        assertEquals("newField",result.getProperty("X.A:field.LIZIZ"));
        assertEquals("X.NewB",result.getProperty("class.X.B"));
    }
    @Test public void rejectsDuplicateCandidates() throws IOException {
        var reference=profile(type("LX/A;","LIZ","LIZIZ",1,0));
        var result=ExtensionDexIndex.scan(List.of(apk(type("LX/B;","a","b",1,0),type("LX/C;","a","b",1,0))),reference);
        assertFalse(result.containsKey("class.X.A"));
        assertEquals("structural candidates=2",result.getProperty("error.X.A"));
    }
    @Test public void refusesManyReferenceRolesClaimingOneClass() throws IOException {
        var reference=profile(type("LX/A;","a","b",1,0),type("LX/B;","a","b",1,0));
        var result=ExtensionDexIndex.scan(List.of(apk(type("LX/C;","a","b",1,0))),reference);
        assertFalse(result.containsKey("class.X.A")); assertFalse(result.containsKey("class.X.B"));
    }
    @Test public void changedConstantOrRegisterDataFlowCannotMatch() throws IOException {
        var reference=profile(type("LX/A;","a","b",1,0));
        for(var changed:List.of(type("LX/B;","a","b",2,0),type("LX/B;","a","b",1,1))) {
            var result=ExtensionDexIndex.scan(List.of(apk(changed)),reference);
            assertFalse(result.containsKey("class.X.A"));
            assertEquals("structural candidates=0",result.getProperty("error.X.A"));
        }
    }
    @Test public void knownProfileIdentityPreservesDescriptors() throws IOException {
        var result=ExtensionDexIndex.identity(profile(type("LX/A;","LIZ","LIZIZ",1,0)));
        assertEquals("X.A",result.getProperty("class.X.A"));
        assertEquals("LIZ()I",result.getProperty("X.A:method.LIZ()I"));
        assertEquals("LIZIZ",result.getProperty("X.A:field.LIZIZ"));
    }
    @Test public void unknownProfileFormatIsRejected() throws IOException {
        var reference=profile(type("LX/A;","a","b",1,0)); reference.setProperty("format","other");
        assertThrows(IllegalArgumentException.class,()->ExtensionDexIndex.scan(List.of(),reference));
    }
    private ClassDef service(String owner, String first, String second) {
        var a = new ImmutableMethod(owner, first, List.of(), "Ljava/lang/String;", 0x401, Set.of(), Set.of(), null);
        var b = new ImmutableMethod(owner, second, List.of(), "Ljava/lang/String;", 0x401, Set.of(), Set.of(), null);
        return new ImmutableClassDef(owner, 0x601, "Ljava/lang/Object;", List.of(), null, Set.of(), List.of(), List.of(a,b));
    }
    private ClassDef caller(String target, String first, String second) {
        String owner="Lexample/Caller;";
        List<ImmutableMethod> methods=new ArrayList<>();
        int number=0;
        for(String name:List.of(first,second)) {
            var body=new ImmutableMethodImplementation(2,List.of(
                new ImmutableInstruction21c(Opcode.CONST_STRING,1,new ImmutableStringReference("role-"+number)),
                new ImmutableInstruction35c(Opcode.INVOKE_INTERFACE,1,0,0,0,0,0,
                    new ImmutableMethodReference(target,name,List.of(),"Ljava/lang/String;")),
                new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT,1),
                new ImmutableInstruction11x(Opcode.RETURN_OBJECT,1)),List.of(),List.of());
            methods.add(new ImmutableMethod(owner,"call"+number++,List.of(),"Ljava/lang/String;",1,Set.of(),Set.of(),body));
        }
        return new ImmutableClassDef(owner,1,"Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),methods);
    }
    @Test public void independentCallSitesResolveIdenticalAbstractMethods() throws IOException {
        var paths=List.of(apk(service("LX/I;","LIZ","LIZIZ"),caller("LX/I;","LIZ","LIZIZ")));
        var reference=ExtensionDexIndex.profile(paths,Set.of("X.I"));
        var result=ExtensionDexIndex.scan(List.of(apk(service("LX/J;","a","b"),caller("LX/J;","b","a"))),reference);
        assertEquals("X.J",result.getProperty("class.X.I"));
        assertEquals("b()Ljava/lang/String;",result.getProperty("X.I:method.LIZ()Ljava/lang/String;"));
        assertEquals("a()Ljava/lang/String;",result.getProperty("X.I:method.LIZIZ()Ljava/lang/String;"));
    }
    @Test public void missingCallEvidenceLeavesAbstractMethodAmbiguous() throws IOException {
        var reference=ExtensionDexIndex.profile(List.of(apk(service("LX/I;","LIZ","LIZIZ"),caller("LX/I;","LIZ","LIZIZ"))),Set.of("X.I"));
        var result=ExtensionDexIndex.scan(List.of(apk(service("LX/J;","a","b"))),reference);
        assertFalse(result.containsKey("X.I:method.LIZ()Ljava/lang/String;"));
        assertEquals("member candidates=2",result.getProperty("error.X.I:method.LIZ()Ljava/lang/String;"));
    }

    @Test public void descriptiveSerializedFieldNamesDisambiguateUnusedFields() throws IOException {
        String owner="Lexample/Result;";
        var fields=List.of(
            new ImmutableField(owner,"translatedContent","Ljava/lang/String;",1,null,Set.of(),Set.of()),
            new ImmutableField(owner,"sourceContent","Ljava/lang/String;",1,null,Set.of(),Set.of()));
        var model=new ImmutableClassDef(owner,1,"Ljava/lang/Object;",List.of(),null,Set.of(),fields,List.of());
        var result=ExtensionDexIndex.scan(List.of(apk(model)),profile(model));
        assertEquals("translatedContent",result.getProperty("example.Result:field.translatedContent"));
    }

    @Test public void namedModelAllowsUnrelatedAdditionsButRejectsChangedAccessedMethod() throws IOException {
        var old=type("Lexample/Model;","read","value",1,0);
        var reference=profile(old);
        var added=type("Lexample/Model;","extra","other",2,0);
        List<org.jf.dexlib2.iface.Method> methods=new ArrayList<>();
        old.getMethods().forEach(methods::add); added.getMethods().forEach(methods::add);
        List<org.jf.dexlib2.iface.Field> fields=new ArrayList<>();
        old.getFields().forEach(fields::add); added.getFields().forEach(fields::add);
        var larger=new ImmutableClassDef(old.getType(),1,"Ljava/lang/Object;",List.of(),null,Set.of(),fields,methods);
        var result=ExtensionDexIndex.scan(List.of(apk(larger)),reference);
        assertEquals("example.Model",result.getProperty("class.example.Model"));
        assertEquals("read()I",result.getProperty("example.Model:method.read()I"));
        result=ExtensionDexIndex.scan(List.of(apk(type("Lexample/Model;","read","value",2,0))),reference);
        assertFalse(result.containsKey("example.Model:method.read()I"));
    }

    @Test public void nativeZeroCandidatesNeverFallsBackToFullObfuscatedScan() throws IOException {
        var reference=profile(type("LX/A;","LIZ","LIZIZ",1,0));
        var paths=List.of(apk(type("LX/B;","a","b",1,0)));
        assertFalse(ExtensionDexIndex.scan(paths,reference,Set.of()).containsKey("class.X.A"));
        assertEquals(ExtensionDexIndex.scan(paths,reference),ExtensionDexIndex.scan(paths,reference,Set.of("LX/B;")));
    }

    private ClassDef large(String owner, int changed, boolean differentHierarchy) {
        List<org.jf.dexlib2.iface.Method> methods=new ArrayList<>();
        for (int i=0;i<10;i++) {
            var body=new ImmutableMethodImplementation(1,List.of(
                new ImmutableInstruction21s(Opcode.CONST_16,0,i==9?100+changed:i),
                new ImmutableInstruction11x(Opcode.RETURN,0)),List.of(),List.of());
            var method=new ImmutableMethod(owner,"m"+i,List.of(),"I",9,Set.of(),Set.of(),body);
            methods.add(method);
        }
        var field=new ImmutableField(owner,"LIZ","I",9,null,Set.of(),Set.of());
        return new ImmutableClassDef(owner,1,differentHierarchy?"Ljava/lang/Number;":"Ljava/lang/Object;",List.of(),null,Set.of(),List.of(field),methods);
    }
    @Test public void partialClassMatchStillRejectsChangedMethodAndHierarchy() throws IOException {
        var reference=profile(large("LX/Old;",1,false));
        var result=ExtensionDexIndex.scan(List.of(apk(large("LX/New;",2,false))),reference);
        assertEquals("X.New",result.getProperty("class.X.Old"));
        assertEquals("m0()I",result.getProperty("X.Old:method.m0()I"));
        assertFalse(result.containsKey("X.Old:method.m9()I"));
        result=ExtensionDexIndex.scan(List.of(apk(large("LX/New;",2,true))),reference);
        assertFalse(result.containsKey("class.X.Old"));
    }
    @Test public void partialClassMatchNeverChoosesAmongTwoCandidates() throws IOException {
        var reference=profile(large("LX/Old;",1,false));
        var result=ExtensionDexIndex.scan(List.of(apk(large("LX/New;",2,false),large("LX/Other;",3,false))),reference);
        assertFalse(result.containsKey("class.X.Old"));
    }
    @Test public void identityDoesNotTreatWitnessMetadataAsResolvedMembers() throws IOException {
        var reference=profile(type("LX/A;","a","b",1,0));
        reference.setProperty("implementation.X.A:method.a()I","fingerprint");
        var result=ExtensionDexIndex.identity(reference);
        assertFalse(result.containsKey("implementation.X.A:method.a()I"));
    }
    private ClassDef implementation(String first,String second) {
        String owner="Lcom/ss/android/ugc/aweme/translation/service/TranslationServiceImpl;";
        List<org.jf.dexlib2.iface.Method> methods=new ArrayList<>();
        int number=0;
        for (String name:List.of(first,second)) {
            var body=new ImmutableMethodImplementation(2,List.of(
                new ImmutableInstruction21c(Opcode.CONST_STRING,0,new ImmutableStringReference("transport-"+number++)),
                new ImmutableInstruction11x(Opcode.RETURN_OBJECT,0)),List.of(),List.of());
            methods.add(new ImmutableMethod(owner,name,List.of(),"Ljava/lang/String;",1,Set.of(),Set.of(),body));
        }
        return new ImmutableClassDef(owner,1,"Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),methods);
    }
    @Test public void implementationEvidenceResolvesFacadeWithoutOldCallers() throws IOException {
        var reference=ExtensionDexIndex.profile(List.of(apk(service("LX/03hV;","LIZ","LIZIZ"),implementation("LIZ","LIZIZ"))),Set.of("X.03hV"));
        var result=ExtensionDexIndex.scan(List.of(apk(service("LX/NewFacade;","a","b"),implementation("b","a"))),reference);
        assertEquals("b()Ljava/lang/String;",result.getProperty("X.03hV:method.LIZ()Ljava/lang/String;"));
        assertEquals("a()Ljava/lang/String;",result.getProperty("X.03hV:method.LIZIZ()Ljava/lang/String;"));
    }
    @Test public void semanticGateFingerprintToleratesCalleeRenameButNotChangedLiteral() {
        var old=caller("LX/I;","LIZ","LIZIZ");
        var renamed=caller("LX/J;","a","b");
        assertNotEquals(HostDexIndex.literals(old),HostDexIndex.literals(renamed));
        assertEquals(HostDexIndex.semanticLiterals(old),HostDexIndex.semanticLiterals(renamed));
        assertNotEquals(HostDexIndex.semanticLiterals(type("LX/A;","a","b",1,0)),
            HostDexIndex.semanticLiterals(type("LX/A;","a","b",2,0)));
    }
}
