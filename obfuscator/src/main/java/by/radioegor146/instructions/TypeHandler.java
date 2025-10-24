package by.radioegor146.instructions;

import by.radioegor146.MethodContext;
import by.radioegor146.MethodProcessor;
import by.radioegor146.Util;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.TypeInsnNode;

public class TypeHandler extends GenericInstructionHandler<TypeInsnNode> {

    @Override
    protected void process(MethodContext context, TypeInsnNode node) {
        props.put("desc", node.desc);

        int classId = context.getCachedClasses().getId(node.desc);
        String classPtr = MethodProcessor.ensureVerifiedClass(context, classId, node.desc, trimmedTryCatchBlock);

        props.put("class_ptr", classPtr);
    }

    @Override
    public String insnToString(MethodContext context, TypeInsnNode node) {
        return String.format("%s %s", Util.getOpcodeString(node.getOpcode()), node.desc);
    }

    @Override
    public int getNewStackPointer(TypeInsnNode node, int currentStackPointer) {
        switch (node.getOpcode()) {
            case Opcodes.ANEWARRAY:
            case Opcodes.CHECKCAST:
            case Opcodes.INSTANCEOF:
                return currentStackPointer;
            case Opcodes.NEW:
                return currentStackPointer + 1;
        }
        throw new RuntimeException();
    }
}
