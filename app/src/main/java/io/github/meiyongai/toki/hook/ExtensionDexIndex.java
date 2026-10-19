package io.github.meiyongai.toki.subtitle.hook;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;

/** Strict structural fingerprints for subtitle extensions; names alone never authorize an unknown build. */
public final class ExtensionDexIndex {
    public static final String FORMAT = "extension-structure-v1";
    private ExtensionDexIndex() {}
    public interface Progress { void update(String stage, int completed, int total); }
    private static final ThreadLocal<Progress> OBSERVER = new ThreadLocal<>();
    private static final ThreadLocal<String> PHASE = new ThreadLocal<>();
    private static void phase(String value) { PHASE.set(value); if(OBSERVER.get()!=null) OBSERVER.get().update(value,0,0); }
    public static Properties scan(List<String> paths, Properties profile, Set<String> selected, Progress progress) throws IOException {
        OBSERVER.set(progress);
        try (ScanWorkers.Scope workers=ScanWorkers.open()) { phase("structure"); return scan(paths,profile,selected); }
        finally { OBSERVER.remove(); PHASE.remove(); }
    }

    private static final java.util.regex.Pattern OBFUSCATED_TYPE = java.util.regex.Pattern.compile("LX/[^;]+;");
    private static final java.util.regex.Pattern OBFUSCATED_MEMBER = java.util.regex.Pattern.compile("L[A-Z0-9_]+|[a-zA-Z]{1,2}[0-9]*");
    private static final java.util.regex.Pattern OBJECT_TYPE = java.util.regex.Pattern.compile("L[^;]+;");
    private static final java.util.regex.Pattern DEX_ENTRY = java.util.regex.Pattern.compile("classes(?:[2-9]|[1-9][0-9]+)?\\.dex");
    static String normalized(String value) {
        return value.indexOf("LX/") < 0 ? value : OBFUSCATED_TYPE.matcher(value).replaceAll("LX/*;");
    }
    private static boolean obfuscated(String name) { return OBFUSCATED_MEMBER.matcher(name).matches(); }
    static String signature(MethodReference method) {
        return "(" + String.join("", method.getParameterTypes()) + ")" + method.getReturnType();
    }
    private static List<String> memberMatches(Properties actual, String role, String hash) {
        String kind = role.startsWith("method.") ? "method." : "field.";
        List<String> matches = actual.stringPropertyNames().stream().filter(it ->
            it.startsWith(kind) && actual.getProperty(it).equals(hash)).collect(java.util.stream.Collectors.toList());
        String name = role.substring(kind.length()).split("\\(", 2)[0];
        // Public descriptive member names are additional evidence, especially serialized model fields.
        // Short/obfuscated identifiers never resolve a collision by name alone.
        if (matches.size() > 1 && !obfuscated(name) && matches.contains(role))
            return Collections.singletonList(role);
        return matches;
    }
    private static String member(String owner, String name) {
        return owner.startsWith("LX/") || obfuscated(name) ? "*" : name;
    }
    private static String methodKey(MethodReference method) {
        String owner = method.getDefiningClass();
        return owner.substring(1, owner.length()-1).replace('/', '.') + ":method." + method.getName() + signature(method);
    }
    private static String fieldKey(FieldReference field) {
        String owner = field.getDefiningClass();
        return owner.substring(1, owner.length()-1).replace('/', '.') + ":field." + field.getName();
    }
    private static String reference(Reference ref) {
        if (ref instanceof StringReference s) return "string:" + s.getString();
        if (ref instanceof TypeReference t) return "type:" + normalized(t.getType());
        if (ref instanceof MethodReference m) return "method:" + normalized(m.getDefiningClass()) + "->" +
            member(m.getDefiningClass(), m.getName()) + normalized(signature(m));
        if (ref instanceof FieldReference f) return "field:" + normalized(f.getDefiningClass()) + "->" +
            member(f.getDefiningClass(), f.getName()) + ":" + normalized(f.getType());
        return ref.toString();
    }
    static String digest(String value) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte part : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
                hex.append(Character.forDigit((part >> 4) & 15, 16)).append(Character.forDigit(part & 15, 16));
            return hex.toString();
        }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    /** Ignores obfuscated names and source locations; preserves code flow, registers, references and literals. */
    static String methodFingerprint(Method method) {
        StringBuilder text = new StringBuilder(normalized(signature(method))).append('|').append(method.getAccessFlags());
        if (method.getImplementation() != null) {
            text.append("|registers:").append(method.getImplementation().getRegisterCount());
            for (var block : method.getImplementation().getTryBlocks()) {
                text.append("|try:").append(block.getStartCodeAddress()).append(':').append(block.getCodeUnitCount());
                for (var handler : block.getExceptionHandlers()) text.append(':')
                    .append(normalized(Objects.toString(handler.getExceptionType(), ""))).append('@').append(handler.getHandlerCodeAddress());
            }
        }
        if (method.getImplementation() != null) for (Instruction instruction : method.getImplementation().getInstructions()) {
            text.append('\n').append(instruction.getOpcode());
            if (instruction instanceof OneRegisterInstruction r) text.append("|a:").append(r.getRegisterA());
            if (instruction instanceof TwoRegisterInstruction r) text.append("|b:").append(r.getRegisterB());
            if (instruction instanceof ThreeRegisterInstruction r) text.append("|c:").append(r.getRegisterC());
            if (instruction instanceof FiveRegisterInstruction r) text.append("|args:").append(r.getRegisterCount())
                .append(':').append(r.getRegisterC()).append(':').append(r.getRegisterD()).append(':').append(r.getRegisterE())
                .append(':').append(r.getRegisterF()).append(':').append(r.getRegisterG());
            if (instruction instanceof RegisterRangeInstruction r) text.append("|range:").append(r.getStartRegister()).append(':').append(r.getRegisterCount());
            if (instruction instanceof OffsetInstruction jump) text.append("|offset:").append(jump.getCodeOffset());
            if (instruction instanceof SwitchPayload payload) for (var element : payload.getSwitchElements())
                text.append("|case:").append(element.getKey()).append(':').append(element.getOffset());
            if (instruction instanceof org.jf.dexlib2.iface.instruction.formats.ArrayPayload payload)
                text.append("|array:").append(payload.getElementWidth()).append(':').append(payload.getArrayElements());
            if (instruction instanceof ReferenceInstruction r) text.append('|').append(reference(r.getReference()));
            if (instruction instanceof DualReferenceInstruction r) text.append('|').append(reference(r.getReference2()));
            if (instruction instanceof WideLiteralInstruction literal) {
                long value = literal.getWideLiteral();
                // Android resource IDs are reassigned when the app is rebuilt.
                text.append('|').append(value >= 0x7f000000L && value <= 0x7fffffffL ? "resource-id" : Long.toString(value));
            }
        }
        return digest(text.toString());
    }
    private static String fieldHeader(Field field) {
        StringBuilder value = new StringBuilder(normalized(field.getType())).append('|').append(field.getAccessFlags());
        if (field.getInitialValue() != null) value.append("|initial:").append(normalized(
            org.jf.dexlib2.formatter.DexFormatter.INSTANCE.getEncodedValue(field.getInitialValue())));
        List<String> annotations = new ArrayList<>();
        for (var annotation : field.getAnnotations()) for (var element : annotation.getElements())
            annotations.add(normalized(annotation.getType())+":"+element.getName()+"="+normalized(
                org.jf.dexlib2.formatter.DexFormatter.INSTANCE.getEncodedValue(element.getValue())));
        Collections.sort(annotations);
        return value + "|annotations:" + String.join("|", annotations);
    }
    private record Fingerprint(String shape, Properties members, Map<String,String> fieldTypes, Map<String,String> fieldContracts, Map<String,List<String>> fieldUses) {}
    private static Fingerprint fingerprint(ClassDef type) {
        List<String> shape = new ArrayList<>();
        Properties members = new Properties();
        Map<String,String> fieldTypes = new HashMap<>();
        Map<String,String> fieldContracts = new HashMap<>();
        Map<String,List<String>> uses = new HashMap<>();
        List<Method> methods=new ArrayList<>();type.getMethods().forEach(methods::add);
        List<String> methodHashes=ScanWorkers.map(methods,ExtensionDexIndex::methodFingerprint);
        for (int methodIndex=0;methodIndex<methods.size();methodIndex++) {
            Method method=methods.get(methodIndex);
            String hash = methodHashes.get(methodIndex);
            shape.add("method:" + hash);
            members.setProperty("method." + method.getName() + signature(method), hash);
            if (method.getImplementation() != null) {
                int index = 0;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (instruction instanceof ReferenceInstruction r && r.getReference() instanceof FieldReference f &&
                            f.getDefiningClass().equals(type.getType()))
                        uses.computeIfAbsent(f.getName(), key -> new ArrayList<>()).add(hash + "@" + index);
                    index++;
                }
            }
        }
        for (Field field : type.getFields()) {
            fieldTypes.put(field.getName(), field.getType());
            String header = fieldHeader(field);
            fieldContracts.put(field.getName(), digest(header));
            shape.add("field:" + header);
            List<String> sites = uses.getOrDefault(field.getName(), new ArrayList<>());
            Collections.sort(sites);
            members.setProperty("field." + field.getName(), digest(header + "\n" + String.join("\n", sites)));
        }
        shape.add("super:" + normalized(Objects.toString(type.getSuperclass(), "")));
        for (String it : type.getInterfaces()) shape.add("interface:" + normalized(it));
        shape.add("flags:" + type.getAccessFlags());
        Collections.sort(shape);
        return new Fingerprint(digest(String.join("\n", shape)), members, fieldTypes, fieldContracts, uses);
    }
    private static List<String> methodHeaders(ClassDef type) {
        List<String> values = new ArrayList<>();
        for (var method : type.getMethods()) values.add(normalized(signature(method))+":"+method.getAccessFlags());
        Collections.sort(values);
        return values;
    }
    private static boolean overlaps(Collection<String> left, Collection<String> right, int minimum) {
        Map<String,Integer> counts = new HashMap<>();
        for (String value : right) counts.merge(value, 1, Integer::sum);
        int common = 0;
        for (String value : left) if (counts.getOrDefault(value, 0) > 0) {
            common++; counts.merge(value, -1, Integer::sum);
        }
        return common >= minimum && common * 5 >= Math.max(left.size(), right.size()) * 3;
    }
    private static String counts(ClassDef type) {
        int methods=0, fields=0;
        for (var ignored : type.getMethods()) methods++;
        for (var ignored : type.getFields()) fields++;
        return methods+","+fields+","+type.getInterfaces().size();
    }
    private static String hierarchy(ClassDef type) {
        List<String> values = new ArrayList<>(type.getInterfaces());
        Collections.sort(values);
        return digest(type.getAccessFlags()+"|"+normalized(Objects.toString(type.getSuperclass(), ""))+"|"+normalized(String.join("|", values)));
    }
    static String relationshipHierarchy(ClassDef type) { return hierarchy(type); }
    private static String header(ClassDef type) {
        List<String> parts = new ArrayList<>();
        for (Method method : type.getMethods()) parts.add("m:" + normalized(signature(method)) + ":" + method.getAccessFlags());
        for (Field field : type.getFields()) parts.add("f:" + normalized(field.getType()) + ":" + field.getAccessFlags());
        parts.add("s:" + normalized(Objects.toString(type.getSuperclass(), "")));
        for (String it : type.getInterfaces()) parts.add("i:" + normalized(it));
        Collections.sort(parts);
        return digest(String.join("\n", parts));
    }
    interface Visitor { void visit(ClassDef type); }
    static void visit(List<String> paths, Visitor visitor) throws IOException {
        int total=0,completed=0;
        if(OBSERVER.get()!=null) for(String path:paths) try(ZipFile archive=new ZipFile(path)) {
            for(var entry:Collections.list(archive.entries())) if(DEX_ENTRY.matcher(entry.getName()).matches()) total++;
        }
        for (String path : paths) try (ZipFile archive = new ZipFile(path)) {
            for (var entry : Collections.list(archive.entries())) {
                if (!DEX_ENTRY.matcher(entry.getName()).matches()) continue;
                try (InputStream stream = new BufferedInputStream(archive.getInputStream(entry))) {
                    long size=entry.getSize();
                    if(size<0 || size>Integer.MAX_VALUE) throw new IOException("Invalid DEX size");
                    byte[] data=new byte[(int)size];
                    new DataInputStream(stream).readFully(data);
                    var dex = new DexBackedDexFile(Opcodes.getDefault(), data);
                    for (ClassDef type : dex.getClasses()) visitor.visit(type);
                }
                if(OBSERVER.get()!=null) OBSERVER.get().update(PHASE.get(),++completed,total);
            }
        }
    }
    /** Bound parallel class analysis to one small batch; merge results in source order. */
    static <T> void visitPrepared(List<String> paths, java.util.function.Function<ClassDef,T> prepare,
            java.util.function.Consumer<T> publish) throws IOException {
        List<ClassDef> batch=new ArrayList<>(128);
        visit(paths,type->{
            batch.add(type);
            if(batch.size()==128) {
                for(T result:ScanWorkers.map(batch,prepare)) publish.accept(result);
                batch.clear();
            }
        });
        if(!batch.isEmpty()) for(T result:ScanWorkers.map(batch,prepare)) publish.accept(result);
    }
    /** Generates public fingerprints, not host bytecode or private user data. */
    public static Properties profile(List<String> paths, Set<String> names) throws IOException {
        Properties result = new Properties();
        visit(paths, type -> {
            String name = type.getType().substring(1, type.getType().length()-1).replace('/', '.');
            if (!names.contains(name)) return;
            Fingerprint fp = fingerprint(type);
            if (result.containsKey("class." + name)) throw new IllegalStateException("Duplicate reference class: " + name);
            result.setProperty("class." + name, fp.shape());
            result.setProperty("header." + name, header(type));
            result.setProperty("selector."+header(type), counts(type));
            result.setProperty("hierarchy." + name, hierarchy(type));
            result.setProperty("signatures."+name, String.join("|",methodHeaders(type)));
            for (String key : fp.members().stringPropertyNames()) result.setProperty(name + ":" + key, fp.members().getProperty(key));
            fp.fieldUses().forEach((field, uses) -> result.setProperty("fieldUses."+name+":"+field, String.join("|",uses)));
            fp.fieldContracts().forEach((field, contract) -> result.setProperty("fieldContract."+name+":"+field, contract));
            fp.fieldTypes().forEach((field, descriptor) -> result.setProperty("fieldType."+name+":"+field, descriptor));
        });
        for (String name : names) if (!result.containsKey("class." + name)) throw new IllegalStateException("Missing reference: " + name);
        // Concrete service implementations can identify abstract interface roles even when callers change.
        visit(paths, type -> {
            if (!type.getType().equals("Lcom/ss/android/ugc/aweme/translation/service/TranslationServiceImpl;")) return;
            String name="X.03hV";
            for (Method method : type.getMethods()) {
                String role=name+":method."+method.getName()+signature(method);
                if (result.containsKey(role) && method.getImplementation()!=null)
                    result.setProperty("implementation."+role, methodFingerprint(method));
            }
        });
        // Abstract methods with identical signatures have no body to distinguish their role.
        // Unique call sites provide independent evidence, without storing host instructions.
        Set<String> ambiguous = new HashSet<>();
        for (String name : names) {
            Map<String,List<String>> groups = new HashMap<>();
            for (String key : result.stringPropertyNames()) if (key.startsWith(name+":method."))
                groups.computeIfAbsent(result.getProperty(key), unused -> new ArrayList<>()).add(key);
            for (var group : groups.values()) if (group.size() > 1) ambiguous.addAll(group);
        }
        Map<String,Set<String>> witnesses = new HashMap<>();
        Map<String,Set<String>> fieldWitnesses = new HashMap<>();
        visit(paths, type -> {
            String callerHeader = null;
            for (Method method : type.getMethods()) if (method.getImplementation() != null) {
                List<Map.Entry<Integer,String>> calls = new ArrayList<>();
                List<Map.Entry<Integer,String>> fields = new ArrayList<>();
                int index = 0;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (instruction instanceof ReferenceInstruction r && r.getReference() instanceof MethodReference called) {
                        String key = methodKey(called);
                        if (ambiguous.contains(key)) calls.add(new AbstractMap.SimpleImmutableEntry<>(index, key));
                    }
                    if (instruction instanceof ReferenceInstruction r && r.getReference() instanceof FieldReference field &&
                            result.containsKey(fieldKey(field)) && obfuscated(field.getName()))
                        fields.add(new AbstractMap.SimpleImmutableEntry<>(index, fieldKey(field)));
                    index++;
                }
                if (calls.isEmpty() && fields.isEmpty()) continue;
                if (callerHeader == null) { callerHeader = header(type); result.setProperty("selector."+callerHeader, counts(type)); }
                String prefix = "witness." + callerHeader + "." + methodFingerprint(method) + "@";
                for (var call : calls) witnesses.computeIfAbsent(prefix+call.getKey(), unused -> new HashSet<>()).add(call.getValue());
                String fieldPrefix = "fieldWitness." + methodFingerprint(method) + "@";
                for (var field : fields) fieldWitnesses.computeIfAbsent(fieldPrefix+field.getKey(), unused -> new HashSet<>()).add(field.getValue());
            }
        });
        Map<String,List<String>> byRole = new HashMap<>();
        witnesses.forEach((key, roles) -> {
            if (roles.size() == 1) byRole.computeIfAbsent(roles.iterator().next(), unused -> new ArrayList<>()).add(key);
        });
        byRole.forEach((role, keys) -> {
            Collections.sort(keys);
            for (String key : keys.subList(0, Math.min(3, keys.size()))) result.setProperty(key, role);
        });
        Map<String,List<String>> byField = new HashMap<>();
        fieldWitnesses.forEach((key, roles) -> {
            if (roles.size() == 1) byField.computeIfAbsent(roles.iterator().next(), unused -> new ArrayList<>()).add(key);
        });
        byField.forEach((role, keys) -> {
            Collections.sort(keys);
            for (String key : keys.subList(0, Math.min(8, keys.size()))) result.setProperty(key, role);
        });
        result.setProperty("format", FORMAT);
        ExtensionRelationshipIndex.profile(paths, result);
        addNativeSelectors(paths, result);
        return result;
    }
    /** Optional search hints derived from headers; never part of matching evidence or cache identity. */
    public static void addNativeSelectors(List<String> paths, Properties profile) throws IOException {
        Set<String> headers = new HashSet<>();
        for (String key : profile.stringPropertyNames()) {
            if (key.startsWith("header.")) headers.add(profile.getProperty(key));
            if (key.startsWith("witness.")) headers.add(key.substring(8, 72));
        }
        Set<String> selectors = new HashSet<>();
        for (String value : headers) selectors.add(profile.getProperty("selector."+value));
        visit(paths, type -> {
            if (!selectors.contains(counts(type))) return;
            String value = header(type);
            if (!headers.contains(value)) return;
            profile.setProperty("native.super."+value, normalized(Objects.toString(type.getSuperclass(), "")));
            List<String> interfaces = new ArrayList<>();
            for (String it : type.getInterfaces()) interfaces.add(normalized(it));
            Collections.sort(interfaces);
            profile.setProperty("native.interfaces."+value, String.join("|", interfaces));
            Set<String> methods = new TreeSet<>();
            for (Method method : type.getMethods()) methods.add(normalized(signature(method)));
            profile.setProperty("native.methods."+value, String.join("|", methods));
        });
    }
    /** Refuses zero/multiple candidates and ambiguous member correspondence; stores only proven mappings. */
    public static Properties scan(List<String> paths, Properties profile) throws IOException {
        return scan(paths, profile, null);
    }
    public static Properties scan(List<String> paths, Properties profile, Set<String> selected) throws IOException {
        if (!FORMAT.equals(profile.getProperty("format"))) throw new IllegalArgumentException("Unknown extension profile format");
        Map<String,List<String>> byHeader = new HashMap<>();
        for (String key : profile.stringPropertyNames()) if (key.startsWith("class.")) {
            String name = key.substring(6);
            byHeader.computeIfAbsent(profile.getProperty("header."+name), unused -> new ArrayList<>()).add(name);
        }
        Map<String,List<Map.Entry<String,Fingerprint>>> matches = new HashMap<>();
        Map<String,List<String>> fuzzyHeaders = new HashMap<>();
        Map<String,List<String>> fuzzyBodies = new HashMap<>();
        for (String key : profile.stringPropertyNames()) if (key.startsWith("class.X.")) {
            String name = key.substring(6);
            List<String> signatures = Arrays.asList(profile.getProperty("signatures."+name, "").split("\\|"));
            if (signatures.size() < 8) continue;
            fuzzyHeaders.put(name, signatures);
            List<String> bodies = new ArrayList<>();
            for (String member : profile.stringPropertyNames()) if (member.startsWith(name+":method.")) bodies.add(profile.getProperty(member));
            fuzzyBodies.put(name, bodies);
        }
        Map<String,Set<String>> callHints = new HashMap<>();
        Map<String,Set<String>> fieldHints = new HashMap<>();
        Set<String> fieldWitnessHashes = new HashSet<>();
        for (String key : profile.stringPropertyNames()) if (key.startsWith("fieldWitness.")) fieldWitnessHashes.add(key.substring(13,77));
        Map<String,ClassDef> definitions = new HashMap<>();
        Map<String,Set<String>> implementationHints = new HashMap<>();
        Map<String,Set<String>> implementationRoles = new HashMap<>();
        for (String key : profile.stringPropertyNames()) if (key.startsWith("implementation."))
            implementationRoles.computeIfAbsent(profile.getProperty(key), unused -> new HashSet<>()).add(key.substring(15));
        Set<String> witnessHeaders = new HashSet<>();
        for (String key : profile.stringPropertyNames()) if (key.startsWith("witness.")) witnessHeaders.add(key.substring(8, 72));
        Set<String> witnessCounts = new HashSet<>();
        for (String value : witnessHeaders) witnessCounts.add(profile.getProperty("selector."+value));
        Map<String,List<String>> byHierarchy = new HashMap<>();
        for (String key : profile.stringPropertyNames()) if (key.startsWith("class.")) {
            String name = key.substring(6);
            byHierarchy.computeIfAbsent(profile.getProperty("hierarchy."+name), unused -> new ArrayList<>()).add(name);
        }
        visit(paths, type -> {
            String actual = type.getType().substring(1, type.getType().length()-1).replace('/', '.');
            if (actual.equals("com.ss.android.ugc.aweme.translation.service.TranslationServiceImpl")) {
                definitions.put(actual, org.jf.dexlib2.immutable.ImmutableClassDef.of(type));
                for (Method method : type.getMethods()) if (method.getImplementation()!=null) {
                    Set<String> roles=implementationRoles.get(methodFingerprint(method));
                    if (roles!=null) for (String role:roles) implementationHints.computeIfAbsent(role, unused -> new HashSet<>())
                        .add("method."+method.getName()+signature(method));
                }
            }
            if (selected != null && !selected.contains(type.getType()) && !profile.containsKey("class."+actual)) return;
            // Count and hierarchy checks do not decode method signatures or instructions. Most
            // native count candidates are unrelated classes; reject them before expensive headers.
            String typeCounts = counts(type);
            int methodCount = Integer.parseInt(typeCounts.substring(0, typeCounts.indexOf(',')));
            List<String> possibleClasses = new ArrayList<>();
            for (String name : byHierarchy.getOrDefault(hierarchy(type), Collections.emptyList())) {
                String expected = profile.getProperty("selector."+profile.getProperty("header."+name));
                int expectedMethods = Integer.parseInt(expected.substring(0, expected.indexOf(',')));
                if (typeCounts.equals(expected) || (!name.startsWith("X.") && name.equals(actual)) ||
                    (fuzzyHeaders.containsKey(name) && methodCount >= 8 &&
                     methodCount * 5 >= expectedMethods * 3 && expectedMethods * 5 >= methodCount * 3))
                    possibleClasses.add(name);
            }
            if (possibleClasses.isEmpty() && !witnessCounts.contains(typeCounts)) return;
            String classHeader = header(type);
            if (witnessHeaders.contains(classHeader)) for (Method method : type.getMethods()) if (method.getImplementation() != null) {
                String prefix = "witness."+classHeader+"."+methodFingerprint(method)+"@";
                int index = 0;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    String role = profile.getProperty(prefix+index);
                    if (role != null && instruction instanceof ReferenceInstruction r && r.getReference() instanceof MethodReference called)
                        callHints.computeIfAbsent(role, unused -> new HashSet<>()).add(methodKey(called));
                    index++;
                }
            }
            List<String> candidates = new ArrayList<>(byHeader.getOrDefault(classHeader, Collections.emptyList()));
            if (methodCount >= 8 && !possibleClasses.isEmpty()) {
                List<String> signatures = methodHeaders(type);
                for (String name : possibleClasses) if (fuzzyHeaders.containsKey(name) && !candidates.contains(name) &&
                    overlaps(fuzzyHeaders.get(name), signatures, 8)) candidates.add(name);
            }
            // Named model/service classes often gain unrelated members in an update.
            // Keep their stable identity/hierarchy, but verify each accessed member independently.
            if (!actual.startsWith("X.") && profile.containsKey("class."+actual) && !candidates.contains(actual)) candidates.add(actual);
            if (!fieldWitnessHashes.isEmpty() && !candidates.isEmpty()) for (Method method : type.getMethods()) if (method.getImplementation()!=null) {
                String hash = methodFingerprint(method);
                if (!fieldWitnessHashes.contains(hash)) continue;
                int index=0;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    String role=profile.getProperty("fieldWitness."+hash+"@"+index++);
                    if (role!=null && instruction instanceof ReferenceInstruction r && r.getReference() instanceof FieldReference field)
                        fieldHints.computeIfAbsent(role, unused -> new HashSet<>()).add(fieldKey(field));
                }
            }
            if (candidates.isEmpty()) return;
            Fingerprint fp = null;
            for (String name : candidates) {
                if (!name.startsWith("X.") && !name.equals(actual)) continue;
                if (fp == null) fp = fingerprint(type);
                if (profile.getProperty("class."+name).equals(fp.shape()) ||
                    (!name.startsWith("X.") && hierarchy(type).equals(profile.getProperty("hierarchy."+name))) ||
                    (fuzzyBodies.containsKey(name) && hierarchy(type).equals(profile.getProperty("hierarchy."+name)) && overlaps(fuzzyBodies.get(name),
                        fp.members().stringPropertyNames().stream().filter(k -> k.startsWith("method.")).map(fp.members()::getProperty)
                            .collect(java.util.stream.Collectors.toList()), 8))) {
                    // Detach only accepted candidates. DexBackedClassDef retains the entire
                    // DEX buffer, which is too expensive inside a running Android host.
                    definitions.computeIfAbsent(actual, unused -> org.jf.dexlib2.immutable.ImmutableClassDef.of(type));
                    matches.computeIfAbsent(name, unused -> new ArrayList<>()).add(new AbstractMap.SimpleImmutableEntry<>(actual, fp));
                }
            }
        });
        // A call site may also identify its declaring class, still subject to shape validation.
        Map<String,Set<String>> ownerHints = new HashMap<>();
        callHints.forEach((role, values) -> {
            if (values.size() == 1) ownerHints.computeIfAbsent(role.substring(0, role.indexOf(":")), unused -> new HashSet<>())
                .add(values.iterator().next().split(":", 2)[0]);
        });
        ownerHints.forEach((name, values) -> {
            if (values.size() == 1 && matches.containsKey(name)) matches.put(name, matches.get(name).stream()
                .filter(it -> values.contains(it.getKey())).collect(java.util.stream.Collectors.toList()));
        });
        // Tiny interfaces can share an implementation shape. Resolve them only through
        // parameter/field relationships from an already unique class, then revalidate shape.
        for (int pass = 0; pass < matches.size(); pass++) {
            Map<String,Set<String>> hints = new HashMap<>();
            for (var entry : matches.entrySet()) if (entry.getValue().size() == 1) {
                String name = entry.getKey();
                var actual = entry.getValue().get(0).getValue();
                for (String key : profile.stringPropertyNames()) if (key.startsWith(name+":")) {
                    String role = key.substring(name.length()+1);
                    String kind = role.startsWith("method.") ? "method." : "field.";
                    var matching = memberMatches(actual.members(), role, profile.getProperty(key));
                    if (matching.size() != 1) continue;
                    String mapped = matching.get(0).substring(kind.length());
                    if (kind.equals("method.")) hint(role.substring(role.indexOf('(')), mapped.substring(mapped.indexOf('(')), profile, hints);
                    else hint(profile.getProperty("fieldType."+name+":"+role.substring(6), ""), actual.fieldTypes().get(mapped), profile, hints);
                }
            }
            boolean changed = false;
            for (var hint : hints.entrySet()) {
                var previous = matches.getOrDefault(hint.getKey(), List.of());
                var filtered = previous.stream().filter(it -> hint.getValue().size() == 1 && hint.getValue().contains(it.getKey()))
                    .collect(java.util.stream.Collectors.toList());
                if (previous.size() != filtered.size()) { matches.put(hint.getKey(), filtered); changed = true; }
            }
            if (!changed) break;
        }
        Map<String,Integer> claims = new HashMap<>();
        for (var candidates : matches.values()) if (candidates.size() == 1)
            claims.merge(candidates.get(0).getKey(), 1, Integer::sum);
        Properties result = new Properties();
        for (var group : byHeader.values()) for (String name : group) {
            var candidates = matches.getOrDefault(name, List.of());
            if (candidates.size() != 1) {
                result.setProperty("error."+name, "structural candidates=" + candidates.size());
                continue;
            }
            var match = candidates.get(0);
            if (claims.getOrDefault(match.getKey(), 0) != 1) {
                result.setProperty("error."+name, "class claimed by multiple reference roles");
                continue;
            }
            result.setProperty("class."+name, match.getKey());
            var actual = match.getValue().members();
            for (String key : profile.stringPropertyNames()) if (key.startsWith(name+":")) {
                String kind = key.substring(name.length()+1).startsWith("method.") ? "method." : "field.";
                List<String> mapped = memberMatches(actual, key.substring(name.length()+1), profile.getProperty(key));
                String role = key.substring(name.length()+1);
                if (role.startsWith("field.")) {
                    String field = role.substring(6);
                    if (!obfuscated(field) &&
                        Objects.equals(profile.getProperty("fieldContract."+name+":"+field), match.getValue().fieldContracts().get(field)) &&
                        match.getValue().fieldContracts().containsKey(field)) mapped = Collections.singletonList(role);
                }
                if (mapped.isEmpty() && role.startsWith("field.")) {
                    String field = role.substring(6);
                    String oldUses = profile.getProperty("fieldUses."+name+":"+field, "");
                    List<String> sites = Arrays.asList(oldUses.split("\\|"));
                    if (sites.size() >= 2) {
                        List<String> alternatives = new ArrayList<>();
                        for (var actualField : match.getValue().fieldUses().entrySet()) {
                            if (!Objects.equals(profile.getProperty("fieldContract."+name+":"+field),
                                match.getValue().fieldContracts().get(actualField.getKey()))) continue;
                            Set<String> common = new HashSet<>(sites); common.retainAll(actualField.getValue());
                            if (common.size() >= 2) alternatives.add("field."+actualField.getKey());
                        }
                        if (alternatives.size() == 1) mapped = alternatives;
                    }
                }
                Set<String> external = fieldHints.get(key);
                if (role.startsWith("field.") && external!=null && external.size()==1) {
                    String full=external.iterator().next();
                    if (full.startsWith(match.getKey()+":field.")) {
                        String field=full.substring(full.indexOf(":field.")+7);
                        if (Objects.equals(profile.getProperty("fieldContract."+name+":"+role.substring(6)),
                                match.getValue().fieldContracts().get(field)) && match.getValue().fieldContracts().containsKey(field))
                            mapped=List.of("field."+field);
                    }
                }
                Set<String> implemented = implementationHints.get(key);
                if (implemented != null) mapped = mapped.stream().filter(it -> implemented.size()==1 && implemented.contains(it))
                    .collect(java.util.stream.Collectors.toList());
                Set<String> evidence = callHints.get(key);
                if (evidence != null) mapped = mapped.stream().filter(it -> evidence.size() == 1 &&
                    evidence.contains(match.getKey()+":"+it)).collect(java.util.stream.Collectors.toList());
                if (mapped.size() == 1) result.setProperty(key, mapped.get(0).substring(kind.length()));
                else result.setProperty("error."+key, "member candidates=" + mapped.size());
            }
        }
        // Full structural fingerprints are no longer needed by the relationship pass.
        matches.clear(); callHints.clear(); fieldHints.clear(); implementationHints.clear();
        phase("comment-gate");
        CommentGateIndex.resolve(paths,result);
        phase("relationships");
        ExtensionRelationshipIndex.resolve(paths, profile, result, definitions);
        phase("members");
        ExtensionSemanticContracts.resolve(definitions, result);
        ExtensionRelationshipIndex.completeMembers(paths, profile, result, definitions);
        ExtensionSemanticContracts.resolve(definitions, result);
        ExtensionRelationshipIndex.completeMembers(paths, profile, result, definitions);
        return result;
    }
    private static void hint(String source, String target, Properties profile, Map<String,Set<String>> hints) {
        if (target == null) return;
        var pattern = OBJECT_TYPE;
        var left = pattern.matcher(source);
        var right = pattern.matcher(target);
        while (left.find() && right.find()) {
            String name = left.group().substring(1, left.group().length()-1).replace('/', '.');
            String mapped = right.group().substring(1, right.group().length()-1).replace('/', '.');
            if (profile.containsKey("class."+name)) hints.computeIfAbsent(name, unused -> new HashSet<>()).add(mapped);
        }
    }
    public static Properties identity(Properties profile) {
        Properties result = new Properties();
        for (String key : profile.stringPropertyNames()) {
            if (key.startsWith("class.")) result.setProperty(key, key.substring(6));
            else if (key.contains(":method.") && profile.containsKey("class."+key.substring(0,key.indexOf(':'))))
                result.setProperty(key, key.substring(key.indexOf(":method.")+8));
            else if (key.contains(":field.") && profile.containsKey("class."+key.substring(0,key.indexOf(':'))))
                result.setProperty(key, key.substring(key.indexOf(":field.")+7));
        }
        return result;
    }
}
