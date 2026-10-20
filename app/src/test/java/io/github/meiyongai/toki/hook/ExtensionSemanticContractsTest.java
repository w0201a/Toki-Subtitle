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
    @Test public void targetLanguageComesFromVerifiedTranslationRequestAndRejectsTwoGetters() {
        String service="com.ss.android.ugc.aweme.translation.service.TranslationServiceImpl",owner="L"+service.replace('.','/')+";";
        for(boolean ambiguous:List.of(false,true)) {
            List<Method> methods=new ArrayList<>();
            var requestCode=new ArrayList<org.jf.dexlib2.iface.instruction.Instruction>();
            for(String getter:ambiguous?List.of("target","other"):List.of("target")) {
                requestCode.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,1,0,0,0,0,0,
                    new ImmutableMethodReference("LX/Facade;",getter,List.of(),"Ljava/lang/String;")));
                var code=List.of(
                    new ImmutableInstruction35c(Opcode.INVOKE_INTERFACE,1,0,0,0,0,0,new ImmutableMethodReference(
                        "Lcom/ss/android/ugc/aweme/translation/service/ITranslationLangKevaService;","read",List.of(),"Ljava/lang/String;")),
                    new ImmutableInstruction35c(Opcode.INVOKE_STATIC,1,0,0,0,0,0,new ImmutableMethodReference(
                        "Landroid/text/TextUtils;","isEmpty",List.of("Ljava/lang/CharSequence;"),"Z")),
                    new ImmutableInstruction11x(Opcode.RETURN_OBJECT,0));
                methods.add(new ImmutableMethod(owner,getter,List.of(),"Ljava/lang/String;",1,Set.of(),Set.of(),new ImmutableMethodImplementation(2,code,List.of(),List.of())));
            }
            requestCode.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT,0));
            methods.add(new ImmutableMethod(owner,"translate",List.of(new ImmutableMethodParameter("Ljava/lang/String;",Set.of(),null)),"LX/Rx;",1,Set.of(),Set.of(),
                new ImmutableMethodImplementation(2,requestCode,List.of(),List.of())));
            Properties result=new Properties();result.setProperty("class.X.03hV","X.Facade");
            result.setProperty("X.03hV:method.LJIIJ(Ljava/lang/String;)LX/02tQ;","translate(Ljava/lang/String;)LX/Rx;");
            ExtensionSemanticContracts.resolve(Map.of(service,type(owner,methods)),result);
            assertEquals(!ambiguous,result.containsKey("X.03hV:method.LLILLIZIL()Ljava/lang/String;"));
        }
    }
    @Test public void captionServiceRequiresModelTrackReadAndUniqueTypedSelection() {
        String name="com.ss.android.ugc.aweme.service.CaptionServiceImpl",owner="L"+name.replace('.','/')+";";
        for(boolean tracks:List.of(true,false)) {
            var code=new ArrayList<org.jf.dexlib2.iface.instruction.Instruction>();
            code.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,1,1,0,0,0,0,
                new ImmutableMethodReference(AWEME,"getVideo",List.of(),"Lcom/ss/android/ugc/aweme/feed/model/Video;")));
            code.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,1,0,0,0,0,0,
                new ImmutableMethodReference("Lcom/ss/android/ugc/aweme/feed/model/Video;","getCaptionModel",List.of(),"Lcom/ss/android/ugc/aweme/feed/model/CaptionModel;")));
            if(tracks) code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT,0,1,
                new ImmutableFieldReference("Lcom/ss/android/ugc/aweme/feed/model/CaptionModel;","captionList","Ljava/util/List;")));
            code.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT,0));
            var method=new ImmutableMethod(owner,"select",List.of(new ImmutableMethodParameter(AWEME,Set.of(),null)),"LX/Selection;",1,Set.of(),Set.of(),
                new ImmutableMethodImplementation(2,code,List.of(),List.of()));
            for(boolean duplicate:List.of(false,true)) {
                List<Method> methods=new ArrayList<>();methods.add(method);
                if(duplicate) methods.add(new ImmutableMethod(owner,"other",method.getParameters(),method.getReturnType(),1,Set.of(),Set.of(),method.getImplementation()));
                Properties result=new Properties();result.setProperty("class."+name,name);result.setProperty("class.X.08ZQ","X.Selection");
                ExtensionSemanticContracts.resolve(Map.of(name,type(owner,methods)),result);
                assertEquals(tracks&&!duplicate,result.containsKey(name+":method.LJIIIZ("+AWEME+")LX/08ZQ;"));
            }
        }
    }
    private Method eligibility(String owner,String name,boolean complete) {
        var instructions=new ArrayList<org.jf.dexlib2.iface.instruction.Instruction>();
        instructions.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,1,1,0,0,0,0,
            new ImmutableMethodReference(AWEME,"isDescTranslatable",List.of(),"Z")));
        instructions.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC,1,0,0,0,0,0,
            new ImmutableMethodReference("Landroid/text/TextUtils;","isEmpty",List.of("Ljava/lang/CharSequence;"),"Z")));
        if (complete) instructions.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC,2,0,1,0,0,0,
            new ImmutableMethodReference("Landroid/text/TextUtils;","equals",List.of("Ljava/lang/CharSequence;","Ljava/lang/CharSequence;"),"Z")));
        instructions.add(new ImmutableInstruction11x(Opcode.RETURN,0));
        return new ImmutableMethod(owner,name,List.of(new ImmutableMethodParameter(AWEME,Set.of(),null)),"Z",17,Set.of(),Set.of(),
            new ImmutableMethodImplementation(2,instructions,List.of(),List.of()));
    }
    @Test public void titleGateSurvivesRenameButRejectsIncompleteOrAmbiguousEvidence() {
        String service="com.ss.android.ugc.aweme.translation.service.CLATranslatabilityServiceImpl";
        String owner="L"+service.replace('.','/')+";";
        String role=service+":method.LJ("+AWEME+")Z";
        for (int count:List.of(0,1,2)) {
            List<Method> methods=new ArrayList<>();
            methods.add(eligibility(owner,"decoy",false));
            for (int i=0;i<count;i++) methods.add(eligibility(owner,"renamed"+i,true));
            Properties result=new Properties(); result.setProperty("class."+service,service);
            ExtensionSemanticContracts.resolve(Map.of(service,type(owner,methods)),result);
            assertEquals(count==1,result.containsKey(role));
            if(count==1) assertEquals("renamed0("+AWEME+")Z",result.getProperty(role));
        }
    }
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
        for (boolean complete:List.of(true,false)) for (boolean derived:List.of(true,false)) {
            var code=new ArrayList<org.jf.dexlib2.iface.instruction.Instruction>();
            code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT,0,1,new ImmutableFieldReference(owner,"renderer","LX/Renderer;")));
            code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT,0,1,new ImmutableFieldReference("LX/Frame;","text","Ljava/lang/String;")));
            if (complete) code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT,0,1,new ImmutableFieldReference("LX/Frame;","state","LX/State;")));
            code.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,5,0,1,2,3,4,new ImmutableMethodReference("LX/Renderer;","show",List.of("Ljava/lang/String;","LX/State;","Ljava/lang/String;","LX/Mode;"),"V")));
            code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
            var method=new ImmutableMethod(owner,"renamedFrame",List.of(new ImmutableMethodParameter("LX/Frame;",Set.of(),null)),"V",17,Set.of(),Set.of(),new ImmutableMethodImplementation(5,code,List.of(),List.of()));
            var result=new Properties();result.putAll(p);
            String renderRole="X.0B1g:method.LJIIIZ(Ljava/lang/String;LX/0B17;Ljava/lang/String;LX/0B18;)V";
            if(derived) result.remove(renderRole);
            result.setProperty("class.X.0B17","X.State");result.setProperty("class.X.0B18","X.Mode");
            var target=new ImmutableMethod("LX/Renderer;","show",List.of(
                new ImmutableMethodParameter("Ljava/lang/String;",Set.of(),null),new ImmutableMethodParameter("LX/State;",Set.of(),null),
                new ImmutableMethodParameter("Ljava/lang/String;",Set.of(),null),new ImmutableMethodParameter("LX/Mode;",Set.of(),null)),
                "V",1,Set.of(),Set.of(),new ImmutableMethodImplementation(5,List.of(new ImmutableInstruction10x(Opcode.RETURN_VOID)),List.of(),List.of()));
            ExtensionSemanticContracts.resolve(Map.of(assem,type(owner,List.of(method)),"X.Renderer",type("LX/Renderer;",List.of(target))),result);
            assertEquals(complete,result.containsKey(assem+":method.bs(LX/0B14;)V"));
            if(derived) assertEquals(complete,result.containsKey(renderRole));
        }
    }
    @Test public void captionRendererCanGainTrailingParametersAndStateEnumWithoutLosingItsFrameRelationship() {
        String assem="com.ss.android.ugc.aweme.autocaption.refactor.CLACaptionAssemV2",owner="L"+assem.replace('.','/')+";";
        var signature=List.of("Ljava/lang/String;","LX/NewState;","Ljava/lang/String;","Lexample/Mode;","Z");
        var code=List.of(
            new ImmutableInstruction22c(Opcode.IGET_OBJECT,0,1,new ImmutableFieldReference(owner,"actualRenderer","LX/NewRenderer;")),
            new ImmutableInstruction22c(Opcode.IGET_OBJECT,2,1,new ImmutableFieldReference("LX/Frame;","text","Ljava/lang/String;")),
            new ImmutableInstruction22c(Opcode.IGET_OBJECT,3,1,new ImmutableFieldReference("LX/Frame;","state","LX/NewState;")),
            new ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE,0,6,new ImmutableMethodReference("LX/NewRenderer;","draw",signature,"V")),
            new ImmutableInstruction10x(Opcode.RETURN_VOID));
        var method=new ImmutableMethod(owner,"frame",List.of(new ImmutableMethodParameter("LX/Frame;",Set.of(),null)),"V",1,Set.of(),Set.of(),new ImmutableMethodImplementation(6,code,List.of(),List.of()));
        var frame=new ImmutableClassDef("LX/Frame;",1,"Ljava/lang/Object;",List.of(),null,Set.of(),List.of(
            new ImmutableField("LX/Frame;","text","Ljava/lang/String;",1,null,Set.of(),Set.of()),
            new ImmutableField("LX/Frame;","state","LX/NewState;",1,null,Set.of(),Set.of())),List.of());
        Properties result=new Properties();result.setProperty("class.X.0B14","X.Frame");
        result.setProperty("X.0B14:field.LIZ","text");result.setProperty("X.0B14:field.LIZIZ","state");
        ExtensionSemanticContracts.resolve(Map.of(assem,type(owner,List.of(method)),"X.Frame",frame),result);
        assertEquals("X.NewState",result.getProperty("class.X.0B17"));
        assertEquals("example.Mode",result.getProperty("class.X.0B18"));
        assertEquals("X.NewRenderer",result.getProperty("class.X.0B1g"));
        assertEquals("draw(Ljava/lang/String;LX/NewState;Ljava/lang/String;Lexample/Mode;Z)V",result.getProperty("X.0B1g:method.LJIIIZ(Ljava/lang/String;LX/0B17;Ljava/lang/String;LX/0B18;)V"));
    }

    @Test public void changedCaptionFrameIsRecoveredFromArgumentFlowWithNoPriorMappings() {
        String assem="com.ss.android.ugc.aweme.autocaption.refactor.CLACaptionAssemV2",owner="L"+assem.replace('.','/')+";";
        for(int variant:List.of(0,1,2,3,4,5)) {
            List<Method> methods=new ArrayList<>();
            for(int n=0;n<(variant==1?2:1);n++) {
                String frame="LX/NewFrame"+n+";";
                var code=new ArrayList<org.jf.dexlib2.iface.instruction.Instruction>();
                code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT,0,6,new ImmutableFieldReference(owner,"renderer","LX/NewRenderer;")));
                code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT,1,7,new ImmutableFieldReference(frame,"text","Ljava/lang/String;")));
                code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT,2,7,new ImmutableFieldReference(frame,"state","LX/NewState;")));
                code.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT,3,7,new ImmutableFieldReference(frame,"text","Ljava/lang/String;")));
                if(variant==5) { code.add(new ImmutableInstruction21t(Opcode.IF_EQZ,4,3));code.add(new ImmutableInstruction10x(Opcode.NOP)); }
                if(variant==2) code.add(new ImmutableInstruction11n(Opcode.CONST_4,3,0));
                if(variant==3) { code.add(new ImmutableInstruction21t(Opcode.IF_EQZ,4,3));code.add(new ImmutableInstruction11n(Opcode.CONST_4,3,0)); }
                code.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,4,0,1,2,3,0,new ImmutableMethodReference("LX/NewRenderer;","draw",List.of("Ljava/lang/String;","LX/NewState;","Ljava/lang/String;"),"V")));
                code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
                methods.add(new ImmutableMethod(owner,"frame"+n,List.of(new ImmutableMethodParameter(frame,Set.of(),null)),"V",1,Set.of(),Set.of(),new ImmutableMethodImplementation(8,code,List.of(),List.of())));
            }
            ClassDef state=new ImmutableClassDef("LX/NewState;",0x4001,"Ljava/lang/Enum;",List.of(),null,Set.of(),List.of(
                new ImmutableField("LX/NewState;",variant==4?"OTHER":"EXPANDED","LX/NewState;",0x4019,null,Set.of(),Set.of()),
                new ImmutableField("LX/NewState;","NEW_CONSTANT","LX/NewState;",0x4019,null,Set.of(),Set.of())),List.of());
            var definitions=Map.of(assem,type(owner,methods),"X.NewState",state);
            Properties result=new Properties();ExtensionSemanticContracts.resolve(definitions,result);
            assertEquals("variant="+variant,variant==0||variant==5,result.containsKey("class.X.0B14"));
            if(variant==0) {
                assertEquals("X.NewFrame0",result.getProperty("class.X.0B14"));
                assertEquals("text",result.getProperty("X.0B14:field.LIZ"));
                assertEquals("state",result.getProperty("X.0B14:field.LIZIZ"));
                assertEquals("X.NewRenderer",result.getProperty("class.X.0B1g"));
                assertTrue(result.containsKey(assem+":method.bs(LX/0B14;)V"));
                assertTrue(ExtensionSemanticContracts.captionDependencies(definitions).containsAll(List.of("X.NewFrame0","X.NewState","X.NewRenderer")));
            }
        }
    }

}
