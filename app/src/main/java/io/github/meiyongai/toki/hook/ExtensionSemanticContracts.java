package io.github.meiyongai.toki.subtitle.hook;

import java.util.*;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;

/** Relationship contracts for UI methods whose bodies evolve between host releases. */
final class ExtensionSemanticContracts {
    private ExtensionSemanticContracts() {}
    private static final String POLL = "Lcom/ss/android/ugc/aweme/comment/model/PollInfo;";
    private static final String CELL = "com.ss.android.ugc.aweme.commentv2.commentlist.powercell.PollCell";
    private static final String AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;";
    private static List<Instruction> code(Method method) {
        List<Instruction> result=new ArrayList<>();
        if (method.getImplementation()!=null) method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }
    private static List<MethodReference> calls(Method method) {
        List<MethodReference> result=new ArrayList<>();
        for (var instruction:code(method)) if (instruction instanceof ReferenceInstruction r && r.getReference() instanceof MethodReference m) result.add(m);
        return result;
    }
    private static String key(MethodReference method) { return method.getName()+ExtensionDexIndex.signature(method); }
    private static void accept(Properties result, String role, String value) {
        String previous=result.getProperty(role);
        if (previous!=null && !previous.equals(value)) {
            result.remove(role);
            result.setProperty("error."+role,"structural and relationship contracts disagree");
        } else {
            result.setProperty(role,value);
            result.remove("error."+role);
        }
    }
    static void resolve(Map<String,ClassDef> definitions, Properties result) {
        ClassDef cell=definitions.get(result.getProperty("class."+CELL,""));
        if (cell!=null) poll(cell,result);
        ClassDef renderer=definitions.get(result.getProperty("class.X.09Gd",""));
        if (renderer!=null) description(renderer,result);
        captions(definitions,result);
        menu(definitions,result);
        titleEligibility(definitions,result);
        captionService(definitions,result);
        translationLanguage(definitions,result);
        ClassDef aweme=definitions.get("com.ss.android.ugc.aweme.feed.model.Aweme");
        if (aweme!=null) for (Method method:aweme.getMethods()) if (method.getName().equals("getVideo") &&
                ExtensionDexIndex.signature(method).equals("()Lcom/ss/android/ugc/aweme/feed/model/Video;")) {
            boolean field=false;
            for (var instruction:code(method)) if (instruction instanceof ReferenceInstruction r && r.getReference() instanceof FieldReference f &&
                instruction.getOpcode()==Opcode.IGET_OBJECT && f.getDefiningClass().equals(aweme.getType()) && (f.getName().equals("video") || f.getName().equals("_video")) &&
                f.getType().equals(method.getReturnType())) field=true;
            if (field) accept(result,"com.ss.android.ugc.aweme.feed.model.Aweme:method.getVideo()Lcom/ss/android/ugc/aweme/feed/model/Video;",key(method));
        }
    }
    private static void translationLanguage(Map<String,ClassDef> definitions, Properties result) {
        ClassDef service=definitions.get("com.ss.android.ugc.aweme.translation.service.TranslationServiceImpl");
        String facade=result.getProperty("class.X.03hV"),request=result.getProperty("X.03hV:method.LJIIJ(Ljava/lang/String;)LX/02tQ;");
        if(service==null || facade==null || request==null) return;
        String facadeType="L"+facade.replace('.','/')+";";
        Set<String> targets=new HashSet<>();
        for(Method method:service.getMethods()) if(key(method).equals(request)) {
            for(var call:calls(method)) if(call.getDefiningClass().equals(facadeType) && ExtensionDexIndex.signature(call).equals("()Ljava/lang/String;"))
                for(Method target:service.getMethods()) if(key(target).equals(key(call)) &&
                    calls(target,"Landroid/text/TextUtils;","isEmpty","(Ljava/lang/CharSequence;)Z")) {
                    boolean language=false;
                    for(var source:calls(target)) if(source.getDefiningClass().equals("Lcom/ss/android/ugc/aweme/translation/service/ITranslationLangKevaService;") &&
                        ExtensionDexIndex.signature(source).equals("()Ljava/lang/String;")) language=true;
                    if(language) targets.add(key(target));
                }
        }
        if(targets.size()==1) accept(result,"X.03hV:method.LLILLIZIL()Ljava/lang/String;",targets.iterator().next());
    }
    private static void captionService(Map<String,ClassDef> definitions, Properties result) {
        String name="com.ss.android.ugc.aweme.service.CaptionServiceImpl";
        ClassDef service=definitions.get(result.getProperty("class."+name,""));
        String selection=result.getProperty("class.X.08ZQ");
        if(service==null || selection==null) return;
        String selectionType="L"+selection.replace('.','/')+";";
        List<Method> matches=new ArrayList<>();
        for(Method method:service.getMethods()) if((method.getAccessFlags()&8)==0 &&
            ExtensionDexIndex.signature(method).equals("("+AWEME+")"+selectionType) &&
            calls(method,AWEME,"getVideo","()Lcom/ss/android/ugc/aweme/feed/model/Video;") &&
            calls(method,"Lcom/ss/android/ugc/aweme/feed/model/Video;","getCaptionModel","()Lcom/ss/android/ugc/aweme/feed/model/CaptionModel;")) {
            boolean tracks=false;
            for(var instruction:code(method)) if(instruction.getOpcode()==Opcode.IGET_OBJECT &&
                instruction instanceof ReferenceInstruction r && r.getReference() instanceof FieldReference f &&
                f.getDefiningClass().equals("Lcom/ss/android/ugc/aweme/feed/model/CaptionModel;") &&
                f.getName().equals("captionList") && f.getType().equals("Ljava/util/List;")) tracks=true;
            if(tracks) matches.add(method);
        }
        if(matches.size()==1) accept(result,name+":method.LJIIIZ("+AWEME+")LX/08ZQ;",key(matches.get(0)));
    }
    private static boolean calls(Method method, String owner, String name, String signature) {
        return calls(method).stream().anyMatch(it -> it.getDefiningClass().equals(owner) &&
            it.getName().equals(name) && ExtensionDexIndex.signature(it).equals(signature));
    }
    private static void titleEligibility(Map<String,ClassDef> definitions, Properties result) {
        String owner="com.ss.android.ugc.aweme.translation.service.CLATranslatabilityServiceImpl";
        ClassDef service=definitions.get(result.getProperty("class."+owner,""));
        if (service==null) return;
        for (boolean photo:List.of(false,true)) {
            List<Method> gates=new ArrayList<>();
            for (Method method:service.getMethods()) if ((method.getAccessFlags()&8)==0 &&
                ExtensionDexIndex.signature(method).equals("("+AWEME+")Z") &&
                calls(method,AWEME,photo?"isPhotoTitleTranslatable":"isDescTranslatable","()Z") &&
                calls(method,"Landroid/text/TextUtils;","isEmpty","(Ljava/lang/CharSequence;)Z") &&
                calls(method,"Landroid/text/TextUtils;","equals","(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z")) gates.add(method);
            if (gates.size()!=1) continue;
            Method gate=gates.get(0);
            accept(result,owner+":method."+(photo?"LIZLLL":"LJ")+"("+AWEME+")Z",key(gate));
            List<Method> automatic=new ArrayList<>();
            for (Method method:service.getMethods()) if ((method.getAccessFlags()&8)==0 &&
                ExtensionDexIndex.signature(method).equals("("+AWEME+"Ljava/lang/Boolean;)Z") &&
                calls(method,service.getType(),gate.getName(),ExtensionDexIndex.signature(gate)) &&
                calls(method,AWEME,"getAid","()Ljava/lang/String;") &&
                calls(method,"Ljava/lang/Boolean;","booleanValue","()Z")) {
                boolean override=false;
                for (var instruction:code(method)) if (instruction.getOpcode()==Opcode.SGET_OBJECT &&
                    instruction instanceof ReferenceInstruction r && r.getReference() instanceof FieldReference f &&
                    f.getName().equals("OVERRIDE_TO_TRANSLATED") && f.getType().equals(f.getDefiningClass())) override=true;
                if (override) automatic.add(method);
            }
            if (automatic.size()==1) accept(result,owner+":method."+(photo?"LIZJ":"LJIILL")+
                "("+AWEME+"Ljava/lang/Boolean;)Z",key(automatic.get(0)));
        }
    }
    private static void captions(Map<String,ClassDef> definitions, Properties result) {
        String owner="com.ss.android.ugc.aweme.autocaption.refactor.CLACaptionAssemV2";
        ClassDef assem=definitions.get(owner);
        String frame=result.getProperty("class.X.0B14"), renderer=result.getProperty("class.X.0B1g");
        String renderRole="X.0B1g:method.LJIIIZ(Ljava/lang/String;LX/0B17;Ljava/lang/String;LX/0B18;)V";
        String render=result.getProperty(renderRole);
        String rendererField=result.getProperty(owner+":field.LLLLLILLIL");
        String textField=result.getProperty("X.0B14:field.LIZ"),stateField=result.getProperty("X.0B14:field.LIZIZ");
        if (assem==null || frame==null || renderer==null || rendererField==null || textField==null || stateField==null) return;
        String frameType="L"+frame.replace('.','/')+";",rendererType="L"+renderer.replace('.','/')+";";
        if (render==null) {
            String state=result.getProperty("class.X.0B17"),mode=result.getProperty("class.X.0B18");
            ClassDef renderClass=definitions.get(renderer);
            if(state==null || mode==null || renderClass==null) return;
            String signature="(Ljava/lang/String;L"+state.replace('.','/')+";Ljava/lang/String;L"+mode.replace('.','/')+";)V";
            Set<String> renderCalls=new HashSet<>();
            for(Method method:assem.getMethods()) if(ExtensionDexIndex.signature(method).equals("("+frameType+")V")) {
                Set<String> reads=new HashSet<>();
                for(var instruction:code(method)) if(instruction.getOpcode()==Opcode.IGET_OBJECT &&
                    instruction instanceof ReferenceInstruction r && r.getReference() instanceof FieldReference f)
                    reads.add(f.getDefiningClass()+":"+f.getName());
                if(!reads.contains(frameType+":"+textField) || !reads.contains(frameType+":"+stateField) ||
                    !reads.contains(assem.getType()+":"+rendererField)) continue;
                for(var called:calls(method)) if(called.getDefiningClass().equals(rendererType) && ExtensionDexIndex.signature(called).equals(signature))
                    for(Method target:renderClass.getMethods()) if((target.getAccessFlags()&8)==0 && key(target).equals(key(called))) renderCalls.add(key(target));
            }
            if(renderCalls.size()!=1) return;
            render=renderCalls.iterator().next();
            accept(result,renderRole,render);
        }
        List<Method> matches=new ArrayList<>();
        for (Method method:assem.getMethods()) if (ExtensionDexIndex.signature(method).equals("("+frameType+")V") && (method.getAccessFlags()&8)==0) {
            Set<String> reads=new HashSet<>(); boolean calls=false;
            for (var instruction:code(method)) if (instruction instanceof ReferenceInstruction r) {
                if (r.getReference() instanceof MethodReference m && m.getDefiningClass().equals(rendererType) && key(m).equals(render)) calls=true;
                if (instruction.getOpcode()==Opcode.IGET_OBJECT && r.getReference() instanceof FieldReference f)
                    reads.add(f.getDefiningClass()+":"+f.getName());
            }
            if (calls && reads.contains(frameType+":"+textField) && reads.contains(frameType+":"+stateField) && reads.contains(assem.getType()+":"+rendererField)) matches.add(method);
        }
        if (matches.size()==1) accept(result,owner+":method.bs(LX/0B14;)V",key(matches.get(0)));
    }
    private static void menu(Map<String,ClassDef> definitions, Properties result) {
        ClassDef factory=definitions.get(result.getProperty("class.X.0oWb",""));
        String panel=result.getProperty("class.X.0oQw");
        if (factory==null || panel==null) return;
        List<Method> matches=new ArrayList<>();
        for (Method method:factory.getMethods()) if (ExtensionDexIndex.signature(method).equals("(L"+panel.replace('.','/')+";)Ljava/util/List;")) {
            Set<String> labels=new HashSet<>(); boolean adds=false;
            for (var instruction:code(method)) if (instruction instanceof ReferenceInstruction r) {
                if (r.getReference() instanceof StringReference s) labels.add(s.getString());
                if (r.getReference() instanceof MethodReference m && m.getDefiningClass().equals("Ljava/util/ArrayList;") && m.getName().equals("add")) adds=true;
            }
            if (adds && labels.contains("why_this_video") && labels.contains("homepage_podcast")) matches.add(method);
        }
        if (matches.size()==1) accept(result,"X.0oWb:method.LIZIZ(LX/0oQw;)Ljava/util/List;",key(matches.get(0)));
    }
    private static void description(ClassDef renderer, Properties result) {
        // The independently mapped current-video field anchors the binding method.
        String field=result.getProperty("X.09Gd:field.LLLZIIL");
        if (field==null) return;
        List<Method> matches=new ArrayList<>();
        for (Method method:renderer.getMethods()) if (ExtensionDexIndex.signature(method).equals("("+AWEME+")V") && (method.getAccessFlags() & 8)==0) {
            boolean writes=false;
            for (var instruction:code(method)) if (instruction.getOpcode()==Opcode.IPUT_OBJECT && instruction instanceof ReferenceInstruction r &&
                    r.getReference() instanceof FieldReference f && f.getDefiningClass().equals(renderer.getType()) &&
                    f.getName().equals(field) && f.getType().equals(AWEME)) writes=true;
            if (writes) matches.add(method);
        }
        if (matches.size()==1) accept(result,"X.09Gd:method.LLFZ("+AWEME+")V",key(matches.get(0)));
    }
    private static Set<MethodReference> orderCalls(Method method, String cell) {
        Set<MethodReference> result=new HashSet<>();
        for (var instruction:code(method)) if ((instruction.getOpcode()==Opcode.INVOKE_STATIC || instruction.getOpcode()==Opcode.INVOKE_STATIC_RANGE) &&
                instruction instanceof ReferenceInstruction r && r.getReference() instanceof MethodReference m &&
                !m.getDefiningClass().equals(cell) && ExtensionDexIndex.signature(m).equals("("+POLL+")Ljava/util/List;")) result.add(m);
        return result;
    }
    private static boolean labels(Method method) {
        boolean label=false,text=false;
        for (var instruction:code(method)) if (instruction instanceof ReferenceInstruction r) {
            if (r.getReference() instanceof MethodReference m && m.getName().equals("setOptionLabel") && ExtensionDexIndex.signature(m).equals("(Ljava/lang/String;)V")) label=true;
            if (r.getReference() instanceof FieldReference f && f.getDefiningClass().equals("Lcom/ss/android/ugc/aweme/comment/model/OptionCount;") && f.getName().equals("text") && f.getType().equals("Ljava/lang/String;")) text=true;
        }
        return label && text;
    }
    private static Set<String> listFields(Method method, String owner) {
        Set<String> fields=new HashSet<>();
        for (var instruction:code(method)) if (instruction.getOpcode()==Opcode.IGET_OBJECT && instruction instanceof ReferenceInstruction r &&
                r.getReference() instanceof FieldReference f && f.getDefiningClass().equals(owner) && f.getType().equals("Ljava/util/List;")) fields.add(f.getName());
        return fields;
    }
    private static Set<String> titles(Method method, String owner) {
        Set<String> result=new HashSet<>();
        var instructions=code(method);
        for (int i=0;i<instructions.size();i++) {
            var instruction=instructions.get(i);
            if (!(instruction instanceof ReferenceInstruction r) || !(r.getReference() instanceof FieldReference f) ||
                    !f.getDefiningClass().equals(POLL) || !f.getName().equals("title") || !f.getType().equals("Ljava/lang/String;")) continue;
            // Require title String and the immediately preceding TextView read to feed setText.
            if (!(instruction instanceof OneRegisterInstruction title)) continue;
            for (int j=i-1;j>=Math.max(0,i-4);j--) {
                var prior=instructions.get(j);
                if (!(prior instanceof ReferenceInstruction p) || !(p.getReference() instanceof FieldReference view) ||
                        !view.getDefiningClass().equals(owner) || !(prior instanceof OneRegisterInstruction target) ||
                        !(view.getType().equals("Lcom/bytedance/tux/input/TuxTextView;") || view.getType().equals("Landroid/widget/TextView;"))) continue;
                for (int k=i+1;k<Math.min(instructions.size(),i+6);k++) {
                    var next=instructions.get(k);
                    if (next instanceof ReferenceInstruction c && c.getReference() instanceof MethodReference m &&
                            m.getName().equals("setText") && ExtensionDexIndex.signature(m).equals("(Ljava/lang/CharSequence;)V") &&
                            next instanceof FiveRegisterInstruction args && args.getRegisterC()==target.getRegisterA() && args.getRegisterD()==title.getRegisterA()) result.add(view.getName());
                }
            }
        }
        return result;
    }
    private static void poll(ClassDef cell, Properties result) {
        List<Method> bound=new ArrayList<>(), updates=new ArrayList<>();
        for (Method bridge:cell.getMethods()) if (bridge.getName().equals("onBindItemView") && bridge.getParameterTypes().size()==1 &&
                bridge.getReturnType().equals("V") && (bridge.getAccessFlags() & 0x40)!=0) {
            for (var call:calls(bridge)) if (call.getDefiningClass().equals(cell.getType()) && call.getParameterTypes().size()==1 && call.getReturnType().equals("V")) {
                for (Method target:cell.getMethods()) if (key(target).equals(key(call)) && labels(target)) bound.add(target);
            }
        }
        for (Method method:cell.getMethods()) if (ExtensionDexIndex.signature(method).equals("("+POLL+")V") &&
                (method.getAccessFlags() & 8)==0 && labels(method)) updates.add(method);
        if (bound.size()!=1 || updates.size()!=1) return;
        Method bind=bound.get(0),update=updates.get(0);
        var ordered=orderCalls(bind,cell.getType()); ordered.retainAll(orderCalls(update,cell.getType()));
        var lists=listFields(update,cell.getType()); lists.retainAll(listFields(bind,cell.getType()));
        var titles=titles(bind,cell.getType());
        if (ordered.size()!=1 || lists.size()!=1 || titles.size()!=1) return;
        String item=bind.getParameterTypes().get(0).toString();
        String expected=result.getProperty("class.X.0Gxr");
        if (expected==null || !item.equals("L"+expected.replace('.','/')+";")) return;
        var order=ordered.iterator().next();
        String helper=order.getDefiningClass();
        accept(result,"class.X.0QGy",helper.substring(1,helper.length()-1).replace('/','.'));
        accept(result,"X.0QGy:method.LIZ("+POLL+")Ljava/util/List;",key(order));
        accept(result,CELL+":method.s6(LX/0Gxr;)V",key(bind));
        accept(result,CELL+":method.X5("+POLL+")V",key(update));
        accept(result,CELL+":field.LLJJJIL",titles.iterator().next());
        accept(result,CELL+":field.LLJJJJJIL",lists.iterator().next());
    }
}
