package by.radioegor146.instructions;

import by.radioegor146.FastRandom;
import by.radioegor146.MethodContext;
import by.radioegor146.Util;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;

public class InsnHandler extends GenericInstructionHandler<InsnNode> {

    @Override
    protected void process(MethodContext context, InsnNode node) {
        boolean virtualizationEnabled = context.protectionConfig.isVirtualizationEnabled();
        boolean constantObfuscationEnabled = context.protectionConfig.isConstantObfuscationEnabled();

        switch (node.getOpcode()) {
            case Opcodes.IALOAD: {
                // Rewrite enum-switch mapping pattern:
                // When prior sequence was GETSTATIC $SwitchMap...; ALOAD enum; INVOKEVIRTUAL ordinal()I;
                // then IALOAD; we can avoid reading the mapping array and compute mapping value directly
                // as (ordinal + 1). This fixes inconsistent mapping contents across JVMs
                // while preserving stack semantics and exception behavior.
                if (context.enumSwitchMapOnStack && context.lastWasEnumOrdinal) {
                    instructionName = null;
                    // Preserve NPE behavior on null array reference as in IALOAD.
                    // Note: use string literals here to avoid relying on snippet #VARS expansion.
                    context.output.append(String.format(
                            "if (cstack%s.l == nullptr) utils::throw_re(env, \"java/lang/NullPointerException\", \"IALOAD npe\", %d); else { cstack%s.i = (jint) (cstack%s.i + 1); } %s",
                            props.get("stackindexm2"),
                            context.line,
                            props.get("stackindexm2"),
                            props.get("stackindexm1"),
                            props.get("trycatchhandler")));
                    // Reset flags after rewriting one mapping load
                    context.enumSwitchMapOnStack = false;
                    context.lastWasEnumOrdinal = false;
                    break;
                }
                // fall through to default snippets
                break;
            }
            case Opcodes.IADD: {
                if (virtualizationEnabled) {
                    instructionName = null;
                    long seed = FastRandom.nextLong();
                    if (FastRandom.nextBoolean()) {
                        long junkSeed = FastRandom.nextLong();
                        int junkIdx = FastRandom.nextBoolean() ? 1 : 2;
                        context.output.append(String.format(
                                "native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_JUNK%d, 0, 0, %dLL);%s",
                                junkIdx, junkSeed, props.get("trycatchhandler")));
                    }
                    context.output.append(String.format(
                            "cstack%s.i = (jint)native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_ADD, cstack%s.i, cstack%s.i, %dLL);%s",
                            props.get("stackindexm2"), props.get("stackindexm2"), props.get("stackindexm1"), seed,
                            props.get("trycatchhandler")));
                } else if (constantObfuscationEnabled) {
                    instructionName = null;
                    emitEncodedIntResult(context, props.get("stackindexm2"),
                            String.format("(cstack%s.i + cstack%s.i)", props.get("stackindexm2"), props.get("stackindexm1")));
                }
                break;
            }
            case Opcodes.ISUB: {
                if (virtualizationEnabled) {
                    instructionName = null;
                    long seed = FastRandom.nextLong();
                    if (FastRandom.nextBoolean()) {
                        long junkSeed = FastRandom.nextLong();
                        int junkIdx = FastRandom.nextBoolean() ? 1 : 2;
                        context.output.append(String.format(
                                "native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_JUNK%d, 0, 0, %dLL);%s",
                                junkIdx, junkSeed, props.get("trycatchhandler")));
                    }
                    context.output.append(String.format(
                            "cstack%s.i = (jint)native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_SUB, cstack%s.i, cstack%s.i, %dLL);%s",
                            props.get("stackindexm2"), props.get("stackindexm2"), props.get("stackindexm1"), seed,
                            props.get("trycatchhandler")));
                } else if (constantObfuscationEnabled) {
                    instructionName = null;
                    emitEncodedIntResult(context, props.get("stackindexm2"),
                            String.format("(cstack%s.i - cstack%s.i)", props.get("stackindexm2"), props.get("stackindexm1")));
                }
                break;
            }
            case Opcodes.IMUL: {
                if (virtualizationEnabled) {
                    instructionName = null;
                    long seed = FastRandom.nextLong();
                    if (FastRandom.nextBoolean()) {
                        long junkSeed = FastRandom.nextLong();
                        int junkIdx = FastRandom.nextBoolean() ? 1 : 2;
                        context.output.append(String.format(
                                "native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_JUNK%d, 0, 0, %dLL);%s",
                                junkIdx, junkSeed, props.get("trycatchhandler")));
                    }
                    context.output.append(String.format(
                            "cstack%s.i = (jint)native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_MUL, cstack%s.i, cstack%s.i, %dLL);%s",
                            props.get("stackindexm2"), props.get("stackindexm2"), props.get("stackindexm1"), seed,
                            props.get("trycatchhandler")));
                } else if (constantObfuscationEnabled) {
                    instructionName = null;
                    emitEncodedIntResult(context, props.get("stackindexm2"),
                            String.format("(cstack%s.i * cstack%s.i)", props.get("stackindexm2"), props.get("stackindexm1")));
                }
                break;
            }
            case Opcodes.IDIV: {
                if (virtualizationEnabled) {
                    instructionName = null;
                    long seed = FastRandom.nextLong();
                    if (FastRandom.nextBoolean()) {
                        long junkSeed = FastRandom.nextLong();
                        int junkIdx = FastRandom.nextBoolean() ? 1 : 2;
                        context.output.append(String.format(
                                "native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_JUNK%d, 0, 0, %dLL);%s",
                                junkIdx, junkSeed, props.get("trycatchhandler")));
                    }
                    context.output.append(String.format(
                            "cstack%s.i = (jint)native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_DIV, cstack%s.i, cstack%s.i, %dLL);%s",
                            props.get("stackindexm2"), props.get("stackindexm2"), props.get("stackindexm1"), seed,
                            props.get("trycatchhandler")));
                } else if (constantObfuscationEnabled) {
                    instructionName = null;
                    emitEncodedIntDiv(context);
                }
                break;
            }
            case Opcodes.IAND: {
                if (virtualizationEnabled) {
                    instructionName = null;
                    long seed = FastRandom.nextLong();
                    if (FastRandom.nextBoolean()) {
                        long junkSeed = FastRandom.nextLong();
                        int junkIdx = FastRandom.nextBoolean() ? 1 : 2;
                        context.output.append(String.format(
                                "native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_JUNK%d, 0, 0, %dLL);%s",
                                junkIdx, junkSeed, props.get("trycatchhandler")));
                    }
                    context.output.append(String.format(
                            "cstack%s.i = (jint)native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_AND, cstack%s.i, cstack%s.i, %dLL);%s",
                            props.get("stackindexm2"), props.get("stackindexm2"), props.get("stackindexm1"), seed,
                            props.get("trycatchhandler")));
                } else if (constantObfuscationEnabled) {
                    instructionName = null;
                    emitEncodedIntResult(context, props.get("stackindexm2"),
                            String.format("(cstack%s.i & cstack%s.i)", props.get("stackindexm2"), props.get("stackindexm1")));
                }
                break;
            }
            case Opcodes.IOR: {
                if (virtualizationEnabled) {
                    instructionName = null;
                    long seed = FastRandom.nextLong();
                    if (FastRandom.nextBoolean()) {
                        long junkSeed = FastRandom.nextLong();
                        int junkIdx = FastRandom.nextBoolean() ? 1 : 2;
                        context.output.append(String.format(
                                "native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_JUNK%d, 0, 0, %dLL);%s",
                                junkIdx, junkSeed, props.get("trycatchhandler")));
                    }
                    context.output.append(String.format(
                            "cstack%s.i = (jint)native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_OR, cstack%s.i, cstack%s.i, %dLL);%s",
                            props.get("stackindexm2"), props.get("stackindexm2"), props.get("stackindexm1"), seed,
                            props.get("trycatchhandler")));
                } else if (constantObfuscationEnabled) {
                    instructionName = null;
                    emitEncodedIntResult(context, props.get("stackindexm2"),
                            String.format("(cstack%s.i | cstack%s.i)", props.get("stackindexm2"), props.get("stackindexm1")));
                }
                break;
            }
            case Opcodes.IXOR: {
                if (virtualizationEnabled) {
                    instructionName = null;
                    long seed = FastRandom.nextLong();
                    if (FastRandom.nextBoolean()) {
                        long junkSeed = FastRandom.nextLong();
                        int junkIdx = FastRandom.nextBoolean() ? 1 : 2;
                        context.output.append(String.format(
                                "native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_JUNK%d, 0, 0, %dLL);%s",
                                junkIdx, junkSeed, props.get("trycatchhandler")));
                    }
                    context.output.append(String.format(
                            "cstack%s.i = (jint)native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_XOR, cstack%s.i, cstack%s.i, %dLL);%s",
                            props.get("stackindexm2"), props.get("stackindexm2"), props.get("stackindexm1"), seed,
                            props.get("trycatchhandler")));
                } else if (constantObfuscationEnabled) {
                    instructionName = null;
                    emitEncodedIntResult(context, props.get("stackindexm2"),
                            String.format("(cstack%s.i ^ cstack%s.i)", props.get("stackindexm2"), props.get("stackindexm1")));
                }
                break;
            }
            case Opcodes.ISHL: {
                if (virtualizationEnabled) {
                    instructionName = null;
                    long seed = FastRandom.nextLong();
                    context.output.append(String.format(
                            "cstack%s.i = (jint)native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_SHL, cstack%s.i, cstack%s.i, %dLL);%s",
                            props.get("stackindexm2"), props.get("stackindexm2"), props.get("stackindexm1"), seed,
                            props.get("trycatchhandler")));
                } else if (constantObfuscationEnabled) {
                    instructionName = null;
                    emitEncodedIntResult(context, props.get("stackindexm2"),
                            String.format("(cstack%s.i << (0x1f & cstack%s.i))", props.get("stackindexm2"), props.get("stackindexm1")));
                }
                break;
            }
            case Opcodes.ISHR: {
                if (virtualizationEnabled) {
                    instructionName = null;
                    long seed = FastRandom.nextLong();
                    context.output.append(String.format(
                            "cstack%s.i = (jint)native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_SHR, cstack%s.i, cstack%s.i, %dLL);%s",
                            props.get("stackindexm2"), props.get("stackindexm2"), props.get("stackindexm1"), seed,
                            props.get("trycatchhandler")));
                } else if (constantObfuscationEnabled) {
                    instructionName = null;
                    emitEncodedIntResult(context, props.get("stackindexm2"),
                            String.format("(cstack%s.i >> (0x1f & cstack%s.i))", props.get("stackindexm2"), props.get("stackindexm1")));
                }
                break;
            }
            case Opcodes.IUSHR: {
                if (virtualizationEnabled) {
                    instructionName = null;
                    long seed = FastRandom.nextLong();
                    context.output.append(String.format(
                            "cstack%s.i = (jint)native_jvm::vm::run_arith_vm(env, native_jvm::vm::OP_USHR, cstack%s.i, cstack%s.i, %dLL);%s",
                            props.get("stackindexm2"), props.get("stackindexm2"), props.get("stackindexm1"), seed,
                            props.get("trycatchhandler")));
                } else if (constantObfuscationEnabled) {
                    instructionName = null;
                    emitEncodedIntResult(context, props.get("stackindexm2"),
                            String.format("((jint)(((uint32_t)cstack%s.i) >> (((uint32_t)cstack%s.i) & 0x1f)))",
                                    props.get("stackindexm2"), props.get("stackindexm1")));
                }
                break;
            }
            case Opcodes.I2B: {
                if (virtualizationEnabled) {
                    instructionName = null;
                    long seed = FastRandom.nextLong();
                    context.output.append(String.format(
                            "cstack%s.i = (jint)native_jvm::vm::run_unary_vm(env, native_jvm::vm::OP_I2B, cstack%s.i, %dLL);%s",
                            props.get("stackindexm1"), props.get("stackindexm1"), seed, props.get("trycatchhandler")));
                } else if (constantObfuscationEnabled) {
                    instructionName = null;
                    emitEncodedIntResult(context, props.get("stackindexm1"),
                            String.format("((jint)(jbyte)cstack%s.i)", props.get("stackindexm1")));
                }
                break;
            }
            case Opcodes.I2C: {
                if (virtualizationEnabled) {
                    instructionName = null;
                    long seed = FastRandom.nextLong();
                    context.output.append(String.format(
                            "cstack%s.i = (jint)native_jvm::vm::run_unary_vm(env, native_jvm::vm::OP_I2C, cstack%s.i, %dLL);%s",
                            props.get("stackindexm1"), props.get("stackindexm1"), seed, props.get("trycatchhandler")));
                } else if (constantObfuscationEnabled) {
                    instructionName = null;
                    emitEncodedIntResult(context, props.get("stackindexm1"),
                            String.format("((jint)(jchar)cstack%s.i)", props.get("stackindexm1")));
                }
                break;
            }
            case Opcodes.I2S: {
                if (virtualizationEnabled) {
                    instructionName = null;
                    long seed = FastRandom.nextLong();
                    context.output.append(String.format(
                            "cstack%s.i = (jint)native_jvm::vm::run_unary_vm(env, native_jvm::vm::OP_I2S, cstack%s.i, %dLL);%s",
                            props.get("stackindexm1"), props.get("stackindexm1"), seed, props.get("trycatchhandler")));
                } else if (constantObfuscationEnabled) {
                    instructionName = null;
                    emitEncodedIntResult(context, props.get("stackindexm1"),
                            String.format("((jint)(jshort)cstack%s.i)", props.get("stackindexm1")));
                }
                break;
            }
            case Opcodes.I2L: {
                if (virtualizationEnabled) {
                    instructionName = null;
                    long seed = FastRandom.nextLong();
                    context.output.append(String.format(
                            "cstack%s.j = native_jvm::vm::run_unary_vm(env, native_jvm::vm::OP_I2L, cstack%s.i, %dLL);%s",
                            props.get("stackindexm1"), props.get("stackindexm1"), seed, props.get("trycatchhandler")));
                } else if (constantObfuscationEnabled) {
                    instructionName = null;
                    emitEncodedLongResult(context, props.get("stackindexm1"),
                            String.format("((jlong)cstack%s.i)", props.get("stackindexm1")));
                }
                break;
            }
            case Opcodes.INEG: {
                if (virtualizationEnabled) {
                    instructionName = null;
                    long seed = FastRandom.nextLong();
                    context.output.append(String.format(
                            "cstack%s.i = (jint)native_jvm::vm::run_unary_vm(env, native_jvm::vm::OP_NEG, cstack%s.i, %dLL);%s",
                            props.get("stackindexm1"), props.get("stackindexm1"), seed, props.get("trycatchhandler")));
                } else if (constantObfuscationEnabled) {
                    instructionName = null;
                    emitEncodedIntResult(context, props.get("stackindexm1"),
                            String.format("(-cstack%s.i)", props.get("stackindexm1")));
                }
                break;
            }
            case Opcodes.DUP_X1: {
                // Use snippets instead of VM to maintain consistent stack state
                // instructionName will be handled by snippets
                break;
            }
            case Opcodes.DUP_X2: {
                // Use snippets instead of VM to maintain consistent stack state
                // instructionName will be handled by snippets
                break;
            }
            case Opcodes.DUP2: {
                // Use snippets instead of VM to maintain consistent stack state
                // instructionName will be handled by snippets
                break;
            }
            case Opcodes.DUP2_X1: {
                // Use snippets instead of VM to maintain consistent stack state
                // instructionName will be handled by snippets
                break;
            }
            case Opcodes.DUP2_X2: {
                // Use snippets instead of VM to maintain consistent stack state
                // instructionName will be handled by snippets
                break;
            }
            case Opcodes.ATHROW: {
                // Use snippets instead of VM to maintain consistent stack state
                // instructionName will be handled by snippets
                break;
            }
            default:
                // handled via snippets
                break;
        }
    }

