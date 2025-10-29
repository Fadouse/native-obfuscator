package dev.skidfuscator.obfuscator.renamer;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.exempt.ExemptManager;
import dev.skidfuscator.obfuscator.renamer.config.EntityRenamerSettings;
import dev.skidfuscator.obfuscator.renamer.config.RenamerSettings;
import dev.skidfuscator.obfuscator.renamer.generator.NameGenerator;
import dev.skidfuscator.obfuscator.renamer.generator.RadixNameGenerator;
import dev.skidfuscator.obfuscator.skidasm.SkidClassNode;
import dev.skidfuscator.obfuscator.skidasm.SkidFieldNode;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.FieldNode;
import org.mapleir.asm.MethodNode;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.topdank.byteengineer.commons.data.JarClassData;
import org.topdank.byteengineer.commons.data.JarResource;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public final class JvmRenamer {
    private final Skidfuscator skidfuscator;
    private final RenamerSettings settings;
    private static final String MAIN_METHOD_DESCRIPTOR = "([Ljava/lang/String;)V";

    public JvmRenamer(Skidfuscator skidfuscator, RenamerSettings settings) {
        this.skidfuscator = skidfuscator;
        this.settings = settings;
    }

    public RenamerResult execute() {
        if (settings == null || !settings.isAnyEnabled()) {
            return RenamerResult.empty();
        }

        List<SkidClassNode> classes = collectClasses();
        if (classes.isEmpty()) {
            return RenamerResult.empty();
        }

        Map<String, SkidClassNode> classIndex = new HashMap<>();
        for (SkidClassNode classNode : classes) {
            classIndex.put(classNode.getName(), classNode);
        }

        EntryPoint entryPoint = readManifestEntryPoint(classIndex);

        Map<String, String> classMappings = new LinkedHashMap<>();
        Map<MethodSignature, String> methodMappings = new LinkedHashMap<>();
        Map<FieldSignature, String> fieldMappings = new LinkedHashMap<>();

        renameClasses(classes, classMappings);
        renameMethods(classes, methodMappings);
        renameFields(classes, fieldMappings);

        if (classMappings.isEmpty() && methodMappings.isEmpty() && fieldMappings.isEmpty()) {
            return RenamerResult.empty();
        }

        rewriteReflectionSites(classes, classMappings, methodMappings, fieldMappings);
        updateManifest(classMappings, methodMappings, entryPoint);

        return new RenamerResult(classMappings.size(), methodMappings.size(), fieldMappings.size());
    }

    private EntryPoint readManifestEntryPoint(Map<String, SkidClassNode> classIndex) {
        JarResource manifestResource = skidfuscator.getJarContents()
                .getResourceContents()
                .namedMap()
                .get(JarFile.MANIFEST_NAME);

        if (manifestResource == null) {
            return null;
        }

        try (ByteArrayInputStream input = new ByteArrayInputStream(manifestResource.getData())) {
            Manifest manifest = new Manifest(input);
            Attributes attributes = manifest.getMainAttributes();
            String rawMainClass = attributes.getValue(Attributes.Name.MAIN_CLASS);
            if (rawMainClass == null || rawMainClass.isEmpty()) {
                return null;
            }

            String owner = rawMainClass;
            String method = "main";

            String internalOwner = owner.replace('.', '/');
            if (!classIndex.containsKey(internalOwner)) {
                int split = rawMainClass.lastIndexOf('.');
                if (split > 0) {
                    owner = rawMainClass.substring(0, split);
                    method = rawMainClass.substring(split + 1);
                    internalOwner = owner.replace('.', '/');
                }
            }

            SkidClassNode classNode = classIndex.get(internalOwner);
            String descriptor = null;
            if (classNode != null) {
                for (MethodNode methodNode : classNode.getMethods()) {
                    if (methodNode.getName().equals(method)) {
                        descriptor = methodNode.getDesc();
                        break;
                    }
                }
            }

            return new EntryPoint(internalOwner, method, descriptor != null ? descriptor : MAIN_METHOD_DESCRIPTOR);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to inspect manifest before renaming", exception);
        }
    }

    private List<SkidClassNode> collectClasses() {
        List<SkidClassNode> result = new ArrayList<>();
        for (ClassNode node : skidfuscator.getClassSource().iterate()) {
            if (node instanceof SkidClassNode) {
                result.add((SkidClassNode) node);
            }
        }
        return result;
    }

    private void renameClasses(List<SkidClassNode> classes, Map<String, String> classMappings) {
        EntityRenamerSettings classSettings = settings.getClassSettings();
        if (!classSettings.isEnabled()) {
            return;
        }

        Set<String> usedNames = new HashSet<>();
        for (JarClassData classData : skidfuscator.getJarContents().getClassContents()) {
            String name = classData.getName();
            if (name.endsWith(".class")) {
                name = name.substring(0, name.length() - 6);
            }
            usedNames.add(name.replace('.', '/'));
        }
        for (SkidClassNode classNode : classes) {
            usedNames.add(classNode.getName());
        }
        NameGenerator generator = new RadixNameGenerator(classSettings.getAlphabet(), classSettings.getMinLength());
        ExemptManager exemptManager = skidfuscator.getExemptAnalysis();

        for (SkidClassNode classNode : classes) {
            if (!shouldRenameClass(classNode, exemptManager)) {
                continue;
            }

            String newName = nextUniqueClassName(generator, classSettings, usedNames);
            classMappings.put(classNode.getName(), newName);
            skidfuscator.getClassRemapper().add(classNode.getName(), newName);
        }
    }

    private void renameMethods(List<SkidClassNode> classes, Map<MethodSignature, String> methodMappings) {
        EntityRenamerSettings methodSettings = settings.getMethodSettings();
        if (!methodSettings.isEnabled()) {
            return;
        }

        ExemptManager exemptManager = skidfuscator.getExemptAnalysis();

        for (SkidClassNode classNode : classes) {
            if (skidfuscator.getClassSource().isLibraryClass(classNode.getName()) || exemptManager.isExempt(classNode)) {
                continue;
            }

            NameGenerator generator = new RadixNameGenerator(methodSettings.getAlphabet(), methodSettings.getMinLength());
            Set<String> usedNames = new HashSet<>();

            for (MethodNode method : classNode.getMethods()) {
                SkidMethodNode skidMethod = method instanceof SkidMethodNode ? (SkidMethodNode) method : new SkidMethodNode(method.node, classNode, skidfuscator);
                if (!shouldRenameMethod(skidMethod, exemptManager)) {
                    continue;
                }

                String newName = nextUniqueName(generator, usedNames);
                methodMappings.put(new MethodSignature(classNode.getName(), skidMethod.getName(), skidMethod.node.desc), newName);
                skidfuscator.getClassRemapper().add(classNode.getName() + '.' + skidMethod.getName() + skidMethod.node.desc, newName);
            }
        }
    }

    private void renameFields(List<SkidClassNode> classes, Map<FieldSignature, String> fieldMappings) {
        EntityRenamerSettings fieldSettings = settings.getFieldSettings();
        if (!fieldSettings.isEnabled()) {
            return;
        }

        ExemptManager exemptManager = skidfuscator.getExemptAnalysis();

        for (SkidClassNode classNode : classes) {
            if (skidfuscator.getClassSource().isLibraryClass(classNode.getName()) || exemptManager.isExempt(classNode)) {
                continue;
            }

            NameGenerator generator = new RadixNameGenerator(fieldSettings.getAlphabet(), fieldSettings.getMinLength());
            Set<String> usedNames = new HashSet<>();

            for (FieldNode field : classNode.getFields()) {
                SkidFieldNode skidField = field instanceof SkidFieldNode ? (SkidFieldNode) field : new SkidFieldNode(field.node, classNode, skidfuscator);
                String newName = nextUniqueName(generator, usedNames);
                fieldMappings.put(new FieldSignature(classNode.getName(), skidField.getName(), skidField.node.desc), newName);
                skidfuscator.getClassRemapper().add(classNode.getName() + '.' + skidField.getName(), newName);
            }
        }
    }

    private boolean shouldRenameClass(SkidClassNode classNode, ExemptManager exemptManager) {
        if (skidfuscator.getClassSource().isLibraryClass(classNode.getName())) {
            return false;
        }
        if (exemptManager.isExempt(classNode)) {
            return false;
        }
        return true;
    }

    private boolean shouldRenameMethod(SkidMethodNode methodNode, ExemptManager exemptManager) {
        if (methodNode.getName().equals("<init>") || methodNode.getName().equals("<clinit>")) {
            return false;
        }
        if (exemptManager.isExempt(methodNode.owner) || exemptManager.isExempt(methodNode)) {
            return false;
        }
        return true;
    }

    private String nextUniqueClassName(NameGenerator generator, EntityRenamerSettings settings, Set<String> usedNames) {
        while (true) {
            String candidate = formatClassName(generator.next(), settings);
            if (usedNames.add(candidate)) {
                return candidate;
            }
        }
    }

    private String formatClassName(String base, EntityRenamerSettings settings) {
        String prefix = settings.getPrefix();
        int depth = settings.getPackageDepth();
        int segmentLength = settings.getSegmentLength();
        String fallback = settings.getFallbackToken();

        StringBuilder working = new StringBuilder(base);
        int required = segmentLength * Math.max(0, depth - 1) + 1;
        while (working.length() < required) {
            working.append(fallback);
        }

        List<String> segments = new ArrayList<>();
        int index = 0;
        for (int i = 0; i < depth - 1; i++) {
            segments.add(working.substring(index, index + segmentLength));
            index += segmentLength;
        }
        segments.add(working.substring(index));

        StringBuilder builder = new StringBuilder();
        builder.append(prefix);
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).isEmpty()) {
                builder.append(fallback);
            } else {
                builder.append(segments.get(i));
            }
            if (i + 1 < segments.size()) {
                builder.append('/');
            }
        }
        return builder.toString();
    }

    private String nextUniqueName(NameGenerator generator, Set<String> usedNames) {
        while (true) {
            String candidate = generator.next();
            if (usedNames.add(candidate)) {
                return candidate;
            }
        }
    }

    private void rewriteReflectionSites(List<SkidClassNode> classes,
                                        Map<String, String> classMappings,
                                        Map<MethodSignature, String> methodMappings,
                                        Map<FieldSignature, String> fieldMappings) {
        if (classMappings.isEmpty() && methodMappings.isEmpty() && fieldMappings.isEmpty()) {
            return;
        }

        Map<String, List<Map.Entry<MethodSignature, String>>> methodsByOwner = new HashMap<>();
        for (Map.Entry<MethodSignature, String> entry : methodMappings.entrySet()) {
            methodsByOwner.computeIfAbsent(entry.getKey().owner, key -> new ArrayList<>()).add(entry);
        }

        Map<String, List<Map.Entry<FieldSignature, String>>> fieldsByOwner = new HashMap<>();
        for (Map.Entry<FieldSignature, String> entry : fieldMappings.entrySet()) {
            fieldsByOwner.computeIfAbsent(entry.getKey().owner, key -> new ArrayList<>()).add(entry);
        }

        IdentityHashMap<AbstractInsnNode, String> classLoadOrigins = new IdentityHashMap<>();

        for (SkidClassNode classNode : classes) {
            for (MethodNode method : classNode.getMethods()) {
                org.objectweb.asm.tree.MethodNode asmMethod = method.node;
                if (asmMethod.instructions == null || asmMethod.instructions.size() == 0) {
                    continue;
                }

                Analyzer<SourceValue> analyzer = new Analyzer<>(new SourceInterpreter());
                Frame<SourceValue>[] frames;
                try {
                    frames = analyzer.analyze(classNode.getName(), asmMethod);
                } catch (AnalyzerException exception) {
                    continue;
                }

                for (int index = 0; index < asmMethod.instructions.size(); index++) {
                    AbstractInsnNode insn = asmMethod.instructions.get(index);
                    if (!(insn instanceof MethodInsnNode)) {
                        continue;
                    }

                    MethodInsnNode call = (MethodInsnNode) insn;
                    Frame<SourceValue> frame = frames[index];
                    if (frame == null) {
                        continue;
                    }

                    if (isClassForName(call)) {
                        handleClassForName(call, frame, classMappings, classLoadOrigins);
                        continue;
                    }

                    if (isClassLoaderLoadClass(call)) {
                        handleClassLoaderLoadClass(call, frame, classMappings, classLoadOrigins);
                        continue;
                    }

                    if (isMethodLookup(call)) {
                        handleMethodLookup(call, frame, methodsByOwner, classLoadOrigins);
                        continue;
                    }

                    if (isFieldLookup(call)) {
                        handleFieldLookup(call, frame, fieldsByOwner, classLoadOrigins);
                    }
                }
            }
        }
    }

    private void updateManifest(Map<String, String> classMappings,
                                Map<MethodSignature, String> methodMappings,
                                EntryPoint entryPoint) {
        if (entryPoint == null) {
            return;
        }

        JarResource manifestResource = skidfuscator.getJarContents()
                .getResourceContents()
                .namedMap()
                .get(JarFile.MANIFEST_NAME);

        if (manifestResource == null) {
            return;
        }

        try (ByteArrayInputStream input = new ByteArrayInputStream(manifestResource.getData())) {
            Manifest manifest = new Manifest(input);
            Attributes attributes = manifest.getMainAttributes();
            String mainClass = attributes.getValue(Attributes.Name.MAIN_CLASS);
            if (mainClass == null) {
                return;
            }

            String owner = entryPoint.owner;
            String method = entryPoint.method;
            String descriptor = entryPoint.descriptor;

            String mappedOwner = classMappings.getOrDefault(owner, owner);
            String mappedMethod = methodMappings.getOrDefault(new MethodSignature(owner, method, descriptor), method);

            String manifestValue = Type.getObjectType(mappedOwner).getClassName() + '.' + mappedMethod;
            attributes.putValue(Attributes.Name.MAIN_CLASS.toString(), manifestValue);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            manifest.write(output);
            manifestResource.setData(output.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to update manifest after renaming", exception);
        }
    }

    private boolean isClassForName(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESTATIC
                && "java/lang/Class".equals(call.owner)
                && "forName".equals(call.name);
    }

    private boolean isClassLoaderLoadClass(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "java/lang/ClassLoader".equals(call.owner)
                && "loadClass".equals(call.name);
    }

    private boolean isMethodLookup(MethodInsnNode call) {
        if (!"java/lang/Class".equals(call.owner)) {
            return false;
        }
        return "getDeclaredMethod".equals(call.name) || "getMethod".equals(call.name);
    }

    private boolean isFieldLookup(MethodInsnNode call) {
        if (!"java/lang/Class".equals(call.owner)) {
            return false;
        }
        return "getDeclaredField".equals(call.name) || "getField".equals(call.name);
    }

    private void handleClassForName(MethodInsnNode call,
                                    Frame<SourceValue> frame,
                                    Map<String, String> classMappings,
                                    IdentityHashMap<AbstractInsnNode, String> classLoadOrigins) {
        Type[] args = Type.getArgumentTypes(call.desc);
        int stackIndex = frame.getStackSize() - args.length;
        SourceValue stringValue = frame.getStack(stackIndex);
        String original = extractStringLiteral(stringValue);
        if (original == null) {
            return;
        }

        String internal = original.replace('.', '/');
        String mapped = classMappings.get(internal);
        if (mapped == null) {
            return;
        }

        updateStringLiteral(stringValue, Type.getObjectType(mapped).getClassName());
        classLoadOrigins.put(call, internal);
    }

    private void handleClassLoaderLoadClass(MethodInsnNode call,
                                            Frame<SourceValue> frame,
                                            Map<String, String> classMappings,
                                            IdentityHashMap<AbstractInsnNode, String> classLoadOrigins) {
        Type[] args = Type.getArgumentTypes(call.desc);
        int stackIndex = frame.getStackSize() - args.length;
        SourceValue stringValue = frame.getStack(stackIndex);
        String original = extractStringLiteral(stringValue);
        if (original == null) {
            return;
        }

        String internal = original.replace('.', '/');
        String mapped = classMappings.get(internal);
        if (mapped == null) {
            return;
        }

        updateStringLiteral(stringValue, Type.getObjectType(mapped).getClassName());
        classLoadOrigins.put(call, internal);
    }

    private void handleMethodLookup(MethodInsnNode call,
                                    Frame<SourceValue> frame,
                                    Map<String, List<Map.Entry<MethodSignature, String>>> methodsByOwner,
                                    IdentityHashMap<AbstractInsnNode, String> classLoadOrigins) {
        Type[] args = Type.getArgumentTypes(call.desc);
        int argCount = args.length;
        int firstArgIndex = frame.getStackSize() - argCount;
        if (call.getOpcode() != Opcodes.INVOKESTATIC) {
            firstArgIndex -= 1;
        }

        if (firstArgIndex < 0) {
            return;
        }

        SourceValue nameValue = frame.getStack(firstArgIndex);
        String originalName = extractStringLiteral(nameValue);
        if (originalName == null) {
            return;
        }

        String owner = resolveOwnerInternal(frame, firstArgIndex, classLoadOrigins);
        if (owner == null) {
            return;
        }

        List<Map.Entry<MethodSignature, String>> candidates = methodsByOwner.get(owner);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        List<Map.Entry<MethodSignature, String>> matches = new ArrayList<>();
        for (Map.Entry<MethodSignature, String> entry : candidates) {
            if (entry.getKey().name.equals(originalName)) {
                matches.add(entry);
            }
        }

        if (matches.isEmpty()) {
            return;
        }

        if (matches.size() != 1) {
            return;
        }

        updateStringLiteral(nameValue, matches.get(0).getValue());
    }

    private void handleFieldLookup(MethodInsnNode call,
                                   Frame<SourceValue> frame,
                                   Map<String, List<Map.Entry<FieldSignature, String>>> fieldsByOwner,
                                   IdentityHashMap<AbstractInsnNode, String> classLoadOrigins) {
        Type[] args = Type.getArgumentTypes(call.desc);
        int argCount = args.length;
        int firstArgIndex = frame.getStackSize() - argCount;
        if (call.getOpcode() != Opcodes.INVOKESTATIC) {
            firstArgIndex -= 1;
        }

        if (firstArgIndex < 0) {
            return;
        }

        SourceValue nameValue = frame.getStack(firstArgIndex);
        String originalName = extractStringLiteral(nameValue);
        if (originalName == null) {
            return;
        }

        String owner = resolveOwnerInternal(frame, firstArgIndex, classLoadOrigins);
        if (owner == null) {
            return;
        }

        List<Map.Entry<FieldSignature, String>> candidates = fieldsByOwner.get(owner);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        List<Map.Entry<FieldSignature, String>> matches = new ArrayList<>();
        for (Map.Entry<FieldSignature, String> entry : candidates) {
            if (entry.getKey().name.equals(originalName)) {
                matches.add(entry);
            }
        }

        if (matches.size() != 1) {
            return;
        }

        updateStringLiteral(nameValue, matches.get(0).getValue());
    }

    private String resolveOwnerInternal(Frame<SourceValue> frame,
                                        int firstArgIndex,
                                        IdentityHashMap<AbstractInsnNode, String> classLoadOrigins) {
        int ownerIndex = firstArgIndex - 1;
        if (ownerIndex < 0) {
            return null;
        }

        SourceValue ownerValue = frame.getStack(ownerIndex);
        if (ownerValue == null) {
            return null;
        }

        for (AbstractInsnNode source : ownerValue.insns) {
            if (source instanceof LdcInsnNode) {
                Object cst = ((LdcInsnNode) source).cst;
                if (cst instanceof Type) {
                    return ((Type) cst).getInternalName();
                }
            }
            if (source instanceof MethodInsnNode) {
                String mapped = classLoadOrigins.get(source);
                if (mapped != null) {
                    return mapped;
                }
            }
        }

        return null;
    }

    private String extractStringLiteral(SourceValue value) {
        if (value == null) {
            return null;
        }
        for (AbstractInsnNode source : value.insns) {
            if (source instanceof LdcInsnNode) {
                Object cst = ((LdcInsnNode) source).cst;
                if (cst instanceof String) {
                    return (String) cst;
                }
            }
        }
        return null;
    }

    private void updateStringLiteral(SourceValue value, String updated) {
        if (value == null) {
            return;
        }
        for (AbstractInsnNode source : value.insns) {
            if (source instanceof LdcInsnNode) {
                LdcInsnNode ldc = (LdcInsnNode) source;
                if (ldc.cst instanceof String) {
                    ldc.cst = updated;
                    return;
                }
            }
        }
    }

    public static final class RenamerResult {
        private final int classesRenamed;
        private final int methodsRenamed;
        private final int fieldsRenamed;

        private RenamerResult(int classesRenamed, int methodsRenamed, int fieldsRenamed) {
            this.classesRenamed = classesRenamed;
            this.methodsRenamed = methodsRenamed;
            this.fieldsRenamed = fieldsRenamed;
        }

        public static RenamerResult empty() {
            return new RenamerResult(0, 0, 0);
        }

        public int getClassesRenamed() {
            return classesRenamed;
        }

        public int getMethodsRenamed() {
            return methodsRenamed;
        }

        public int getFieldsRenamed() {
            return fieldsRenamed;
        }

        public boolean hasChanges() {
            return classesRenamed > 0 || methodsRenamed > 0 || fieldsRenamed > 0;
        }
    }

    private static final class EntryPoint {
        private final String owner;
        private final String method;
        private final String descriptor;

        private EntryPoint(String owner, String method, String descriptor) {
            this.owner = owner;
            this.method = method;
            this.descriptor = descriptor;
        }
    }

    private static final class MethodSignature {
        private final String owner;
        private final String name;
        private final String desc;

        private MethodSignature(String owner, String name, String desc) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MethodSignature that = (MethodSignature) o;
            return Objects.equals(owner, that.owner) &&
                    Objects.equals(name, that.name) &&
                    Objects.equals(desc, that.desc);
        }

        @Override
        public int hashCode() {
            return Objects.hash(owner, name, desc);
        }
    }

    private static final class FieldSignature {
        private final String owner;
        private final String name;
        private final String desc;

        private FieldSignature(String owner, String name, String desc) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FieldSignature that = (FieldSignature) o;
            return Objects.equals(owner, that.owner) &&
                    Objects.equals(name, that.name) &&
                    Objects.equals(desc, that.desc);
        }

        @Override
        public int hashCode() {
            return Objects.hash(owner, name, desc);
        }
    }
}
