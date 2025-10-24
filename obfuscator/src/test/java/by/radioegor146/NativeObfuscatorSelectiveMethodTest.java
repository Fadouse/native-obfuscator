package by.radioegor146;

import by.radioegor146.nativeobfuscator.Native;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeObfuscatorSelectiveMethodTest {

    @Test
    void generatesLoaderInvocationForSelectiveNativeMethods() throws IOException {
        Path tempDir = Files.createTempDirectory("native-obf-selective");
        Path inputJar = tempDir.resolve("input.jar");
        Path outputDir = tempDir.resolve("out");
        Files.createDirectories(outputDir);

        byte[] classBytes = buildSelectiveNativeClass();
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(inputJar))) {
            JarEntry entry = new JarEntry("test/PartialNative.class");
            jos.putNextEntry(entry);
            jos.write(classBytes);
            jos.closeEntry();
        }

        NativeObfuscator obfuscator = new NativeObfuscator();
        obfuscator.process(inputJar, outputDir,
                Collections.<Path>emptyList(),
                null,
                null,
                "native_library", null, Platform.HOTSPOT,
                true, false,
                false, false, true, true, true);

        Path obfuscatedJar = outputDir.resolve(inputJar.getFileName());
        byte[] processedClass = readClass(obfuscatedJar, "test/PartialNative.class");

        ClassNode node = new ClassNode();
        new ClassReader(processedClass).accept(node, 0);

        Optional<MethodNode> clinit = node.methods.stream()
                .filter(method -> "<clinit>".equals(method.name) && "()V".equals(method.desc))
                .findFirst();

        assertTrue(clinit.isPresent(), "Expected <clinit> to be present in transformed class");
        if (!hasLoaderCall(clinit.get())) {
            System.out.println("<clinit> instructions:");
            for (AbstractInsnNode insn = clinit.get().instructions.getFirst(); insn != null; insn = insn.getNext()) {
                System.out.println(insn);
            }
        }
        assertTrue(hasLoaderCall(clinit.get()), "Expected loader registration call inside <clinit>");
    }

    private static byte[] buildSelectiveNativeClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/PartialNative", null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "counter", "I", null, null).visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "javaMethod", "()I", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "nativeMethod", "(I)I", null, null);
        mv.visitAnnotation(Type.getDescriptor(Native.class), false).visitEnd();
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, "test/PartialNative", "counter", "I");
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, "test/PartialNative", "counter", "I");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] readClass(Path jarPath, String entryName) throws IOException {
        try (var jar = new java.util.jar.JarFile(jarPath.toFile())) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IOException("Entry " + entryName + " not found in jar " + jarPath);
            }
            try (var is = jar.getInputStream(entry)) {
                return is.readAllBytes();
            }
        }
    }

    private static boolean hasLoaderCall(MethodNode methodNode) {
        for (AbstractInsnNode insn = methodNode.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode methodInsn
                    && methodInsn.getOpcode() == Opcodes.INVOKESTATIC
                    && methodInsn.owner.endsWith("/Loader")
                    && "registerNativesForClass".equals(methodInsn.name)
                    && "(ILjava/lang/Class;)V".equals(methodInsn.desc)) {
                return true;
            }
        }
        return false;
    }
}
