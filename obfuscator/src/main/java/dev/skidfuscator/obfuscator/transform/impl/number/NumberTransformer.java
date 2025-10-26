package dev.skidfuscator.obfuscator.transform.impl.number;

import com.typesafe.config.Config;
import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.event.EventPriority;
import dev.skidfuscator.obfuscator.event.annotation.Listen;
import dev.skidfuscator.obfuscator.event.impl.transform.method.PostMethodTransformEvent;
import dev.skidfuscator.obfuscator.predicate.factory.PredicateFlowGetter;
import dev.skidfuscator.obfuscator.skidasm.SkidClassNode;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.skidasm.builder.SkidMethodNodeBuilder;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidControlFlowGraph;
import dev.skidfuscator.obfuscator.skidasm.expr.SkidConstantExpr;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;
import dev.skidfuscator.obfuscator.transform.Transformer;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import dev.skidfuscator.runtime.NumericAesHelper;
import org.mapleir.asm.ClassHelper;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.code.CodeUnit;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.Stmt;
import org.mapleir.ir.code.expr.ArithmeticExpr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.mapleir.ir.code.expr.NewArrayExpr;
import org.mapleir.ir.code.expr.invoke.StaticInvocationExpr;
import org.mapleir.ir.code.stmt.ReturnStmt;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.topdank.byteengineer.commons.data.JarClassData;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NumberTransformer extends AbstractTransformer {
    private static final Set<Type> SUPPORTED_TYPES = new HashSet<>(Arrays.asList(
            Type.INT_TYPE,
            Type.SHORT_TYPE,
            Type.BYTE_TYPE,
            Type.CHAR_TYPE
    ));

    private final boolean staticUseAes;
    private final boolean staticUseXor;
    private final int staticXorRounds;
    private final boolean enableAesCaching;
    private final int dynamicDepth;

    private final Map<SkidClassNode, NumericAesState> aesStates = new HashMap<>();
    private final Map<SkidClassNode, RecursiveHelperPool> helperPools = new HashMap<>();

    public NumberTransformer(Skidfuscator skidfuscator) {
        this(skidfuscator, Collections.emptyList());
    }

    public NumberTransformer(Skidfuscator skidfuscator, List<Transformer> children) {
        super(skidfuscator, "Number Encryption", children);
        final Config config = skidfuscator.getTsConfig();
        this.staticUseAes = resolveBoolean(config, "numberEncryption.static.enableAes", true);
        this.staticUseXor = resolveBoolean(config, "numberEncryption.static.enableXor", true);
        this.staticXorRounds = Math.max(1, resolveInt(config, "numberEncryption.static.xorRounds", 2));
        this.enableAesCaching = resolveBoolean(config, "numberEncryption.enableAesCache", true);
        this.dynamicDepth = Math.max(2, resolveInt(config, "numberEncryption.dynamic.depth", 2));
    }

    @Listen(EventPriority.LOW)
    void handle(final PostMethodTransformEvent event) {
        final SkidMethodNode methodNode = event.getMethodNode();

        if (methodNode.isAbstract() || methodNode.isInit()) {
            this.skip();
            return;
        }

        if (methodNode.node.instructions.size() > 10000) {
            this.fail();
            return;
        }

        final SkidControlFlowGraph cfg = methodNode.getCfg();
        if (cfg == null) {
            this.fail();
            return;
        }

        final PredicateFlowGetter predicateGetter = resolveGetter(methodNode);
        final SkidClassNode parentNode = methodNode.getParent();

        for (BasicBlock vertex : new HashSet<>(cfg.vertices())) {
            if (!(vertex instanceof SkidBlock skidBlock)) {
                continue;
            }

            if (vertex.isFlagSet(SkidBlock.FLAG_NO_OPAQUE)) {
                continue;
            }

            if (methodNode.isClinit() && this.heuristicSizeSkip(methodNode, 8.f)) {
                continue;
            }

            for (Stmt stmt : new HashSet<>(vertex)) {
                for (Expr expr : stmt.enumerateOnlyChildren()) {
                    if (!(expr instanceof SkidConstantExpr constantExpr)) {
                        continue;
                    }

                    final ConstantExpr literal = (ConstantExpr) constantExpr;
                    if (!SUPPORTED_TYPES.contains(literal.getType())) {
                        continue;
                    }

                    final int targetValue = ((Number) literal.getConstant()).intValue();
                    final int predicateValue = methodNode.getBlockPredicate(skidBlock);
                    final Expr predicateExpr = getPredicateExpr(methodNode, skidBlock);
                    final CodeUnit parent = constantExpr.getParent();

                    if (parent == null) {
                        continue;
                    }

                    final boolean staticContext = methodNode.isClinit() || methodNode.isStatic();
                    final Expr replacement;
                    if (staticContext) {
                        replacement = buildStaticExpression(
                                parentNode,
                                predicateValue,
                                predicateExpr,
                                targetValue
                        );
                    } else {
                        replacement = buildDynamicExpression(
                                parentNode,
                                predicateValue,
                                predicateExpr,
                                targetValue
                        );
                    }

                    parent.overwrite(constantExpr, replacement);
                }
            }
        }

        this.success();
    }

    private PredicateFlowGetter resolveGetter(final SkidMethodNode methodNode) {
        if (canUseFlowGetter(methodNode)) {
            return methodNode.getFlowPredicate().getGetter();
        }

        return vertex -> {
            if (!(vertex instanceof SkidBlock block)) {
                throw new IllegalStateException("Expected SkidBlock vertex");
            }
            return new ConstantExpr(methodNode.getBlockPredicate(block), Type.INT_TYPE);
        };
    }

    private boolean canUseFlowGetter(final SkidMethodNode methodNode) {
        return skidfuscator.getSession().isSkidFlowObfuscationEnabled()
                && methodNode.getFlowPredicate() != null
                && methodNode.getFlowPredicate().getGetter() != null;
    }

    private Expr getPredicateExpr(final SkidMethodNode methodNode, final SkidBlock block) {
        if (canUseFlowGetter(methodNode)) {
            final Expr expr = methodNode.getFlowPredicate().getGetter().get(block);
            if (expr != null) {
                return expr;
            }
        }
        return new ConstantExpr(methodNode.getBlockPredicate(block), Type.INT_TYPE);
    }

    private Expr buildStaticExpression(final SkidClassNode parent,
                                       final int predicateValue,
                                       final Expr predicateExpr,
                                       final int targetValue) {
        Expr expr;
        if (staticUseAes) {
            expr = getAesState(parent).createDecodeExpr(targetValue, predicateValue, predicateExpr);
        } else {
            expr = new ConstantExpr(targetValue, Type.INT_TYPE);
        }

        int simulated = targetValue;

        if (staticUseXor) {
            for (int i = 0; i < staticXorRounds; i++) {
                final int mix = nonZeroInt();
                expr = new ArithmeticExpr(
                        new ConstantExpr(mix, Type.INT_TYPE),
                        expr,
                        ArithmeticExpr.Operator.XOR
                );
                simulated ^= mix;
            }
        }

        final int correction = targetValue - simulated;
        if (correction != 0) {
            expr = new ArithmeticExpr(
                    new ConstantExpr(correction, Type.INT_TYPE),
                    expr,
                    ArithmeticExpr.Operator.ADD
            );
        }

        return expr;
    }

    private Expr buildDynamicExpression(final SkidClassNode parent,
                                        final int predicateValue,
                                        final Expr predicateExpr,
                                        final int targetValue) {
        final RecursiveHelperPool pool = helperPools.computeIfAbsent(parent, RecursiveHelperPool::new);
        final HelperDescriptor helper = pool.ensureHelper();

        Expr expr = predicateExpr;
        int simulated = predicateValue;

        for (int i = 0; i < dynamicDepth; i++) {
            final int seed = RandomUtil.nextInt();
            expr = new StaticInvocationExpr(
                    new Expr[]{
                            new ConstantExpr(seed, Type.INT_TYPE),
                            expr
                    },
                    parent.getName(),
                    helper.name(),
                    helper.desc()
            );
            simulated = helper.apply(seed, simulated);
        }

        final int xorConst = nonZeroInt();
        expr = new ArithmeticExpr(
                new ConstantExpr(xorConst, Type.INT_TYPE),
                expr,
                ArithmeticExpr.Operator.XOR
        );
        simulated ^= xorConst;

        final int correction = targetValue - simulated;
        if (correction != 0) {
            expr = new ArithmeticExpr(
                    new ConstantExpr(correction, Type.INT_TYPE),
                    expr,
                    ArithmeticExpr.Operator.ADD
            );
        }

        return expr;
    }

    private NumericAesState getAesState(final SkidClassNode node) {
        return aesStates.computeIfAbsent(node, entry -> new NumericAesState(entry, enableAesCaching));
    }

    private static int nonZeroInt() {
        int value;
        do {
            value = (int) RandomUtil.nextLong();
        } while (value == 0);
        return value;
    }

    private static int resolveInt(final Config config, final String path, final int fallback) {
        if (config != null && config.hasPath(path)) {
            try {
                return config.getInt(path);
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    private static boolean resolveBoolean(final Config config, final String path, final boolean fallback) {
        if (config != null && config.hasPath(path)) {
            try {
                return config.getBoolean(path);
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    private static final class RecursiveHelperPool {
        private final SkidClassNode owner;
        private HelperDescriptor helper;

        private RecursiveHelperPool(final SkidClassNode owner) {
            this.owner = owner;
        }

        private HelperDescriptor ensureHelper() {
            if (helper != null) {
                return helper;
            }

            final String name = RandomUtil.randomAlphabeticalString(10);
            final SkidMethodNode helperNode = owner.createMethod()
                    .access(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)
                    .name(name)
                    .desc("(II)I")
                    .phantom(true)
                    .build();

            final MethodVisitor mv = helperNode.node;
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ILOAD, 1); // value
            mv.visitVarInsn(Opcodes.ILOAD, 0); // seed
            mv.visitInsn(Opcodes.IXOR);
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitIntInsn(Opcodes.BIPUSH, 7);
            mv.visitInsn(Opcodes.IAND);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IADD);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "rotateLeft", "(II)I", false);
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitLdcInsn(0x45d9f3b);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitInsn(Opcodes.IADD);
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "reverse", "(I)I", false);
            mv.visitInsn(Opcodes.IXOR);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(4, 2);
            mv.visitEnd();

            helperNode.recomputeCfg();
            helper = new HelperDescriptor(name);
            return helper;
        }
    }

    private static final class HelperDescriptor {
        private final String name;

        private HelperDescriptor(String name) {
            this.name = name;
        }

        private String name() {
            return name;
        }

        private String desc() {
            return "(II)I";
        }

        private int apply(final int seed, final int value) {
            final int rotated = Integer.rotateLeft(value ^ seed, (seed & 7) + 1);
            final int spread = rotated + (seed * 0x45d9f3b);
            return spread ^ Integer.reverse(seed);
        }
    }

    private static final class NumericAesState {
        private static final int IV_LENGTH = 12;

        private final SkidClassNode owner;
        private final boolean cacheEnabled;
        private final byte[] rawKey;
        private final byte[] mask;
        private final ArrayHandle maskedKeyHandle;
        private final ArrayHandle maskHandle;
        private final Map<Long, Payload> payloadCache = new HashMap<>();
        private int nextSlot = 1;
        private String runtimeOwnerName;

        private NumericAesState(final SkidClassNode owner, final boolean cacheEnabled) {
            this.owner = owner;
            this.cacheEnabled = cacheEnabled;
            this.rawKey = RandomUtil.randomBytes(16);
            this.mask = RandomUtil.randomBytes(16);
            this.maskedKeyHandle = createByteArrayHandle(owner, maskKey(rawKey, mask));
            this.maskHandle = createByteArrayHandle(owner, mask.clone());
        }

        private Expr createDecodeExpr(final int value,
                                      final int predicateValue,
                                      final Expr predicateExpr) {
            ensureRuntimeInjected();

            final Payload payload = cacheEnabled
                    ? payloadCache.computeIfAbsent(
                    buildKey(value, predicateValue),
                    ignored -> createPayload(value, predicateValue)
            )
                    : createPayload(value, predicateValue);

            final ArrayHandle payloadHandle = cacheEnabled
                    ? payload.ensureHandle(owner)
                    : createByteArrayHandle(owner, payload.data());

            return new StaticInvocationExpr(
                    new Expr[]{
                            payloadHandle.invoke(),
                            maskedKeyHandle.invoke(),
                            maskHandle.invoke(),
                            predicateExpr
                    },
                    runtimeOwnerName,
                    "decrypt",
                    "([B[B[BI)I"
            );
        }

        private Payload createPayload(final int value, final int predicateValue) {
            try {
                final byte[] iv = RandomUtil.randomBytes(IV_LENGTH);
                final byte[] derivedKey = deriveKey(rawKey, predicateValue);
                final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.ENCRYPT_MODE,
                        new SecretKeySpec(derivedKey, "AES"),
                        new GCMParameterSpec(128, iv)
                );

                final byte[] encrypted = cipher.doFinal(intToBytes(value));
                final int slot = nextSlot++;
                final byte[] payload = new byte[4 + iv.length + encrypted.length];
                writeInt(payload, 0, slot);
                System.arraycopy(iv, 0, payload, 4, iv.length);
                System.arraycopy(encrypted, 0, payload, 4 + iv.length, encrypted.length);
                return new Payload(payload);
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException("Failed to encode numeric constant", ex);
            }
        }

        private void ensureRuntimeInjected() {
            if (runtimeOwnerName != null) {
                return;
            }

            final org.mapleir.asm.ClassNode template = ClassHelper.create(NumericAesHelper.class);
            final String templateName = template.getName();
            runtimeOwnerName = owner.getName() + "$" + RandomUtil.randomAlphabeticalString(8);
            template.node.name = runtimeOwnerName;

            for (MethodNode method : template.node.methods) {
                if (method.instructions == null) {
                    continue;
                }
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode call && call.owner.equals(templateName)) {
                        call.owner = runtimeOwnerName;
                    } else if (insn instanceof FieldInsnNode field && field.owner.equals(templateName)) {
                        field.owner = runtimeOwnerName;
                    } else if (insn instanceof TypeInsnNode typeInsn && templateName.equals(typeInsn.desc)) {
                        typeInsn.desc = runtimeOwnerName;
                    } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type
                            && type.getInternalName().equals(templateName)) {
                        ldc.cst = Type.getObjectType(runtimeOwnerName);
                    }
                }
            }

            final SkidClassNode runtimeNode = new SkidClassNode(template.node, owner.getSkidfuscator());
            owner.getSkidfuscator().getClassSource().add(runtimeNode);
            owner.getSkidfuscator()
                    .getJarContents()
                    .getClassContents()
                    .add(new JarClassData(
                            runtimeOwnerName + ".class",
                            runtimeNode.toByteArray(),
                            runtimeNode
                    ));
        }

        private static long buildKey(final int value, final int predicate) {
            return (((long) predicate) << 32) ^ (value & 0xffffffffL);
        }

        private static byte[] maskKey(final byte[] key, final byte[] mask) {
            final byte[] masked = new byte[key.length];
            for (int i = 0; i < key.length; i++) {
                masked[i] = (byte) (key[i] ^ mask[i % mask.length]);
            }
            return masked;
        }

        private static byte[] deriveKey(final byte[] baseKey, final int predicate) {
            final byte[] derived = new byte[baseKey.length];
            int state = Integer.rotateLeft(predicate ^ 0x9E3779B9, 5) + 0x7f4a7c15;
            for (int i = 0; i < baseKey.length; i++) {
                final int dynamic = (predicate >>> ((i & 3) * 8)) & 0xFF;
                state = Integer.rotateLeft(state + dynamic + (i * 0x45d9f3b), 3);
                derived[i] = (byte) ((baseKey[i] & 0xFF) ^ dynamic ^ (state & 0xFF));
            }
            return derived;
        }

        private static byte[] intToBytes(final int value) {
            return new byte[]{
                    (byte) (value >>> 24),
                    (byte) (value >>> 16),
                    (byte) (value >>> 8),
                    (byte) value
            };
        }

        private static void writeInt(final byte[] array, final int offset, final int value) {
            array[offset] = (byte) (value >>> 24);
            array[offset + 1] = (byte) (value >>> 16);
            array[offset + 2] = (byte) (value >>> 8);
            array[offset + 3] = (byte) value;
        }
    }

    private static final class ArrayHandle {
        private final SkidClassNode owner;
        private final String methodName;
        private final String methodDesc;

        private ArrayHandle(SkidClassNode owner, String methodName, String methodDesc) {
            this.owner = owner;
            this.methodName = methodName;
            this.methodDesc = methodDesc;
        }

        private Expr invoke() {
            return new StaticInvocationExpr(
                    owner.isInterface() ? StaticInvocationExpr.CallType.INTERFACE : StaticInvocationExpr.CallType.STATIC,
                    new Expr[0],
                    owner.getName(),
                    methodName,
                    methodDesc
            );
        }
    }

    private static final class Payload {
        private final byte[] data;
        private ArrayHandle handle;

        private Payload(final byte[] data) {
            this.data = data;
        }

        private byte[] data() {
            return data;
        }

        private ArrayHandle ensureHandle(final SkidClassNode owner) {
            if (handle == null) {
                handle = createByteArrayHandle(owner, data);
            }
            return handle;
        }
    }

    private static ArrayHandle createByteArrayHandle(final SkidClassNode node, final byte[] data) {
        final SkidMethodNode injector = new SkidMethodNodeBuilder(node.getSkidfuscator(), node)
                .access(Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)
                .name(RandomUtil.randomAlphabeticalString(15))
                .desc("()[B")
                .phantom(true)
                .build();

        final Expr[] constants = new Expr[data.length];
        for (int i = 0; i < data.length; i++) {
            constants[i] = new ConstantExpr(data[i], Type.BYTE_TYPE);
        }

        final NewArrayExpr arrayExpr = new NewArrayExpr(
                new Expr[]{new ConstantExpr(data.length, Type.INT_TYPE)},
                Type.getType(byte[].class),
                constants
        );

        injector.getCfg()
                .getEntry()
                .add(new ReturnStmt(Type.getType(byte[].class), arrayExpr));

        return new ArrayHandle(node, injector.getName(), injector.getDesc());
    }
}
