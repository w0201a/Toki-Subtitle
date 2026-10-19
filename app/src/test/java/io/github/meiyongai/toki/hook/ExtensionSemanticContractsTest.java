package io.github.meiyongai.toki.subtitle.hook;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;

public class ExtensionSemanticContractsTest {
    private static final String AWEME="Lcom/ss/android/ugc/aweme/feed/model/Aweme;";
    private Method writer(String name,String field) {
        var body=new ImmutableMethodImplementation(2,List.of(
            new ImmutableInstruction22c(Opcode.IPUT_OBJECT,1,0,new ImmutableFieldReference("LX/Renderer;",field,AWEME)),
            new ImmutableInstruction10x(Opcode.RETURN_VOID)),List.of(),List.of());
        return new ImmutableMethod("LX/Renderer;",name,List.of(new ImmutableMethodParameter(AWEME,Set.of(),null)),"V",17,Set.of(),Set.of(),body);
    }
    private ClassDef type(String owner,List<? extends Method> methods) {
        return new ImmutableClassDef(owner,1,"Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),methods);
    }
    private Properties rendererMappings() {
        var p=new Properties();p.setProperty("class.X.09Gd","X.Renderer");p.setProperty("X.09Gd:field.LLLZIIL","current");return p;
    }
    @Test public void descriptionBindingUsesMappedVideoFieldAndRejectsAmbiguousWriters() {
        var result=rendererMappings();
        ExtensionSemanticContracts.resolve(Map.of("X.Renderer",type("LX/Renderer;",List.of(writer("newBind","current"),writer("other","previous")))),result);
        assertEquals("newBind("+AWEME+")V",result.getProperty("X.09Gd:method.LLFZ("+AWEME+")V"));
        result=rendererMappings();
        ExtensionSemanticContracts.resolve(Map.of("X.Renderer",type("LX/Renderer;",List.of(writer("first","current"),writer("second","current")))),result);
        assertFalse(result.containsKey("X.09Gd:method.LLFZ("+AWEME+")V"));
    }
    @Test public void captionFrameRequiresRendererCallAndBothFrameFields() {
        String assem="com.ss.android.ugc.aweme.autocaption.refactor.CLACaptionAssemV2";
        String owner="L"+assem.replace('.','/')+";";
        var p=new Properties();
        p.setProperty("class.X.0B14","X.Frame");p.setProperty("class.X.0B1g","X.Renderer");
        p.setProperty("X.0B14:field.LIZ","text");p.setProperty("X.0B14:field.LIZIZ","state");
        p.setProperty(assem+":field.LLLLLILLIL","renderer");
        p.setProperty("X.0B1g:method.LJIIIZ(Ljava/lang/String;LX/0B17;Ljava/lang/String;LX/0B18;)V","show(Ljava/lang/String;LX/State;Ljava/lang/String;LX/Mode;)V");
        for (boolean complete:List.of(true,false)) {
            var code=new ArrayList<org.jf.dexlib2.iface.instruction.Instruction>();
            code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT,0,1,new ImmutableFieldReference(owner,"renderer","LX/Renderer;")));
            code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT,0,1,new ImmutableFieldReference("LX/Frame;","text","Ljava/lang/String;")));
            if (complete) code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT,0,1,new ImmutableFieldReference("LX/Frame;","state","LX/State;")));
            code.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,5,0,1,2,3,4,new ImmutableMethodReference("LX/Renderer;","show",List.of("Ljava/lang/String;","LX/State;","Ljava/lang/String;","LX/Mode;"),"V")));
            code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
            var method=new ImmutableMethod(owner,"renamedFrame",List.of(new ImmutableMethodParameter("LX/Frame;",Set.of(),null)),"V",17,Set.of(),Set.of(),new ImmutableMethodImplementation(5,code,List.of(),List.of()));
            var result=new Properties();result.putAll(p);
            ExtensionSemanticContracts.resolve(Map.of(assem,type(owner,List.of(method))),result);
            assertEquals(complete,result.containsKey(assem+":method.bs(LX/0B14;)V"));
        }
    }
}
