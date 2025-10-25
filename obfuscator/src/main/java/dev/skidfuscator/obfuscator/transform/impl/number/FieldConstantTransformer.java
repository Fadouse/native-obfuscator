package dev.skidfuscator.obfuscator.transform.impl.number;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.event.EventPriority;
import dev.skidfuscator.obfuscator.event.annotation.Listen;
import dev.skidfuscator.obfuscator.event.impl.transform.clazz.InitClassTransformEvent;
import dev.skidfuscator.obfuscator.skidasm.SkidClassNode;
import dev.skidfuscator.obfuscator.skidasm.SkidFieldNode;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;
import dev.skidfuscator.obfuscator.util.RandomUtil;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.expr.ArithmeticExpr;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.mapleir.ir.code.expr.invoke.InitialisedObjectExpr;
import org.mapleir.ir.code.expr.invoke.InvocationExpr;
import org.mapleir.ir.code.expr.invoke.VirtualInvocationExpr;
import org.mapleir.ir.code.stmt.FieldStoreStmt;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Rewrites static final integer-like fields so their values are computed inside
 * the class initializer rather than exposed as ConstantValue attributes.
 * Each constant is XOR-obscured against the deterministic output of a
 * java.util.Random instance that is seeded with a private long literal.
 */
public class FieldConstantTransformer extends AbstractTransformer {

    public FieldConstantTransformer(Skidfuscator skidfuscator) {
        super(skidfuscator, "Field Constant Encryption");
    }

    @Listen(EventPriority.LOW)
    void handle(final InitClassTransformEvent event) {
        final SkidClassNode classNode = event.getClassNode();

        if (classNode.isInterface() || classNode.isAnnotation() || classNode.isEnum()) {
            this.skip();
            return;
        }

        final List<SkidFieldNode> eligible = findEligibleFields(classNode);
        if (eligible.isEmpty()) {
            this.skip();
            return;
        }

        final SkidMethodNode clinit = classNode.getClassInit();
        final BasicBlock entryBlock = clinit.getEntryBlock();

        int insertionIndex = 0;
        for (SkidFieldNode fieldNode : eligible) {
            final Expr encodedExpr = buildInitializationExpr(fieldNode);
            if (encodedExpr == null) {
                continue;
            }

            fieldNode.node.value = null;

            final FieldStoreStmt storeStmt = new FieldStoreStmt(
                    null,
                    encodedExpr,
                    classNode.getName(),
                    fieldNode.getName(),
                    fieldNode.getDesc(),
                    true
            );

            entryBlock.add(insertionIndex++, storeStmt);
        }

        if (insertionIndex == 0) {
            this.skip();
        } else {
            this.success();
        }
    }

    private List<SkidFieldNode> findEligibleFields(final SkidClassNode classNode) {
        final List<SkidFieldNode> result = new ArrayList<>();
        for (org.mapleir.asm.FieldNode field : classNode.getFields()) {
            if (!(field instanceof SkidFieldNode skidFieldNode)) {
                continue;
            }

            if ((skidFieldNode.node.access & Opcodes.ACC_STATIC) == 0) {
                continue;
            }
            if ((skidFieldNode.node.access & Opcodes.ACC_FINAL) == 0) {
                continue;
            }
            if ((skidFieldNode.node.access & Opcodes.ACC_ENUM) != 0) {
                continue;
            }
            if (skidFieldNode.node.value == null) {
                continue;
            }

            final Type type = Type.getType(skidFieldNode.getDesc());
            if (!supports(type)) {
                continue;
            }

            result.add(skidFieldNode);
        }
        return result;
    }

    private boolean supports(final Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> true;
            default -> false;
        };
    }

    private Expr buildInitializationExpr(final SkidFieldNode fieldNode) {
        final Object value = fieldNode.node.value;
        if (value == null) {
            return null;
        }

        final Integer plainValue = asIntConstant(value);
        if (plainValue == null) {
            return null;
        }

        final long seed = RandomUtil.nextLong();
        final Random deterministic = new Random(seed);
        final int mask = deterministic.nextInt();
        final int encoded = plainValue ^ mask;

        final Expr maskExpr = new VirtualInvocationExpr(
                InvocationExpr.CallType.VIRTUAL,
                new Expr[]{
                        new InitialisedObjectExpr(
                                "java/util/Random",
                                "(J)V",
                                new Expr[]{new ConstantExpr(seed, Type.LONG_TYPE)}
                        )
                },
                "java/util/Random",
                "nextInt",
                "()I"
        );

        return new ArithmeticExpr(
                new ConstantExpr(encoded, Type.INT_TYPE),
                maskExpr,
                ArithmeticExpr.Operator.XOR
        );
    }

    private Integer asIntConstant(final Object value) {
        if (value instanceof Integer integer) {
            return integer;
        } else if (value instanceof Short aShort) {
            return (int) aShort.shortValue();
        } else if (value instanceof Byte aByte) {
            return (int) aByte.byteValue();
        } else if (value instanceof Character character) {
            return (int) character.charValue();
        } else if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        return null;
    }
}
