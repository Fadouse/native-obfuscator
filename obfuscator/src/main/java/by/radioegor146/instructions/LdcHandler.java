package by.radioegor146.instructions;

import by.radioegor146.FastRandom;
import by.radioegor146.MethodContext;
import by.radioegor146.MethodProcessor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.LdcInsnNode;

public class LdcHandler extends GenericInstructionHandler<LdcInsnNode> {

    public static String getIntString(int value) {
        return value == Integer.MIN_VALUE ? "(jint) 2147483648U" : String.valueOf(value);
    }

    public static String getLongValue(long value) {
        return value == Long.MIN_VALUE ? "(jlong) 9223372036854775808ULL" : String.valueOf(value) + "LL";
    }

    public static String getFloatValue(float value) {
        if (Float.isNaN(value)) {
            return "NAN";
        } else if (value == Float.POSITIVE_INFINITY) {
            return "HUGE_VALF";
        } else if (value == Float.NEGATIVE_INFINITY) {
            return "-HUGE_VALF";
        }
        return value + "f";
    }

    public static String getDoubleValue(double value) {
        if (Double.isNaN(value)) {
            return "NAN";
        } else if (value == Double.POSITIVE_INFINITY) {
            return "HUGE_VAL";
        } else if (value == Double.NEGATIVE_INFINITY) {
            return "-HUGE_VAL";
        }
        return String.valueOf(value);
    }

    private static int chachaRound(int a, int b, int c, int d) {
        a += b; d ^= a; d = Integer.rotateLeft(d, 16);
        c += d; b ^= c; b = Integer.rotateLeft(b, 12);
        a += b; d ^= a; d = Integer.rotateLeft(d, 8);
        c += d; b ^= c; b = Integer.rotateLeft(b, 7);
        return a;
    }

    private static int mix32(int key, int mid, int cid, int seed) {
        return chachaRound(key, mid, cid, seed);
    }

    private static long mix64(long key, int mid, int cid, int seed) {
        int k1 = (int) key;
        int k2 = (int) (key >>> 32);
        int s2 = seed ^ 0x9E3779B9;
        int r1 = chachaRound(k1, mid, cid, seed);
        int r2 = chachaRound(k2, cid, mid, s2);
        return (((long) r2) << 32) | (r1 & 0xffffffffL);
    }

    @Override
    protected void process(MethodContext context, LdcInsnNode node) {
        Object cst = node.cst;
        boolean constantsObfuscated = context.protectionConfig.isConstantObfuscationEnabled();
        if (cst instanceof String) {
            instructionName += "_STRING";
            props.put("cst_ptr", context.getCachedStrings().getPointer(node.cst.toString()));
        } else if (cst instanceof Integer) {
            if (constantsObfuscated) {
                instructionName += "_INT";
                int key = FastRandom.nextInt();
                int seed = FastRandom.nextInt();
                int mid = context.methodIndex;
                int cid = context.classIndex;
                int mixed = mix32(key, mid, cid, seed);
                int enc = ((Integer) cst) ^ mixed;
                props.put("enc", getIntString(enc));
                props.put("key", getIntString(key));
                props.put("mid", String.valueOf(mid));
                props.put("cid", String.valueOf(cid));
                props.put("seed", getIntString(seed));
            } else {
                instructionName += "_INT_RAW";
                props.put("value", getIntString((Integer) cst));
            }
        } else if (cst instanceof Long) {
            if (constantsObfuscated) {
                instructionName += "_LONG";
                long key = FastRandom.nextLong();
                int seed = FastRandom.nextInt();
                int mid = context.methodIndex;
                int cid = context.classIndex;
                long mixed = mix64(key, mid, cid, seed);
                long enc = ((Long) cst) ^ mixed;
                props.put("enc", getLongValue(enc));
                props.put("key", getLongValue(key));
                props.put("mid", String.valueOf(mid));
                props.put("cid", String.valueOf(cid));
                props.put("seed", getIntString(seed));
            } else {
                instructionName += "_LONG_RAW";
                props.put("value", getLongValue((Long) cst));
            }
        } else if (cst instanceof Float) {
            if (constantsObfuscated) {
                instructionName += "_FLOAT";
                int bits = Float.floatToRawIntBits((Float) cst);
                int key = FastRandom.nextInt();
                int seed = FastRandom.nextInt();
                int mid = context.methodIndex;
                int cid = context.classIndex;
                int mixed = mix32(key, mid, cid, seed);
                int enc = bits ^ mixed;
                props.put("enc", getIntString(enc));
                props.put("key", getIntString(key));
                props.put("mid", String.valueOf(mid));
                props.put("cid", String.valueOf(cid));
                props.put("seed", getIntString(seed));
            } else {
                instructionName += "_FLOAT_RAW";
                props.put("value", getFloatValue((Float) cst));
            }
        } else if (cst instanceof Double) {
            if (constantsObfuscated) {
                instructionName += "_DOUBLE";
                long bits = Double.doubleToRawLongBits((Double) cst);
                long key = FastRandom.nextLong();
                int seed = FastRandom.nextInt();
                int mid = context.methodIndex;
                int cid = context.classIndex;
                long mixed = mix64(key, mid, cid, seed);
                long enc = bits ^ mixed;
                props.put("enc", getLongValue(enc));
                props.put("key", getLongValue(key));
                props.put("mid", String.valueOf(mid));
                props.put("cid", String.valueOf(cid));
                props.put("seed", getIntString(seed));
            } else {
                instructionName += "_DOUBLE_RAW";
                props.put("value", getDoubleValue((Double) cst));
            }
        } else if (cst instanceof Type) {
            instructionName += "_CLASS";

            int classId = context.getCachedClasses().getId(node.cst.toString());
            String classPtr = MethodProcessor.ensureVerifiedClass(context, classId, node.cst.toString(), trimmedTryCatchBlock);

            props.put("class_ptr", classPtr);
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public String insnToString(MethodContext context, LdcInsnNode node) {
        return String.format("LDC %s", node.cst);
    }

    @Override
    public int getNewStackPointer(LdcInsnNode node, int currentStackPointer) {
        if (node.cst instanceof Double || node.cst instanceof Long) {
            return currentStackPointer + 2;
        }
        return currentStackPointer + 1;
    }
}
