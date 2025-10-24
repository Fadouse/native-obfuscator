package by.radioegor146.instructions;

import by.radioegor146.CachedFieldInfo;
import by.radioegor146.MethodContext;
import by.radioegor146.MethodProcessor;
import by.radioegor146.Util;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;

public class FieldHandler extends GenericInstructionHandler<FieldInsnNode> {

    @Override
    protected void process(MethodContext context, FieldInsnNode node) {
        boolean isStatic = node.getOpcode() == Opcodes.GETSTATIC || node.getOpcode() == Opcodes.PUTSTATIC;
        CachedFieldInfo info = new CachedFieldInfo(node.owner, node.name, node.desc, isStatic);

        instructionName += "_" + Type.getType(node.desc).getSort();
        int classId = context.getCachedClasses().getId(node.owner);

        // Optimization: skip class verification and initialization for same-class field access
        boolean isSameClass = node.owner.equals(context.clazz.name);
        String classPtr;

        if (isSameClass && isStatic) {
            // Accessing our own static field - class must already be initialized
            classPtr = "clazz";
        } else {
            // Different class - need full verification
            classPtr = MethodProcessor.ensureVerifiedClass(context, classId, node.owner, trimmedTryCatchBlock);

            // Mirror JVM semantics: static field access triggers class initialization
            if (isStatic) {
                String dotted = node.owner.replace('/', '.');
                context.output.append(String.format(
                        "if (!cclasses_initialized[%1$d].load()) { cclasses_mtx[%1$d].lock(); if (!cclasses_initialized[%1$d].load()) { utils::ensure_initialized(env, classloader, %2$s); if (!env->ExceptionCheck()) { cclasses_initialized[%1$d].store(true); } } cclasses_mtx[%1$d].unlock(); %3$s } ",
                        classId,
                        context.getCachedStrings().getPointer(dotted),
                        trimmedTryCatchBlock));
            }
        }

        if (isStatic) {
            props.put("class_ptr", classPtr);
        }

        int fieldId = context.getCachedFields().getId(info);
        props.put("fieldid", context.getCachedFields().getPointer(info));

        // Use std::call_once for lock-free thread-safe field ID initialization
        context.output.append(String.format("std::call_once(cfields_init_flag[%1$d], [&]() { cfields[%1$d] = env->Get%2$sFieldID(%3$s, %4$s, %5$s); }); %6$s ",
                fieldId,
                isStatic ? "Static" : "",
                classPtr,
                context.getStringPool().get(node.name),
                context.getStringPool().get(node.desc),
                trimmedTryCatchBlock));

        // Heuristic: mark when the synthetic enum switch mapping array is loaded onto the stack.
        // Pattern: GETSTATIC <SomeClass>.$SwitchMap$... : [I
        if (node.getOpcode() == Opcodes.GETSTATIC
                && "[I".equals(node.desc)
                && node.name != null && node.name.startsWith("$SwitchMap$")
        ) {
            context.enumSwitchMapOnStack = true;
        }
    }

    @Override
    public String insnToString(MethodContext context, FieldInsnNode node) {
        return String.format("%s %s.%s %s", Util.getOpcodeString(node.getOpcode()), node.owner, node.name, node.desc);
    }

    @Override
    public int getNewStackPointer(FieldInsnNode node, int currentStackPointer) {
        if (node.getOpcode() == Opcodes.GETFIELD || node.getOpcode() == Opcodes.PUTFIELD) {
            currentStackPointer -= 1;
        }
        if (node.getOpcode() == Opcodes.GETSTATIC || node.getOpcode() == Opcodes.GETFIELD) {
            currentStackPointer += Type.getType(node.desc).getSize();
        }
        if (node.getOpcode() == Opcodes.PUTSTATIC || node.getOpcode() == Opcodes.PUTFIELD) {
            currentStackPointer -= Type.getType(node.desc).getSize();
        }
        return currentStackPointer;
    }
}