    private void emitEncodedIntResult(MethodContext context, String targetIndex, String resultExpression) {
        int key = FastRandom.nextInt();
        int seed = FastRandom.nextInt();
        String keyLiteral = LdcHandler.getIntString(key);
        String seedLiteral = LdcHandler.getIntString(seed);
        context.output.append(String.format(
                "{ jint __ngen_res = %s; jint __ngen_mix = native_jvm::utils::decode_int(0, %s, %d, %d, %s); " +
                        "jint __ngen_enc = __ngen_res ^ __ngen_mix; cstack%s.i = native_jvm::utils::decode_int(__ngen_enc, %s, %d, %d, %s); }%s",
                resultExpression,
                keyLiteral, context.methodIndex, context.classIndex, seedLiteral,
                targetIndex,
                keyLiteral, context.methodIndex, context.classIndex, seedLiteral,
                props.get("trycatchhandler")));
    }

    private void emitEncodedLongResult(MethodContext context, String targetIndex, String resultExpression) {
        long key = FastRandom.nextLong();
        int seed = FastRandom.nextInt();
        String keyLiteral = LdcHandler.getLongValue(key);
        String seedLiteral = LdcHandler.getIntString(seed);
        context.output.append(String.format(
                "{ jlong __ngen_res = %s; jlong __ngen_mix = native_jvm::utils::decode_long(0LL, %s, %d, %d, %s); " +
                        "jlong __ngen_enc = __ngen_res ^ __ngen_mix; cstack%s.j = native_jvm::utils::decode_long(__ngen_enc, %s, %d, %d, %s); }%s",
                resultExpression,
                keyLiteral, context.methodIndex, context.classIndex, seedLiteral,
                targetIndex,
                keyLiteral, context.methodIndex, context.classIndex, seedLiteral,
                props.get("trycatchhandler")));
    }

