package by.radioegor146.instructions;

import by.radioegor146.MethodContext;
import by.radioegor146.MethodProcessor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.LdcInsnNode;

import java.util.concurrent.ThreadLocalRandom;

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

    @Override
    protected void process(MethodContext context, LdcInsnNode node) {
        Object cst = node.cst;
        if (cst instanceof String) {
            instructionName += "_STRING";
            props.put("cst_ptr", context.getCachedStrings().getPointer(node.cst.toString()));
        } else if (cst instanceof Integer) {
            instructionName += "_INT";
            int key = ThreadLocalRandom.current().nextInt();
            int mid = context.methodIndex;
            int cid = context.classIndex;
            int mixed = key ^ ((mid << 3) | cid);
            int enc = ((Integer) cst) ^ mixed;
            props.put("enc", getIntString(enc));
            props.put("key", getIntString(key));
            props.put("mid", String.valueOf(mid));
            props.put("cid", String.valueOf(cid));
        } else if (cst instanceof Long) {
            instructionName += "_LONG";
            long key = ThreadLocalRandom.current().nextLong();
            int mid = context.methodIndex;
            int cid = context.classIndex;
            long mixed = key ^ ((((long) mid) << 32) | (cid & 0xffffffffL));
            long enc = ((Long) cst) ^ mixed;
            props.put("enc", getLongValue(enc));
            props.put("key", getLongValue(key));
            props.put("mid", String.valueOf(mid));
            props.put("cid", String.valueOf(cid));
        } else if (cst instanceof Float) {
            instructionName += "_FLOAT";
            int bits = Float.floatToRawIntBits((Float) cst);
            int key = ThreadLocalRandom.current().nextInt();
            int mid = context.methodIndex;
            int cid = context.classIndex;
            int mixed = key ^ ((mid << 3) | cid);
            int enc = bits ^ mixed;
            props.put("enc", getIntString(enc));
            props.put("key", getIntString(key));
            props.put("mid", String.valueOf(mid));
            props.put("cid", String.valueOf(cid));
        } else if (cst instanceof Double) {
            instructionName += "_DOUBLE";
            long bits = Double.doubleToRawLongBits((Double) cst);
            long key = ThreadLocalRandom.current().nextLong();
            int mid = context.methodIndex;
            int cid = context.classIndex;
            long mixed = key ^ ((((long) mid) << 32) | (cid & 0xffffffffL));
            long enc = bits ^ mixed;
            props.put("enc", getLongValue(enc));
            props.put("key", getLongValue(key));
            props.put("mid", String.valueOf(mid));
            props.put("cid", String.valueOf(cid));
        } else if (cst instanceof Type) {
            instructionName += "_CLASS";

            int classId = context.getCachedClasses().getId(node.cst.toString());
            context.output.append(String.format("if (!cclasses[%d] || env->IsSameObject(cclasses[%d], NULL)) { cclasses_mtx[%d].lock(); if (!cclasses[%d] || env->IsSameObject(cclasses[%d], NULL)) { if (jclass clazz = %s) { cclasses[%d] = (jclass) env->NewWeakGlobalRef(clazz); env->DeleteLocalRef(clazz); } } cclasses_mtx[%d].unlock(); %s } ",
                    classId,
                    classId,
                    classId,
                    classId,
                    classId,
                    MethodProcessor.getClassGetter(context, node.cst.toString()),
                    classId,
                    classId,
                    trimmedTryCatchBlock));
            
            props.put("cst_ptr", context.getCachedClasses().getPointer(node.cst.toString()));
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
