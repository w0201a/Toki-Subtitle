package io.github.meiyongai.toki.subtitle.hook;

import java.io.IOException;
import java.util.*;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;

/** Local comment gate discovery through the preference supplier and lazy value graph. */
final class CommentGateIndex {
    private CommentGateIndex() {}
    static List<MethodReference> calls(Method method) {
        List<MethodReference> result=new ArrayList<>();
        if(method.getImplementation()!=null) for(var instruction:method.getImplementation().getInstructions())
            if(instruction instanceof ReferenceInstruction r && r.getReference() instanceof MethodReference m) result.add(m);
        return result;
    }
    static boolean supplier(ClassDef type) {
        for(Method method:type.getMethods()) if(method.getName().equals("invoke") &&
            ExtensionDexIndex.signature(method).equals("()Ljava/lang/Object;") && method.getImplementation()!=null) {
            boolean preference=false,boxed=false;
            for(var instruction:method.getImplementation().getInstructions()) if(instruction instanceof ReferenceInstruction r &&
                r.getReference() instanceof StringReference s && s.getString().equals("comment_auto_translation_enabled")) preference=true;
            for(var call:calls(method)) if(call.getDefiningClass().equals("Ljava/lang/Boolean;") && call.getName().equals("valueOf") &&
                ExtensionDexIndex.signature(call).equals("(Z)Ljava/lang/Boolean;")) boxed=true;
            if(preference && boxed) return true;
        }
        return false;
    }
    static List<String> contract(ClassDef type,Set<String> suppliers) {
        Set<String> lazyFields=new HashSet<>();
        for(Method method:type.getMethods()) if(method.getName().equals("<clinit>") && method.getImplementation()!=null) {
            boolean creates=false,lazy=false;
            for(var instruction:method.getImplementation().getInstructions()) if(instruction instanceof ReferenceInstruction r) {
                if(instruction.getOpcode()==Opcode.NEW_INSTANCE && r.getReference() instanceof TypeReference t && suppliers.contains(t.getType())) creates=true;
                if(r.getReference() instanceof MethodReference m && m.getParameterTypes().size()==1 &&
                    m.getParameterTypes().get(0).toString().equals("Lkotlin/jvm/functions/Function0;")) lazy=true;
                if(instruction.getOpcode()==Opcode.SPUT_OBJECT && r.getReference() instanceof FieldReference f &&
                    f.getDefiningClass().equals(type.getType()) && creates && lazy) lazyFields.add(f.getName());
            }
        }
        if(lazyFields.size()!=1) return List.of();
        List<Method> zero=new ArrayList<>(),one=new ArrayList<>();
        for(Method method:type.getMethods()) if((method.getAccessFlags()&9)==9 && method.getReturnType().equals("Z")) {
            if(method.getImplementation()==null) continue;
            if(ExtensionDexIndex.signature(method).equals("()Z")) zero.add(method);
            if(ExtensionDexIndex.signature(method).equals("(Z)Z")) one.add(method);
        }
        if(zero.size()!=2 || one.size()!=1) return List.of();
        List<Method> readers=new ArrayList<>();
        for(Method method:zero) {
            boolean field=false,value=false,unbox=false;
            for(var instruction:method.getImplementation().getInstructions()) if(instruction instanceof ReferenceInstruction r &&
                r.getReference() instanceof FieldReference f && f.getDefiningClass().equals(type.getType()) && lazyFields.contains(f.getName())) field=true;
            for(var call:calls(method)) {
                if(call.getName().equals("getValue") && ExtensionDexIndex.signature(call).equals("()Ljava/lang/Object;")) value=true;
                if(call.getDefiningClass().equals("Ljava/lang/Boolean;") && call.getName().equals("booleanValue") && ExtensionDexIndex.signature(call).equals("()Z")) unbox=true;
            }
            if(field&&value&&unbox) readers.add(method);
        }
        if(readers.size()!=1) return List.of();
        Method reader=readers.get(0);
        List<Method> all=new ArrayList<>(zero);all.addAll(one);
        for(Method method:all) if(method!=reader && calls(method).stream().noneMatch(call->call.getDefiningClass().equals(type.getType()) &&
            call.getName().equals(reader.getName()) && ExtensionDexIndex.signature(call).equals("()Z"))) return List.of();
        return all.stream().map(method->method.getName()+ExtensionDexIndex.signature(method)).sorted().collect(java.util.stream.Collectors.toList());
    }
    static void resolve(List<String> paths,Properties result) throws IOException {
        Set<String> suppliers=new HashSet<>();
        ExtensionDexIndex.visit(paths,type->{if(supplier(type)) suppliers.add(type.getType());});
        if(suppliers.isEmpty()) { result.setProperty("error.commentGate","preference supplier unavailable");return; }
        Map<String,List<String>> matches=new HashMap<>();
        ExtensionDexIndex.visit(paths,type->{List<String> methods=contract(type,suppliers);if(!methods.isEmpty()) matches.put(type.getType(),methods);});
        if(matches.size()!=1) { result.setProperty("error.commentGate","relationship candidates="+matches.size());return; }
        var match=matches.entrySet().iterator().next();
        result.setProperty("commentGate.class",match.getKey().substring(1,match.getKey().length()-1).replace('/','.'));
        result.setProperty("commentGate.methods",String.join("\n",match.getValue()));
    }
}