    private void emitEncodedIntDiv(MethodContext context) {
        int key = FastRandom.nextInt();
        int seed = FastRandom.nextInt();
        String keyLiteral = LdcHandler.getIntString(key);
        String seedLiteral = LdcHandler.getIntString(seed);
        String dividendIdx = props.get("stackindexm2");
        String divisorIdx = props.get("stackindexm1");
        String tryCatch = props.get("trycatchhandler");
        context.output.append(String.format(
                "if (cstack%s.i == -1 && cstack%s.i == ((jint) 2147483648U)) { } else { if (cstack%s.i == 0) { " +
                        "utils::throw_re(env, \"java/lang/ArithmeticException\", \"IDIV / by 0\", %d); %s } else { " +
                        "jint __ngen_res = cstack%s.i / cstack%s.i; jint __ngen_mix = native_jvm::utils::decode_int(0, %s, %d, %d, %s); " +
                        "jint __ngen_enc = __ngen_res ^ __ngen_mix; cstack%s.i = native_jvm::utils::decode_int(__ngen_enc, %s, %d, %d, %s); } }",
                divisorIdx, dividendIdx,
                divisorIdx, context.line, tryCatch,
                dividendIdx, divisorIdx,
                keyLiteral, context.methodIndex, context.classIndex, seedLiteral,
                dividendIdx,
                keyLiteral, context.methodIndex, context.classIndex, seedLiteral));
        context.output.append(String.format(" %s", tryCatch));
    }

