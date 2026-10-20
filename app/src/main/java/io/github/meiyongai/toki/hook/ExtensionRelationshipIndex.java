package io.github.meiyongai.toki.subtitle.hook;

import java.io.IOException;
import java.util.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;

/** On-device secondary evidence: ordered references survive register allocation and branch offsets. */
final class ExtensionRelationshipIndex {
    private ExtensionRelationshipIndex() {}
    private static final java.util.regex.Pattern OBFUSCATED = java.util.regex.Pattern.compile("L[A-Z0-9_]+|[a-zA-Z]{1,2}[0-9]*");
    private static String name(String type) { return type.substring(1,type.length()-1).replace('/','.'); }
    private static String role(MethodReference m) { return name(m.getDefiningClass())+":method."+m.getName()+ExtensionDexIndex.signature(m); }
    private static String member(String owner,String name) {
        return owner.startsWith("LX/") || OBFUSCATED.matcher(name).matches() ? "*" : name;
    }
    static String evidence(Method method) {
        if(method.getImplementation()==null) return null;
        List<String> parts=new ArrayList<>(); int anchors=0;
        for(var instruction:method.getImplementation().getInstructions()) if(instruction instanceof ReferenceInstruction r) {
            var ref=r.getReference(); String value=null;
            if(ref instanceof MethodReference m && (m.getDefiningClass().equals("Lcom/bytedance/pumbaa/utility/method_id/MethodIDManager;") ||
                m.getDefiningClass().equals("Lcom/bytedance/covode/number/Covode;"))) continue;
            if(ref instanceof StringReference s) {
                value="s:"+s.getString(); if(s.getString().length()>=4) anchors++;
            } else if(ref instanceof MethodReference m) {
                value="m:"+ExtensionDexIndex.normalized(m.getDefiningClass())+"->"+member(m.getDefiningClass(),m.getName())+ExtensionDexIndex.normalized(ExtensionDexIndex.signature(m));
                if(!m.getDefiningClass().startsWith("LX/") && !m.getDefiningClass().equals("Lkotlin/jvm/internal/Intrinsics;")) anchors++;
            } else if(ref instanceof FieldReference f) {
                value="f:"+ExtensionDexIndex.normalized(f.getDefiningClass())+"->"+member(f.getDefiningClass(),f.getName())+":"+ExtensionDexIndex.normalized(f.getType());
                if(!f.getDefiningClass().startsWith("LX/") || (method.getName().equals("<init>") && f.getDefiningClass().equals(method.getDefiningClass()))) anchors++;
            } else if(ref instanceof TypeReference t) value="t:"+ExtensionDexIndex.normalized(t.getType());
            if(value!=null) parts.add(instruction.getOpcode().toString().replace("_RANGE","")+":"+value);
        }
        if(anchors<3 || parts.size()<4) return null;
        return ExtensionDexIndex.digest(ExtensionDexIndex.normalized(ExtensionDexIndex.signature(method))+"|"+(method.getAccessFlags()&0x9)+"|"+String.join("\n",parts));
    }
    static void profile(List<String> paths,Properties profile) throws IOException {
        ExtensionDexIndex.visit(paths,type->{
            String owner=name(type.getType()); if(!profile.containsKey("class."+owner)) return;
            for(Method method:type.getMethods()) {
                String hash=evidence(method); if(hash==null) continue;
                profile.setProperty("relationship."+role(method),hash);
                int ordinal=0;
                for(var instruction:method.getImplementation().getInstructions()) if(instruction instanceof ReferenceInstruction r && r.getReference() instanceof FieldReference f) {
                    if(f.getDefiningClass().equals(type.getType()))
                        profile.setProperty("relationshipField."+role(method)+"@"+ordinal,owner+":field."+f.getName());
                    ordinal++;
                }
            }
        });
    }
    private static void accept(Properties result,String key,String value) {
        String existing=result.getProperty(key);
        if(existing!=null && !existing.equals(value)) {
            result.remove(key);result.setProperty("error."+key,"independent relationship evidence disagrees");
        } else { result.setProperty(key,value);result.remove("error."+key); }
    }
    private record PreparedRelationships(String actual,String hierarchy,Map<String,Map<String,List<Method>>> matches) {}
    static void resolve(List<String> paths,Properties profile,Properties result,Map<String,ClassDef> definitions) throws IOException {
        Map<String,List<String>> byHash=new HashMap<>();
        Set<String> signatures=new HashSet<>(),namedOwners=new HashSet<>(),hierarchies=new HashSet<>();
        for(String key:profile.stringPropertyNames()) if(key.startsWith("relationship."))
            byHash.computeIfAbsent(profile.getProperty(key),unused->new ArrayList<>()).add(key.substring(13));
        if(byHash.isEmpty()) return;
        for(var roles:byHash.values()) for(String role:roles) {
            signatures.add(ExtensionDexIndex.normalized(role.substring(role.indexOf('('))));
            String owner=role.substring(0,role.indexOf(':'));
            if(!owner.startsWith("X.")) namedOwners.add(owner);
            String hierarchy=profile.getProperty("hierarchy."+owner);if(hierarchy!=null) hierarchies.add(hierarchy);
        }
        Map<String,Map<String,Map<String,List<Method>>>> candidates=new HashMap<>();
        Map<String,String> candidateHierarchies=new HashMap<>();
        ExtensionDexIndex.visitPrepared(paths,type->{
            if(!type.getType().startsWith("LX/") && !namedOwners.contains(name(type.getType()))) return null;
            String hierarchy=ExtensionDexIndex.relationshipHierarchy(type);
            if(!hierarchies.contains(hierarchy)) return null;
            Map<String,List<Method>> hashes=new HashMap<>();
            for(Method method:type.getMethods()) {
                if(!signatures.contains(ExtensionDexIndex.normalized(ExtensionDexIndex.signature(method)))) continue;
                String hash=evidence(method);
                if(hash!=null && byHash.containsKey(hash)) hashes.computeIfAbsent(hash,unused->new ArrayList<>()).add(org.jf.dexlib2.immutable.ImmutableMethod.of(method));
            }
            String actual=name(type.getType());
            Map<String,Map<String,List<Method>>> matches=new HashMap<>();
            for(var entry:hashes.entrySet()) for(String role:byHash.get(entry.getKey())) {
                String expected=role.substring(0,role.indexOf(':'));
                if(!expected.startsWith("X.") && !expected.equals(actual)) continue;
                matches.computeIfAbsent(expected,unused->new HashMap<>()).put(role,entry.getValue());
            }
            return matches.isEmpty()?null:new PreparedRelationships(actual,hierarchy,matches);
        },prepared->{
            if(prepared==null) return;
            for(var match:prepared.matches().entrySet()) candidates.computeIfAbsent(match.getKey(),unused->new HashMap<>()).put(prepared.actual(),match.getValue());
            candidateHierarchies.put(prepared.actual(),prepared.hierarchy());
        });
        for(var group:candidates.entrySet()) {
            String expected=group.getKey(),existing=result.getProperty("class."+expected);
            List<String> owners=new ArrayList<>();
            for(var candidate:group.getValue().entrySet()) {
                String hierarchy=candidateHierarchies.get(candidate.getKey());
                long unique=candidate.getValue().values().stream().filter(methods->methods.size()==1).count();
                // Require two independent methods for an unknown obfuscated class. Named
                // anchors and previously verified classes still verify each member separately.
                boolean uniqueAnchor=unique==1 && group.getValue().size()==1 && candidate.getValue().keySet().stream().anyMatch(key->key.contains(":method.toString()"));
                if((candidate.getKey().equals(existing) || expected.equals(candidate.getKey()) || unique>=2 || uniqueAnchor) &&
                    Objects.equals(hierarchy,profile.getProperty("hierarchy."+expected))) owners.add(candidate.getKey());
            }
            if(owners.size()!=1) continue;
            String actual=owners.get(0); accept(result,"class."+expected,actual);
            if(!actual.equals(result.getProperty("class."+expected))) continue;
            var methods=group.getValue().get(actual);
            Map<String,Set<String>> fields=new HashMap<>();
            for(var entry:methods.entrySet()) if(entry.getValue().size()==1) {
                Method method=entry.getValue().get(0);String methodRole=entry.getKey();
                accept(result,methodRole,method.getName()+ExtensionDexIndex.signature(method));
                int ordinal=0;
                for(var instruction:method.getImplementation().getInstructions()) if(instruction instanceof ReferenceInstruction r && r.getReference() instanceof FieldReference f) {
                    String fieldRole=profile.getProperty("relationshipField."+methodRole+"@"+ordinal++);
                    if(fieldRole!=null && f.getDefiningClass().equals("L"+actual.replace('.','/')+";"))
                        fields.computeIfAbsent(fieldRole,unused->new HashSet<>()).add(f.getName());
                }
            }
            for(var field:fields.entrySet()) if(field.getValue().size()==1) accept(result,field.getKey(),field.getValue().iterator().next());
        }
        // Retain only selected classes; candidate methods are detached from DEX buffers.
        Set<String> accepted=new HashSet<>();for(String property:result.stringPropertyNames()) if(property.startsWith("class.")) accepted.add(result.getProperty(property));
        accepted.removeAll(definitions.keySet());
        candidates.clear();
        if(!accepted.isEmpty()) ExtensionDexIndex.visit(paths,type->{String actual=name(type.getType());if(accepted.contains(actual)) definitions.put(actual,org.jf.dexlib2.immutable.ImmutableClassDef.of(type));});
        candidates.clear();
        inferRelatedTypes(paths,profile,result,definitions);
        typedMembers(profile,result,definitions);
    }
    private static String mapped(String descriptor,Properties profile,Properties result) {
        String value=descriptor;
        for(String type:types(descriptor)) if(profile.containsKey("class."+type)) {
            String actual=result.getProperty("class."+type);if(actual==null) return null;
            value=value.replace("L"+type.replace('.','/')+";","L"+actual.replace('.','/')+";");
        }
        return value;
    }
    static void completeMembers(List<String> paths,Properties profile,Properties result,Map<String,ClassDef> definitions) throws IOException {
        Set<String> wanted=new HashSet<>();for(String key:result.stringPropertyNames()) if(key.startsWith("class.")) wanted.add(result.getProperty(key));
        wanted.addAll(ExtensionSemanticContracts.captionDependencies(definitions));
        wanted.removeAll(definitions.keySet());
        if(!wanted.isEmpty()) ExtensionDexIndex.visit(paths,type->{String actual=name(type.getType());if(wanted.contains(actual)) definitions.computeIfAbsent(actual,unused->org.jf.dexlib2.immutable.ImmutableClassDef.of(type));});
        Map<String,Map<String,List<Method>>> indexed=new HashMap<>();
        for(String key:profile.stringPropertyNames()) if(key.startsWith("relationship.")) {
            String role=key.substring(13),owner=role.substring(0,role.indexOf(':'));
            ClassDef type=definitions.get(result.getProperty("class."+owner,""));if(type==null) continue;
            Map<String,List<Method>> hashes=indexed.computeIfAbsent(type.getType(),unused->{
                Map<String,List<Method>> values=new HashMap<>();List<Method> methods=new ArrayList<>();type.getMethods().forEach(methods::add);
                List<String> hashesForClass=ScanWorkers.map(methods,ExtensionRelationshipIndex::evidence);
                for(int number=0;number<methods.size();number++) {
                    String hash=hashesForClass.get(number);if(hash!=null) values.computeIfAbsent(hash,ignored->new ArrayList<>()).add(methods.get(number));
                }return values;
            });
            List<Method> matches=hashes.getOrDefault(profile.getProperty(key),List.of());
            if(matches.size()!=1) continue;
            Method method=matches.get(0);accept(result,role,method.getName()+ExtensionDexIndex.signature(method));
            Map<String,Set<String>> fields=new HashMap<>();int ordinal=0;
            for(var instruction:method.getImplementation().getInstructions()) if(instruction instanceof ReferenceInstruction r && r.getReference() instanceof FieldReference f) {
                String fieldRole=profile.getProperty("relationshipField."+role+"@"+ordinal++);
                if(fieldRole!=null && f.getDefiningClass().equals(type.getType())) fields.computeIfAbsent(fieldRole,unused->new HashSet<>()).add(f.getName());
            }
            for(var field:fields.entrySet()) if(field.getValue().size()==1) accept(result,field.getKey(),field.getValue().iterator().next());
        }
        typedMembers(profile,result,definitions);
    }
    private static void typedMembers(Properties profile,Properties result,Map<String,ClassDef> definitions) {
        for(String key:profile.stringPropertyNames()) {
            if(!key.contains(":") || result.containsKey(key)) continue;
            String owner=key.substring(0,key.indexOf(':'));ClassDef type=definitions.get(result.getProperty("class."+owner,""));
            if(type==null) continue;
            if(key.startsWith(owner+":field.")) {
                String expected=mapped(profile.getProperty("fieldType."+owner+":"+key.substring(key.indexOf(":field.")+7),""),profile,result);
                if(expected==null || expected.isEmpty()) continue;
                List<Field> fields=new ArrayList<>();for(Field field:type.getFields()) if(field.getType().equals(expected)) fields.add(field);
                if(fields.size()==1) accept(result,key,fields.get(0).getName());
            } else if(key.startsWith(owner+":method.")) {
                String oldName=key.substring(key.indexOf(":method.")+8,key.indexOf('('));
                // Descriptive lifecycle names are part of the contract; obfuscated names
                // need separate call/body evidence, even if their signature is unique.
                if(!((oldName.equals("accept") && key.endsWith("(Ljava/lang/Object;)V")) || (oldName.equals("dispose") && key.endsWith("()V")))) continue;
                String signature=mapped(key.substring(key.indexOf('(')),profile,result);if(signature==null) continue;
                List<Method> matches=new ArrayList<>();for(Method method:type.getMethods()) if(method.getName().equals(oldName) && ExtensionDexIndex.signature(method).equals(signature)) matches.add(method);
                if(matches.size()==1) accept(result,key,matches.get(0).getName()+signature);
            }
        }
    }
    private static List<String> types(String signature) {
        List<String> values=new ArrayList<>();var matcher=java.util.regex.Pattern.compile("L[^;]+;").matcher(signature);
        while(matcher.find()) values.add(name(matcher.group()));return values;
    }
    private static void inferRelatedTypes(List<String> paths,Properties profile,Properties result,Map<String,ClassDef> definitions) throws IOException {
        Map<String,Set<String>> hints=new HashMap<>();
        for(String key:result.stringPropertyNames()) if(key.contains(":method.") && !key.startsWith("error.")) {
            List<String> old=types(key.substring(key.indexOf('('))),actual=types(result.getProperty(key));
            if(old.size()!=actual.size()) continue;
            for(int i=0;i<old.size();i++) if(profile.containsKey("class."+old.get(i))) hints.computeIfAbsent(old.get(i),unused->new HashSet<>()).add(actual.get(i));
        }
        Map<String,String> requested=new HashMap<>();
        for(var hint:hints.entrySet()) if(!result.containsKey("class."+hint.getKey()) && hint.getValue().size()==1) requested.put(hint.getValue().iterator().next(),hint.getKey());
        if(requested.isEmpty()) return;
        ExtensionDexIndex.visit(paths,type->{
            String actual=name(type.getType()),expected=requested.get(actual);if(expected==null) return;
            if(!Objects.equals(ExtensionDexIndex.relationshipHierarchy(type),profile.getProperty("hierarchy."+expected))) return;
            accept(result,"class."+expected,actual);definitions.computeIfAbsent(actual,unused->org.jf.dexlib2.immutable.ImmutableClassDef.of(type));
        });
    }
}
