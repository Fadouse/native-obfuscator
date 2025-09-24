package by.radioegor146.javaobf;

import by.radioegor146.ClassMethodFilter;
import by.radioegor146.ClassMethodList;
import by.radioegor146.Util;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckClassAdapter;
import ru.gravit.launchserver.asm.ClassMetadataReader;
import ru.gravit.launchserver.asm.SafeClassWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.io.StringWriter;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Java-layer obfuscator: currently implements basic control-flow flattening
 * (state machine wrapper) with multiple strength levels.
 */
public class JavaObfuscator {

    private static final Logger logger = LoggerFactory.getLogger(JavaObfuscator.class);

    public Path process(Path inputJarPath,
                        Path outputDir,
                        List<Path> inputLibs,
                        JavaObfuscationConfig config,
                        boolean useAnnotations) throws IOException {
        Objects.requireNonNull(inputJarPath, "inputJarPath");
        Objects.requireNonNull(outputDir, "outputDir");
        Objects.requireNonNull(config, "config");

        if (!config.isEnabled()) {
            // No changes; just copy jar to output
            Path outJar = outputDir.resolve(inputJarPath.getFileName().toString());
            Files.createDirectories(outputDir);
            Files.deleteIfExists(outJar);
            Files.copy(inputJarPath, outJar);
            return outJar;
        }

        List<Path> libs = new ArrayList<>(inputLibs);
        libs.add(inputJarPath);

        // For Java obfuscation, we use a more permissive approach
        // Only create whitelist if user explicitly provided one
        ClassMethodList javaWhiteList = null;
        if (config.getJavaWhiteList() != null && !config.getJavaWhiteList().isEmpty()) {
            javaWhiteList = ClassMethodList.parse(config.getJavaWhiteList());
        }

        ClassMethodFilter filter = new ClassMethodFilter(
            ClassMethodList.parse(config.getJavaBlackList()),
            javaWhiteList, // null means no whitelist restriction
            useAnnotations);
        ClassMetadataReader metadataReader = new ClassMetadataReader(libs.stream().map(x -> {
            try {
                return new JarFile(x.toFile());
            } catch (IOException ex) {
                return null;
            }
        }).collect(Collectors.toList()));

        Path outJar = outputDir.resolve(inputJarPath.getFileName().toString());
        Files.createDirectories(outputDir);


        try (JarFile jar = new JarFile(inputJarPath.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(outJar))) {
            jar.stream().forEach(entry -> {
                try {
                    if (!entry.getName().endsWith(".class")) {
                        if (!entry.getName().equals(JarFile.MANIFEST_NAME)) {
                            Util.writeEntry(jar, out, entry);
                        }
                        return;
                    }

                    byte[] src;
                    try (InputStream in = jar.getInputStream(entry)) {
                        src = in.readAllBytes();
                    }

                    ClassReader cr = new ClassReader(src);
                    ClassNode cn = new ClassNode(Opcodes.ASM9);
                    cr.accept(cn, 0);

                    boolean changed = false;
                    if (filter.shouldProcess(cn)) {
                        logger.info("Processing class for Java obfuscation: {}", cn.name);
                        int methodsProcessed = 0;
                        for (MethodNode mn : cn.methods) {
                            if (!filter.shouldProcess(cn, mn)) continue;

                            // Double-check: Never process constructors or class initializers
                            // This is critical for JVM compliance and prevents corruption
                            if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) {
                                logger.warn("Skipping constructor/initializer: {}.{}{}", cn.name, mn.name, mn.desc);
                                continue;
                            }

                            if (!JavaControlFlowFlattener.canProcess(mn)) continue;

                            try {
                                logger.warn("Advanced flattening method: {}.{}{}", cn.name, mn.name, mn.desc);
                                JavaControlFlowFlattener.flatten(mn, cn.name + "#" + mn.name + mn.desc, config.getStrength());
                                methodsProcessed++;
                                changed = true;
                            } catch (Exception e) {
                                logger.warn("Failed to flatten method {}.{}{}: {}",
                                        cn.name, mn.name, mn.desc, e.getMessage());
                                // Continue with other methods
                            }
                        }
                        if (methodsProcessed > 0) {
                            logger.info("Applied control flow flattening to {} methods in {}", methodsProcessed, cn.name);
                        }
                    }

                    if (useAnnotations && changed) {
                        ClassMethodFilter.cleanAnnotations(cn);

                    }

                    if (changed) {
                        sanitizeTryCatches(cn);  // ★ 新增：统一清理
                        sanitizeLocalVariables(cn);  // ★ 新增
                    }

                    // Try to write with frame computation, fall back if needed
                    byte[] result = null;
                    try {
                        // First attempt: with COMPUTE_FRAMES
                        ClassWriter cw = new SafeClassWriter(metadataReader,
                                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                        cn.accept(cw);
                        result = cw.toByteArray();
                    } catch (Exception e) {
                        logger.warn("Frame computation failed for {}, using fallback.", cn.name);
                        logger.warn("Frame computation error: ", e);
                        try {
                            // Second attempt: without COMPUTE_FRAMES
                            ClassWriter cw = new SafeClassWriter(metadataReader,
                                    ClassWriter.COMPUTE_MAXS);
                            // Remove frame nodes only from methods that we actually modified
                            // Never touch <init> or <clinit> methods during fallback
                            for (MethodNode mn : cn.methods) {
                                // Skip constructors and initializers - they should never be modified
                                if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) {
                                    continue;
                                }

                                if (mn.instructions != null && filter.shouldProcess(cn, mn) && JavaControlFlowFlattener.canProcess(mn)) {
                                    AbstractInsnNode insn = mn.instructions.getFirst();
                                    while (insn != null) {
                                        AbstractInsnNode next = insn.getNext();
                                        if (insn instanceof FrameNode) {
                                            mn.instructions.remove(insn);
                                        }
                                        insn = next;
                                    }
                                }
                            }
                            cn.accept(cw);
                            result = cw.toByteArray();
                        } catch (Exception e2) {
                            logger.error("Failed to write class even without frames: {}", cn.name);
                            // Use original bytecode
                            result = src;
                        }
                    }

                    if (changed && result != src) {
                        verifyBytecode(result, entry.getName(), metadataReader);
                    }

                    Util.writeEntry(out, entry.getName(), result);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });


            // Preserve manifest if present
            if (jar.getManifest() != null) {
                out.putNextEntry(new ZipEntry(JarFile.MANIFEST_NAME));
                jar.getManifest().write(out);
                out.closeEntry();
            }
        } finally {
            metadataReader.close();
        }

        return outJar;
    }