    @Override
    public String insnToString(MethodContext context, InsnNode node) {
        return Util.getOpcodeString(node.getOpcode());
    }

    @Override
    public int getNewStackPointer(InsnNode node, int currentStackPointer) {
        switch (node.getOpcode()) {
            case Opcodes.NOP:
            case Opcodes.ARRAYLENGTH:
            case Opcodes.RETURN:
            case Opcodes.I2S:
            case Opcodes.I2C:
            case Opcodes.I2B:
            case Opcodes.D2L:
            case Opcodes.F2I:
            case Opcodes.L2D:
            case Opcodes.I2F:
            case Opcodes.DNEG:
            case Opcodes.FNEG:
            case Opcodes.LNEG:
            case Opcodes.INEG:
            case Opcodes.SWAP:
            case Opcodes.LALOAD:
            case Opcodes.DALOAD:
                return currentStackPointer;
            case Opcodes.ACONST_NULL:
            case Opcodes.F2D:
            case Opcodes.F2L:
            case Opcodes.I2D:
            case Opcodes.I2L:
            case Opcodes.DUP_X1:
            case Opcodes.DUP_X2:
            case Opcodes.DUP:
            case Opcodes.FCONST_2:
            case Opcodes.FCONST_1:
            case Opcodes.FCONST_0:
            case Opcodes.ICONST_5:
            case Opcodes.ICONST_4:
            case Opcodes.ICONST_3:
            case Opcodes.ICONST_2:
            case Opcodes.ICONST_1:
            case Opcodes.ICONST_0:
            case Opcodes.ICONST_M1:
                return currentStackPointer + 1;
            case Opcodes.LCONST_0:
            case Opcodes.DUP2_X1:
            case Opcodes.DUP2_X2:
            case Opcodes.DUP2:
            case Opcodes.DCONST_1:
            case Opcodes.DCONST_0:
            case Opcodes.LCONST_1:
                return currentStackPointer + 2;
            case Opcodes.IALOAD:
            case Opcodes.FALOAD:
            case Opcodes.AALOAD:
            case Opcodes.BALOAD:
            case Opcodes.CALOAD:
            case Opcodes.SALOAD:
            case Opcodes.MONITOREXIT:
            case Opcodes.MONITORENTER:
            case Opcodes.ARETURN:
            case Opcodes.FRETURN:
            case Opcodes.IRETURN:
            case Opcodes.FCMPG:
            case Opcodes.FCMPL:
            case Opcodes.D2F:
            case Opcodes.D2I:
            case Opcodes.L2F:
            case Opcodes.L2I:
            case Opcodes.IXOR:
            case Opcodes.IOR:
            case Opcodes.IAND:
            case Opcodes.LUSHR:
            case Opcodes.IUSHR:
            case Opcodes.LSHR:
            case Opcodes.ISHR:
            case Opcodes.LSHL:
            case Opcodes.ISHL:
            case Opcodes.FREM:
            case Opcodes.IREM:
            case Opcodes.FDIV:
            case Opcodes.IDIV:
            case Opcodes.FMUL:
            case Opcodes.IMUL:
            case Opcodes.FSUB:
            case Opcodes.ISUB:
            case Opcodes.FADD:
            case Opcodes.IADD:
            case Opcodes.POP:
            case Opcodes.ATHROW:
                return currentStackPointer - 1;
            case Opcodes.IASTORE:
            case Opcodes.DCMPG:
            case Opcodes.DCMPL:
            case Opcodes.LCMP:
            case Opcodes.SASTORE:
            case Opcodes.CASTORE:
            case Opcodes.BASTORE:
            case Opcodes.AASTORE:
            case Opcodes.FASTORE:
                return currentStackPointer - 3;
            case Opcodes.LASTORE:
            case Opcodes.DASTORE:
                return currentStackPointer - 4;
            case Opcodes.POP2:
            case Opcodes.DRETURN:
            case Opcodes.LRETURN:
            case Opcodes.LXOR:
            case Opcodes.LOR:
            case Opcodes.LAND:
            case Opcodes.DREM:
            case Opcodes.LREM:
            case Opcodes.DDIV:
            case Opcodes.LDIV:
            case Opcodes.DMUL:
            case Opcodes.LMUL:
            case Opcodes.DSUB:
            case Opcodes.LSUB:
            case Opcodes.DADD:
            case Opcodes.LADD:
                return currentStackPointer - 2;
        }
        throw new RuntimeException(String.valueOf(node.getOpcode()));
    }
}
