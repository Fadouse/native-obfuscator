package by.radioegor146.special;

import by.radioegor146.MethodContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;

public class ClInitSpecialMethodProcessor implements SpecialMethodProcessor {

    @Override
    public String previewName(MethodContext context) {
        if (shouldKeepOriginalClinit(context)) {
            return null;
        }
        return String.format("special_clinit_%d_%d", context.classIndex, context.methodIndex);
    }

    @Override
    public String preProcess(MethodContext context) {
        // Avoid transforming <clinit> for classes that are known to be fragile under
        // redirection on newer JVMs (e.g., enum classes and synthetic switch-map holders).
        if (shouldKeepOriginalClinit(context)) {
            context.skipNative = true;
            return null;
        }
        String name = String.format("special_clinit_%d_%d", context.classIndex, context.methodIndex);

        context.proxyMethod = context.obfuscator.getHiddenMethodsPool().getMethod(name, "(Ljava/lang/Class;)V", methodNode -> {
            methodNode.signature = context.method.signature;
            methodNode.access = Opcodes.ACC_NATIVE | Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE;
            methodNode.visibleAnnotations = new ArrayList<>();
            methodNode.visibleAnnotations.add(new AnnotationNode("Ljava/lang/invoke/LambdaForm$Hidden;"));
            methodNode.visibleAnnotations.add(new AnnotationNode("Ljdk/internal/vm/annotation/Hidden;"));
        });
        return name;
    }

    @Override
    public void postProcess(MethodContext context) {
        InsnList instructions = context.method.instructions;
        instructions.clear();
        instructions.add(new LdcInsnNode(context.classIndex));
        instructions.add(new LdcInsnNode(Type.getObjectType(context.clazz.name)));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, context.obfuscator.getNativeDir() + "/Loader",
                "registerNativesForClass", "(ILjava/lang/Class;)V", false));
        instructions.add(new LdcInsnNode(Type.getObjectType(context.clazz.name)));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                context.proxyMethod.getClassNode().name,
                context.proxyMethod.getMethodNode().name,
                context.proxyMethod.getMethodNode().desc, false));
        instructions.add(new InsnNode(Opcodes.RETURN));
    }
    private static boolean shouldKeepOriginalClinit(MethodContext context) {
        // 1) Enum classes: JVM places special constraints on enum initialization order.
        boolean isEnum = (context.clazz.access & Opcodes.ACC_ENUM) != 0;

        // 2) Synthetic switch-map holder classes typically look like: one static synthetic int[] field
        // and only a <clinit> method. These are very sensitive to init ordering.
        boolean looksLikeSwitchMap = false;
        if (context.clazz.fields != null && context.clazz.fields.size() == 1) {
            FieldNode f = (FieldNode) context.clazz.fields.get(0);
            boolean oneIntArray = "[I".equals(f.desc);
            boolean isStatic = (f.access & Opcodes.ACC_STATIC) != 0;
            boolean isSynthetic = (f.access & Opcodes.ACC_SYNTHETIC) != 0;
            boolean onlyClinit = context.clazz.methods != null
                    && context.clazz.methods.stream().allMatch(m -> "<clinit>".equals(m.name));
            looksLikeSwitchMap = oneIntArray && isStatic && isSynthetic && onlyClinit;
        }

        return isEnum || looksLikeSwitchMap;
    }
}
