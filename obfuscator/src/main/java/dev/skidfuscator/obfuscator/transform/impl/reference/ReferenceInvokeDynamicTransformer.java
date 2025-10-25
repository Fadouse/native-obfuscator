package dev.skidfuscator.obfuscator.transform.impl.reference;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.event.EventPriority;
import dev.skidfuscator.obfuscator.event.annotation.Listen;
import dev.skidfuscator.obfuscator.event.impl.transform.method.DumpMethodTransformEvent;
import dev.skidfuscator.obfuscator.skidasm.SkidClassNode;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import dev.skidfuscator.runtime.InvokeDynamicRuntime;
import lombok.RequiredArgsConstructor;
import org.mapleir.asm.ClassHelper;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.topdank.byteengineer.commons.data.JarClassData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ReferenceInvokeDynamicTransformer extends AbstractTransformer {
    private static final Type OBJECT_TYPE = Type.getType(Object.class);
    private static final String BOOTSTRAP_NAME = "bootstrap";
    private static final String BOOTSTRAP_DESC =
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "ILjava/lang/String;JLjava/lang/String;JLjava/lang/String;JI)Ljava/lang/invoke/CallSite;";
    private static final Base64.Encoder BASE64 = Base64.getEncoder();
    private static final long MIX = 0x9E3779B97F4A7C15L;

    private final List<RuntimeShard> runtimeShards = new ArrayList<>();
    private final Map<String, RuntimeShard> classAssignments = new HashMap<>();
    private final Set<String> runtimeOwnerNames = new HashSet<>();

    public ReferenceInvokeDynamicTransformer(Skidfuscator skidfuscator) {
        super(skidfuscator, "Reference");
    }

    @Override
    protected ReferenceConfig createConfig() {
        return new ReferenceConfig(skidfuscator.getTsConfig(), "reference");
    }

    @Override
    public ReferenceConfig getConfig() {
        return (ReferenceConfig) super.getConfig();
    }

    public synchronized void ensureRuntimeHelpers() {
        if (!runtimeShards.isEmpty()) {
            return;
        }
        runtimeShards.clear();
        classAssignments.clear();
        runtimeOwnerNames.clear();

        try {
            prepareRuntimeShards();
        } catch (IOException ex) {
            throw new RuntimeException("Failed to inject invoke-dynamic runtime helpers", ex);
        }
    }

    @Listen(EventPriority.MONITOR)
    void handle(final DumpMethodTransformEvent event) {
        if (runtimeShards.isEmpty()) {
            this.fail();
            return;
        }

        final SkidMethodNode methodNode = event.getMethodNode();
        if (methodNode.isAbstract()) {
            this.skip();
            return;
        }
        if (runtimeOwnerNames.contains(methodNode.getParent().getName())) {
            this.skip();
            return;
        }
        if (skidfuscator.getExemptAnalysis().isExempt(this.getClass(), methodNode)) {
            this.skip();
            return;
        }
        if (methodNode.node.instructions == null || methodNode.node.instructions.size() == 0) {
            this.skip();
            return;
        }

        final ReferenceConfig config = getConfig();
        final double probability = config.getProbability();
        final int maxPerMethod = config.getMaxPerMethod();

        boolean mutated = false;
        int transformed = 0;
        final InsnList insnList = methodNode.node.instructions;
        for (AbstractInsnNode insn = insnList.getFirst(); insn != null; ) {
            if (maxPerMethod > 0 && transformed >= maxPerMethod) {
                break;
            }
            final AbstractInsnNode next = insn.getNext();

            if (!shouldTransform(probability)) {
                insn = next;
                continue;
            }

            AbstractInsnNode replacement = null;
            if (insn instanceof MethodInsnNode methodInsn) {
                replacement = transformMethodInsn(methodNode, methodInsn);
            } else if (insn instanceof FieldInsnNode fieldInsn) {
                replacement = transformFieldInsn(methodNode.getParent(), fieldInsn);
            }

            if (replacement != null) {
                insnList.set(insn, replacement);
                mutated = true;
                transformed++;
            }

            insn = next;
        }

        if (mutated) {
            this.success();
        } else {
            this.skip();
        }
    }

    private void prepareRuntimeShards() throws IOException {
        int copies = Math.max(1, getConfig().getRuntimeCopies());
        for (int i = 0; i < copies; i++) {
            runtimeShards.add(createRuntimeShard(i));
        }
    }

    private RuntimeShard createRuntimeShard(int index) throws IOException {
        SkidClassNode runtimeNode = new SkidClassNode(
                ClassHelper.create(InvokeDynamicRuntime.class).node,
                skidfuscator
        );
        final String templateName = runtimeNode.getName();
        final String ownerName = selectOwnerName(index);
        runtimeNode.node.name = ownerName;

        for (MethodNode method : runtimeNode.node.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(templateName)) {
                    call.owner = ownerName;
                } else if (insn instanceof FieldInsnNode field && field.owner.equals(templateName)) {
                    field.owner = ownerName;
                } else if (insn instanceof TypeInsnNode typeInsn && typeInsn.desc.equals(templateName)) {
                    typeInsn.desc = ownerName;
                } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type ldcType
                        && ldcType.getInternalName().equals(templateName)) {
                    ldc.cst = Type.getObjectType(ownerName);
                }
            }
        }

        runtimeOwnerNames.add(ownerName);
        skidfuscator.getClassRemapper().add(templateName, ownerName);
        skidfuscator.getClassSource().add(runtimeNode);
        skidfuscator.getJarContents()
                .getClassContents()
                .add(new JarClassData(ownerName + ".class", runtimeNode.toByteArray(), runtimeNode));

        Handle handle = new Handle(Opcodes.H_INVOKESTATIC, ownerName, BOOTSTRAP_NAME, BOOTSTRAP_DESC, false);
        return new RuntimeShard(ownerName, handle);
    }

    private String normalizeOwner(String candidate, int shardIndex) {
        String result = candidate == null ? "" : candidate.trim();
        if (result.isEmpty()) {
            result = "skid/rt/%s/%d";
        }

        if (!result.contains("%s")) {
            if (!result.endsWith("/")) {
                result += "/";
            }
            result += "%s";
        }
        if (!result.contains("%d")) {
            result += "_%d";
        }

        result = result.replace("%d", Integer.toString(shardIndex));
        result = result.replace("%s", RandomUtil.randomAlphabeticalString(6));
        result = result.replace('.', '/');
        while (result.contains("//")) {
            result = result.replace("//", "/");
        }
        if (result.endsWith("/")) {
            result += RandomUtil.randomAlphabeticalString(5);
        }
        return result;
    }

    private String selectOwnerName(int shardIndex) {
        final String candidate = getConfig().getRuntimeOwner();
        if (candidate != null && !candidate.trim().isEmpty()) {
            return normalizeOwner(candidate, shardIndex);
        }
        return deriveOwnerFromFlowHandler();
    }

    private String deriveOwnerFromFlowHandler() {
        final String directory = skidfuscator.getFlowHandlerDirectory();
        final String randomClass = RandomUtil.randomAlphabeticalString(16);
        if (directory == null || directory.isEmpty()) {
            return randomClass;
        }
        return directory + "/" + randomClass;
    }

    private Type maskArgumentType(Type type) {
        if (!getConfig().isEraseArgumentTypes()) {
            return type;
        }
        return maskReferenceType(type);
    }

    private Type maskReturnType(Type type) {
        if (!getConfig().isEraseReturnTypes()) {
            return type;
        }
        return maskReferenceType(type);
    }

    private Type maskReferenceType(Type type) {
        if (type == null) {
            return null;
        }
        if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
            return OBJECT_TYPE;
        }
        return type;
    }

    private boolean shouldTransform(double probability) {
        if (probability >= 1.0d) {
            return true;
        }
        if (probability <= 0.0d) {
            return false;
        }
        final double sample = (double) RandomUtil.nextInt(Integer.MAX_VALUE) / (double) Integer.MAX_VALUE;
        return sample <= probability;
    }

    private AbstractInsnNode transformMethodInsn(SkidMethodNode contextMethod, MethodInsnNode methodInsn) {
        final int opcode = methodInsn.getOpcode();
        if (opcode < Opcodes.INVOKEVIRTUAL || opcode > Opcodes.INVOKEINTERFACE) {
            return null;
        }
        if ("<init>".equals(methodInsn.name) || "<clinit>".equals(methodInsn.name)) {
            return null;
        }
        if (runtimeOwnerNames.contains(methodInsn.owner)) {
            return null;
        }

        final Type ownerType = Type.getObjectType(methodInsn.owner);
        final Type methodDesc = Type.getMethodType(methodInsn.desc);
        final Type[] originalArgs = methodDesc.getArgumentTypes();
        final Type returnType = methodDesc.getReturnType();

        Type[] indyArgs;
        if (opcode == Opcodes.INVOKESTATIC) {
            indyArgs = new Type[originalArgs.length];
            for (int i = 0; i < originalArgs.length; i++) {
                indyArgs[i] = maskArgumentType(originalArgs[i]);
            }
        } else {
            indyArgs = new Type[originalArgs.length + 1];
            indyArgs[0] = maskArgumentType(ownerType);
            for (int i = 0; i < originalArgs.length; i++) {
                indyArgs[i + 1] = maskArgumentType(originalArgs[i]);
            }
        }

        final String indyDesc = Type.getMethodDescriptor(maskReturnType(returnType), indyArgs);

        final EncryptedString owner = encrypt(methodInsn.owner);
        final EncryptedString name = encrypt(methodInsn.name);
        final EncryptedString desc = encrypt(methodInsn.desc);

        RuntimeShard shard = getShardForClass(contextMethod.getParent());
        Object[] args = new Object[]{
                opcode,
                owner.payload(),
                owner.key(),
                name.payload(),
                name.key(),
                desc.payload(),
                desc.key(),
                methodInsn.itf ? 1 : 0
        };

        return new InvokeDynamicInsnNode(randomInvokeName(), indyDesc, shard.bootstrapHandle, args);
    }

    private AbstractInsnNode transformFieldInsn(SkidClassNode owningClass, FieldInsnNode fieldInsn) {
        final int opcode = fieldInsn.getOpcode();
        if (opcode != Opcodes.GETSTATIC && opcode != Opcodes.GETFIELD
                && opcode != Opcodes.PUTSTATIC && opcode != Opcodes.PUTFIELD) {
            return null;
        }
        if (runtimeOwnerNames.contains(fieldInsn.owner)) {
            return null;
        }

        final Type ownerType = Type.getObjectType(fieldInsn.owner);
        final Type fieldType = Type.getType(fieldInsn.desc);

        final String indyDesc;
        if (opcode == Opcodes.GETSTATIC) {
            indyDesc = Type.getMethodDescriptor(maskReturnType(fieldType));
        } else if (opcode == Opcodes.GETFIELD) {
            indyDesc = Type.getMethodDescriptor(maskReturnType(fieldType), maskArgumentType(ownerType));
        } else if (opcode == Opcodes.PUTSTATIC) {
            indyDesc = Type.getMethodDescriptor(Type.VOID_TYPE, maskArgumentType(fieldType));
        } else {
            indyDesc = Type.getMethodDescriptor(
                    Type.VOID_TYPE,
                    maskArgumentType(ownerType),
                    maskArgumentType(fieldType)
            );
        }

        final EncryptedString owner = encrypt(fieldInsn.owner);
        final EncryptedString name = encrypt(fieldInsn.name);
        final EncryptedString desc = encrypt(fieldInsn.desc);

        RuntimeShard shard = getShardForClass(owningClass);
        Object[] args = new Object[]{
                opcode,
                owner.payload(),
                owner.key(),
                name.payload(),
                name.key(),
                desc.payload(),
                desc.key(),
                0
        };

        return new InvokeDynamicInsnNode(randomInvokeName(), indyDesc, shard.bootstrapHandle, args);
    }

    private static EncryptedString encrypt(String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        long key = RandomUtil.nextLong();
        long state = key ^ MIX;
        for (int i = 0; i < data.length; i++) {
            data[i] ^= (byte) state;
            state = Long.rotateLeft(state + MIX + i, 3);
        }
        return new EncryptedString(BASE64.encodeToString(data), key);
    }

    private String randomInvokeName() {
        int length = 3 + RandomUtil.nextInt(5);
        return RandomUtil.randomAlphabeticalString(length);
    }

    private RuntimeShard getShardForClass(SkidClassNode clazz) {
        if (runtimeShards.isEmpty()) {
            throw new IllegalStateException("InvokeDynamic runtime shards not initialized");
        }
        return classAssignments.computeIfAbsent(clazz.getName(), name -> {
            int idx = Math.floorMod(name.hashCode(), runtimeShards.size());
            return runtimeShards.get(idx);
        });
    }

    public Set<String> getRuntimeOwnerNames() {
        return Collections.unmodifiableSet(runtimeOwnerNames);
    }

    @RequiredArgsConstructor
    private static final class EncryptedString {
        private final String payload;
        private final long key;

        public String payload() {
            return payload;
        }

        public long key() {
            return key;
        }
    }

    @RequiredArgsConstructor
    private static final class RuntimeShard {
        private final String ownerName;
        private final Handle bootstrapHandle;
    }
}
