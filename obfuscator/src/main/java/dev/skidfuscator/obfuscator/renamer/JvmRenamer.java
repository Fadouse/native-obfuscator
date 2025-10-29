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
import dev.skidfuscator.runtime.ReflectionSupport;
import org.mapleir.asm.ClassHelper;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.FieldNode;
import org.mapleir.asm.MethodNode;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.topdank.byteengineer.commons.data.JarClassData;

import java.io.IOException;
import java.util.*;

public final class JvmRenamer {
    private static final String REFLECTION_SUPPORT_INTERNAL = "dev/skidfuscator/runtime/ReflectionSupport";
    private static final String METADATA_INTERNAL = "dev/skidfuscator/runtime/ReflectionMappings";

    private final Skidfuscator skidfuscator;
    private final RenamerSettings settings;

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

        Map<String, String> classMappings = new LinkedHashMap<>();
        Map<MethodSignature, String> methodMappings = new LinkedHashMap<>();
        Map<FieldSignature, String> fieldMappings = new LinkedHashMap<>();

        renameClasses(classes, classMappings);
        renameMethods(classes, methodMappings);
        renameFields(classes, fieldMappings);

        if (classMappings.isEmpty() && methodMappings.isEmpty() && fieldMappings.isEmpty()) {
            return RenamerResult.empty();
        }

        injectReflectionMetadata(classMappings, methodMappings, fieldMappings);

        return new RenamerResult(classMappings.size(), methodMappings.size(), fieldMappings.size());
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
        usedNames.add(METADATA_INTERNAL);
        usedNames.add(REFLECTION_SUPPORT_INTERNAL);

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

    private void injectReflectionMetadata(Map<String, String> classMappings,
                                          Map<MethodSignature, String> methodMappings,
                                          Map<FieldSignature, String> fieldMappings) {
        if (classMappings.isEmpty() && methodMappings.isEmpty() && fieldMappings.isEmpty()) {
            return;
        }

        ensureReflectionSupportClass();

        if (skidfuscator.getClassSource().findClassNode(METADATA_INTERNAL) != null) {
            return;
        }

        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        node.version = Opcodes.V1_8;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        node.name = METADATA_INTERNAL;
        node.superName = "java/lang/Object";

        SkidClassNode metadata = new SkidClassNode(node, skidfuscator);

        org.objectweb.asm.tree.MethodNode clinit = new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        InsnList insnList = clinit.instructions;

        for (Map.Entry<String, String> entry : classMappings.entrySet()) {
            insnList.add(new LdcInsnNode(Type.getObjectType(entry.getKey()).getClassName()));
            insnList.add(new LdcInsnNode(Type.getObjectType(entry.getValue()).getClassName()));
            insnList.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    REFLECTION_SUPPORT_INTERNAL,
                    "registerClassInternal",
                    "(Ljava/lang/String;Ljava/lang/String;)V",
                    false));
        }

        for (Map.Entry<MethodSignature, String> entry : methodMappings.entrySet()) {
            MethodSignature signature = entry.getKey();
            insnList.add(new LdcInsnNode(Type.getObjectType(signature.owner).getClassName()));
            insnList.add(new LdcInsnNode(signature.desc));
            insnList.add(new LdcInsnNode(signature.name));
            insnList.add(new LdcInsnNode(entry.getValue()));
            insnList.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    REFLECTION_SUPPORT_INTERNAL,
                    "registerMethodInternal",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                    false));
        }

        for (Map.Entry<FieldSignature, String> entry : fieldMappings.entrySet()) {
            FieldSignature signature = entry.getKey();
            insnList.add(new LdcInsnNode(Type.getObjectType(signature.owner).getClassName()));
            insnList.add(new LdcInsnNode(signature.desc));
            insnList.add(new LdcInsnNode(signature.name));
            insnList.add(new LdcInsnNode(entry.getValue()));
            insnList.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    REFLECTION_SUPPORT_INTERNAL,
                    "registerFieldInternal",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                    false));
        }

        insnList.add(new InsnNode(Opcodes.RETURN));
        metadata.node.methods.add(clinit);

        org.objectweb.asm.tree.MethodNode ctor = new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        ctor.instructions.add(new InsnNode(Opcodes.RETURN));
        metadata.node.methods.add(ctor);

        skidfuscator.getClassSource().add(metadata);
        skidfuscator.getJarContents().getClassContents().add(new JarClassData(
                METADATA_INTERNAL + ".class",
                metadata.toByteArray(),
                metadata
        ));
    }

    private void ensureReflectionSupportClass() {
        if (skidfuscator.getClassSource().findClassNode(REFLECTION_SUPPORT_INTERNAL) != null) {
            return;
        }

        final org.mapleir.asm.ClassNode runtimeNode;
        try {
            runtimeNode = ClassHelper.create(ReflectionSupport.class.getName());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load ReflectionSupport runtime class", exception);
        }

        SkidClassNode skidNode = new SkidClassNode(runtimeNode.node, skidfuscator);
        skidfuscator.getClassSource().add(skidNode);
        skidfuscator.getJarContents().getClassContents().add(new JarClassData(
                REFLECTION_SUPPORT_INTERNAL + ".class",
                skidNode.toByteArray(),
                skidNode
        ));
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
