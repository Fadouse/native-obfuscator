package dev.skidfuscator.obfuscator.predicate.cache;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Generates a lightweight runtime exception used for control-flow dispatch.
 * The generated exception disables stack trace collection to keep throwing
 * and catching it as cheap as possible.
 */
public class FlowExceptionDump implements Opcodes {

    private FlowExceptionDump() {
        // utility
    }

    public static byte[] dump() {
        ClassWriter classWriter = new ClassWriter(0);

        classWriter.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "skid/FlowException", null,
                "java/lang/RuntimeException", null);

        MethodVisitor methodVisitor = classWriter.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        methodVisitor.visitLabel(label0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitInsn(ACONST_NULL);
        methodVisitor.visitInsn(ACONST_NULL);
        methodVisitor.visitInsn(ICONST_0);
        methodVisitor.visitInsn(ICONST_0);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>",
                "(Ljava/lang/String;Ljava/lang/Throwable;ZZ)V", false);
        methodVisitor.visitInsn(RETURN);
        Label label1 = new Label();
        methodVisitor.visitLabel(label1);
        methodVisitor.visitLocalVariable("this", "Lskid/FlowException;", null, label0, label1, 0);
        methodVisitor.visitMaxs(5, 1);
        methodVisitor.visitEnd();

        classWriter.visitEnd();
        return classWriter.toByteArray();
    }
}
