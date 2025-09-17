package by.radioegor146.javaobf;

import by.radioegor146.ClassMethodFilter;
import by.radioegor146.ClassMethodList;
import by.radioegor146.Util;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import ru.gravit.launchserver.asm.ClassMetadataReader;
import ru.gravit.launchserver.asm.SafeClassWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
                    ClassNode cn = new ClassNode(Opcodes.ASM7);
                    cr.accept(cn, 0);

                    boolean changed = false;
                    if (filter.shouldProcess(cn)) {
                        logger.info("Processing class for Java obfuscation: {}", cn.name);
                        int methodsProcessed = 0;
                        for (MethodNode mn : cn.methods) {
                            if (!filter.shouldProcess(cn, mn)) continue;
                            if (!JavaControlFlowFlattener.canProcess(mn)) continue;
                            logger.debug("Advanced flattening method: {}.{}{}", cn.name, mn.name, mn.desc);
                            JavaControlFlowFlattener.flatten(mn, cn.name + "#" + mn.name + mn.desc, config.getStrength());
                            methodsProcessed++;
                            changed = true;
                        }
                        if (methodsProcessed > 0) {
                            logger.info("Applied control flow flattening to {} methods in {}", methodsProcessed, cn.name);
                        }
                    } else {
                        logger.debug("Skipping class: {}", cn.name);
                    }

                    if (useAnnotations && changed) {
                        // Strip annotations used for filtering to avoid leaking intent
                        ClassMethodFilter.cleanAnnotations(cn);
                    }

                    ClassWriter cw = new SafeClassWriter(metadataReader, Opcodes.ASM7 | ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                    cn.accept(cw);
                    Util.writeEntry(out, entry.getName(), cw.toByteArray());
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
}