    private static void sanitizeLocalVariables(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            // Never modify constructors or initializers during sanitization
            if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) {
                continue;
            }
            if (mn.localVariables == null || mn.localVariables.isEmpty()) continue;
            // 标注每个 Label 在最终指令流中的位置
            IdentityHashMap<LabelNode, Integer> pos = new IdentityHashMap<>();
            int i = 0;
            for (AbstractInsnNode p = mn.instructions.getFirst(); p != null; p = p.getNext(), i++) {
                if (p instanceof LabelNode) pos.put((LabelNode) p, i);
            }

            List<LocalVariableNode> keep = new ArrayList<>(mn.localVariables.size());
            // 用 (index, startPos, endPos) 作为去重键 —— JVM 判定重复就是看这三个
            HashSet<String> seen = new HashSet<>();
            for (LocalVariableNode lv : mn.localVariables) {
                if (lv == null || lv.start == null || lv.end == null) continue;
                Integer s = pos.get(lv.start), e = pos.get(lv.end);
                if (s == null || e == null) continue;
                if (s >= e) continue; // 丢弃零长度/逆序范围
                String key = lv.index + ":" + s + ":" + e; // 名字/desc 不参与去重
                if (!seen.add(key)) continue;              // 去重
                keep.add(lv);
            }
            // 可选：按 startPos 排序，便于可读性
            keep.sort((a, b) -> {
                Integer sa = pos.get(a.start), ea = pos.get(a.end);
                Integer sb = pos.get(b.start), eb = pos.get(b.end);
                int x = Integer.compare(sa, sb);
                return (x != 0) ? x : Integer.compare(ea, eb);
            });
            mn.localVariables = keep;
        }
    }


    private void verifyBytecode(byte[] bytecode, String entryName, ClassMetadataReader metadataReader) {
        if (bytecode == null || entryName == null) return;

        final ClassLoader verifierLoader =
                new MetadataClassLoader(metadataReader, JavaObfuscator.class.getClassLoader());

        // ——— 1) 先直接校验原始字节码 ———
        final String first = runAsmVerify(bytecode, verifierLoader);

        if (first.isEmpty()) {
            // 原始字节码验证通过
            return;
        }

        // ——— 2) 尝试重算 StackMap/Maxs 再验一次（常见于构造器/框架误报或边界情况）———
        byte[] recomputed = null;
        try {
            recomputed = recomputeFrames(bytecode, verifierLoader);
        } catch (Throwable ignore) {
            // 重算失败也不要中断，继续按原始错误处理
        }

        if (recomputed != null) {
            final String second = runAsmVerify(recomputed, verifierLoader);
            if (second.isEmpty()) {
                // 重算后通过：多数情况下是帧信息/公共父类推断导致的误报
                System.err.println("[verifyBytecode] Recomputed frames fixed verification for " + entryName + ".");
                return;
            }
        }

        // ——— 3) 两次都没过：打印详细调试并抛异常 ———
        try {
            System.err.println("=== ASM detailed debug (class-wide) ===");
            by.radioegor146.javaobf.debug.AsmDebug.verifyClassBytes(
                    bytecode,
                    new MetadataClassLoader(metadataReader, JavaObfuscator.class.getClassLoader())
            );
        } catch (Throwable ignore) {
            // 调试输出失败不影响抛错
        }

        final StringBuilder msg = new StringBuilder("ASM verification failed for ")
                .append(entryName)
                .append('\n')
                .append(first);

        throw new IllegalStateException(msg.toString());
    }

    /* ===== 辅助函数 ===== */

    private static String runAsmVerify(byte[] bytes, ClassLoader loader) {
        final StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw, true)) {
            try {
                CheckClassAdapter.verify(new ClassReader(bytes), loader, /*dump*/ false, pw);
            } catch (Throwable t) {
                // 某些 ASM 版本会直接抛异常；兜底写进报告即可
                t.printStackTrace(pw);
            }
        }
        return sw.toString().trim();
    }

    private static byte[] recomputeFrames(byte[] in, final ClassLoader loader) {
        final ClassReader cr = new ClassReader(in);
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    if (type1.equals(type2)) return type1;

                    Class<?> c1 = Class.forName(type1.replace('/', '.'), false, loader);
                    Class<?> c2 = Class.forName(type2.replace('/', '.'), false, loader);

                    if (c1.isAssignableFrom(c2)) return org.objectweb.asm.Type.getInternalName(c1);
                    if (c2.isAssignableFrom(c1)) return org.objectweb.asm.Type.getInternalName(c2);

                    if (c1.isInterface() || c2.isInterface()) {
                        return "java/lang/Object";
                    }

                    // 向上追溯 c1 的父类，直到能接住 c2
                    while (c1 != null && !c1.isAssignableFrom(c2)) {
                        c1 = c1.getSuperclass();
                    }
                    return c1 == null ? "java/lang/Object" : org.objectweb.asm.Type.getInternalName(c1);
                } catch (Throwable t) {
                    // 任何解析失败都回退到 Object，避免 verify 过程中因反射类不可见而崩
                    return "java/lang/Object";
                }
            }
        };

        // 用 EXPAND_FRAMES 让 ASM 先铺开已有帧，再交给 CW 重新计算
        cr.accept(cw, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }


    private static void sanitizeTryCatches(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            // Never modify constructors or initializers during sanitization
            if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) {
                continue;
            }
            if (mn.tryCatchBlocks == null || mn.tryCatchBlocks.isEmpty()) continue;

            // 标注方法内各 Label 的出现位置
            IdentityHashMap<LabelNode, Integer> pos = new IdentityHashMap<>();
            int i = 0;
            for (AbstractInsnNode p = mn.instructions.getFirst(); p != null; p = p.getNext(), i++) {
                if (p instanceof LabelNode) pos.put((LabelNode) p, i);
            }

            List<TryCatchBlockNode> keep = new ArrayList<>(mn.tryCatchBlocks.size());
            for (TryCatchBlockNode t : mn.tryCatchBlocks) {
                if (t == null || t.start == null || t.end == null || t.handler == null) continue;
                if (t.start == t.end) continue;
                Integer s = pos.get(t.start), e = pos.get(t.end), h = pos.get(t.handler);
                if (s == null || e == null || h == null) continue;
                if (s >= e) continue; // 空/逆序
                keep.add(t);
            }
            mn.tryCatchBlocks = keep;
        }
    }

    private static final class MetadataClassLoader extends ClassLoader {
        private final ClassMetadataReader metadataReader;

        MetadataClassLoader(ClassMetadataReader metadataReader, ClassLoader parent) {
            super(parent);
            this.metadataReader = metadataReader;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (metadataReader == null) {
                throw new ClassNotFoundException(name);
            }
            String internal = name.replace('.', '/');
            try {
                byte[] data = metadataReader.getClassData(internal);
                return defineClass(name, data, 0, data.length);
            } catch (IOException ignored) {
            } catch (ClassNotFoundException ignored) {
            }
            throw new ClassNotFoundException(name);
        }
    }
}
