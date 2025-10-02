package by.radioegor146;

import by.radioegor146.bytecode.PreprocessorRunner;
import by.radioegor146.javaobf.JavaObfuscationConfig;
import by.radioegor146.javaobf.JavaObfuscator;
import by.radioegor146.source.CMakeFilesBuilder;
import by.radioegor146.source.ClassSourceBuilder;
import by.radioegor146.source.MainSourceBuilder;
import by.radioegor146.source.StringPool;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.gravit.launchserver.asm.ClassMetadataReader;
import ru.gravit.launchserver.asm.SafeClassWriter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class NativeObfuscator {

    private static final Logger logger = LoggerFactory.getLogger(NativeObfuscator.class);

    private final Snippets snippets;
    private final StringPool stringPool;
    private final MethodProcessor methodProcessor;

    private final NodeCache<String> cachedStrings;
    private final NodeCache<String> cachedClasses;
    private final NodeCache<CachedMethodInfo> cachedMethods;
    private final NodeCache<CachedFieldInfo> cachedFields;
    private byte[] lastStringPoolBytes;
    private byte[] lastNativeJvmSourceBytes;

    public static class InvokeDynamicInfo {
        private final String methodName;
        private final int index;

        public InvokeDynamicInfo(String methodName, int index) {
            this.methodName = methodName;
            this.index = index;
        }

        public String getMethodName() {
            return methodName;
        }

        public int getIndex() {
            return index;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            InvokeDynamicInfo that = (InvokeDynamicInfo) o;
            return index == that.index && Objects.equals(methodName, that.methodName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(methodName, index);
        }
    }

    private HiddenMethodsPool hiddenMethodsPool;

    private int currentClassId;
    private String nativeDir;

    public NativeObfuscator() {
        stringPool = new StringPool();
        snippets = new Snippets(stringPool);
        cachedStrings = new NodeCache<>("(cstrings[%d])");
        cachedClasses = new NodeCache<>("(cclasses[%d])");
        cachedMethods = new NodeCache<>("(cmethods[%d])");
        cachedFields = new NodeCache<>("(cfields[%d])");
        methodProcessor = new MethodProcessor(this);
    }

    public void process(Path inputJarPath, Path outputDir, List<Path> inputLibs,
                        List<String> blackList, List<String> whiteList, String plainLibName,
                        String customLibraryDirectory,
                        Platform platform, boolean useAnnotations, boolean generateDebugJar,
                        boolean enableVirtualization, boolean enableJit, boolean flattenControlFlow,
                        boolean obfuscateStrings, boolean obfuscateConstants) throws IOException {
        // Default Java obfuscation disabled, native obfuscation enabled
        process(inputJarPath, outputDir, inputLibs, blackList, whiteList, plainLibName, customLibraryDirectory,
                platform, useAnnotations, generateDebugJar, enableVirtualization, enableJit, flattenControlFlow,
                obfuscateStrings, obfuscateConstants,
                false, "MEDIUM", new ArrayList<>(), new ArrayList<>(), true);
    }

    public void process(Path inputJarPath, Path outputDir, List<Path> inputLibs,
                        List<String> blackList, List<String> whiteList, String plainLibName,
                        String customLibraryDirectory,
                        Platform platform, boolean useAnnotations, boolean generateDebugJar,
                        boolean enableVirtualization, boolean enableJit, boolean flattenControlFlow,
                        boolean obfuscateStrings, boolean obfuscateConstants,
                        boolean enableJavaObfuscation, String javaObfuscationStrength,
                        List<String> javaBlackList, List<String> javaWhiteList, boolean enableNativeObfuscation) throws IOException {
        ProtectionConfig protectionConfig = new ProtectionConfig(enableVirtualization, enableJit, flattenControlFlow,
                obfuscateStrings, obfuscateConstants);
        if (Files.exists(outputDir) && Files.isSameFile(inputJarPath.toRealPath().getParent(), outputDir.toRealPath())) {
            throw new RuntimeException("Input jar can't be in the same directory as output directory");
        }

        stringPool.reset(protectionConfig.isStringObfuscationEnabled());

        // Step 1: Apply Java obfuscation if enabled
        Path processedJarPath = inputJarPath;
        if (enableJavaObfuscation) {
            logger.info("Starting Java-layer obfuscation...");
            JavaObfuscationConfig.Strength strength;
            try {
                strength = JavaObfuscationConfig.Strength.valueOf(javaObfuscationStrength.toUpperCase());
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid Java obfuscation strength '{}', using MEDIUM", javaObfuscationStrength);
                strength = JavaObfuscationConfig.Strength.MEDIUM;
            }

            JavaObfuscationConfig javaConfig = new JavaObfuscationConfig(true, strength, javaBlackList, javaWhiteList);
            JavaObfuscator javaObfuscator = new JavaObfuscator();

            Path javaObfOutputDir = outputDir.resolve("java-obf-temp");
            processedJarPath = javaObfuscator.process(inputJarPath, javaObfOutputDir, inputLibs, javaConfig, useAnnotations);
            logger.info("Java-layer obfuscation completed. Output: {}", processedJarPath);
        }

        // Step 2: Check if native obfuscation is disabled
        if (!enableNativeObfuscation) {
            logger.info("Native obfuscation disabled. Copying final JAR to output directory...");
            Path finalOutputJar = outputDir.resolve(processedJarPath.getFileName().toString());
            Files.createDirectories(outputDir);
            Files.deleteIfExists(finalOutputJar);
            Files.copy(processedJarPath, finalOutputJar);
            logger.info("Processing completed. Output: {}", finalOutputJar);
            return;
        }

        List<Path> libs = new ArrayList<>(inputLibs);
        libs.add(processedJarPath);
        ClassMethodFilter classMethodFilter = new ClassMethodFilter(ClassMethodList.parse(blackList), ClassMethodList.parse(whiteList), useAnnotations);
        ClassMetadataReader metadataReader = new ClassMetadataReader(libs.stream().map(x -> {
            try {
                return new JarFile(x.toFile());
            } catch (IOException ex) {
                return null;
            }
        }).collect(Collectors.toList()));

        Path cppDir = outputDir.resolve("cpp");
        Path cppOutput = cppDir.resolve("output");
        Files.createDirectories(cppOutput);

        Util.copyResource("sources/native_jvm.cpp", cppDir);
        Util.copyResource("sources/native_jvm.hpp", cppDir);
        Util.copyResource("sources/native_jvm_output.hpp", cppDir);
        Util.copyResource("sources/string_pool.hpp", cppDir);
        Util.copyResource("sources/micro_vm.cpp", cppDir);
        Util.copyResource("sources/micro_vm.hpp", cppDir);
        Util.copyResource("sources/vm_jit.cpp", cppDir);
        Util.copyResource("sources/vm_jit.hpp", cppDir);
        Util.copyResource("sources/anti_debug.cpp", cppDir);
        Util.copyResource("sources/anti_debug.hpp", cppDir);

        String projectName = "native_library";

        CMakeFilesBuilder cMakeBuilder = new CMakeFilesBuilder(projectName);
        cMakeBuilder.addMainFile("native_jvm.hpp");
        cMakeBuilder.addMainFile("native_jvm.cpp");
        cMakeBuilder.addMainFile("native_jvm_output.hpp");
        cMakeBuilder.addMainFile("native_jvm_output.cpp");
        cMakeBuilder.addMainFile("string_pool.hpp");
        cMakeBuilder.addMainFile("string_pool.cpp");
        cMakeBuilder.addMainFile("micro_vm.hpp");
        cMakeBuilder.addMainFile("micro_vm.cpp");
        cMakeBuilder.addMainFile("vm_jit.hpp");
        cMakeBuilder.addMainFile("vm_jit.cpp");
        cMakeBuilder.addMainFile("anti_debug.hpp");
        cMakeBuilder.addMainFile("anti_debug.cpp");

        if (platform != Platform.ANDROID) {
            cMakeBuilder.addFlag("USE_HOTSPOT");
        }

        MainSourceBuilder mainSourceBuilder = new MainSourceBuilder();

        File jarFile = processedJarPath.toAbsolutePath().toFile();
        try (JarFile jar = new JarFile(jarFile);
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(outputDir.resolve(jarFile.getName())));
             ZipOutputStream debug = generateDebugJar ? new ZipOutputStream(
                     Files.newOutputStream(outputDir.resolve("debug.jar"))) : null) {

            logger.info("Processing {}...", jarFile);

            if (customLibraryDirectory != null) {
                nativeDir = customLibraryDirectory;

                if (jar.stream().anyMatch(x -> x.getName().equals(nativeDir) ||
                        x.getName().startsWith(nativeDir + "/"))) {
                    logger.warn("Directory '{}' already exists in input jar file", nativeDir);
                }
            } else {
                int nativeDirId = IntStream.iterate(0, i -> i + 1)
                        .filter(i -> jar.stream().noneMatch(x -> x.getName().equals("native" + i) ||
                                x.getName().startsWith("native" + i + "/")))
                        .findFirst().orElseThrow(RuntimeException::new);
                nativeDir = "native" + nativeDirId;
            }

            hiddenMethodsPool = new HiddenMethodsPool(nativeDir + "/hidden");

            Integer[] classIndexReference = new Integer[]{0};

            jar.stream().forEach(entry -> {
                if (entry.getName().equals(JarFile.MANIFEST_NAME)) return;

                try {
                    if (!entry.getName().endsWith(".class")) {
                        Util.writeEntry(jar, out, entry);
                        if (debug != null) {
                            Util.writeEntry(jar, debug, entry);
                        }
                        return;
                    }

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (InputStream in = jar.getInputStream(entry)) {
                        Util.transfer(in, baos);
                    }
                    byte[] src = baos.toByteArray();

                    if (Util.byteArrayToInt(Arrays.copyOfRange(src, 0, 4)) != 0xCAFEBABE) {
                        Util.writeEntry(out, entry.getName(), src);
                        if (debug != null) {
                            Util.writeEntry(debug, entry.getName(), src);
                        }
                        return;
                    }

                    StringBuilder nativeMethods = new StringBuilder();
                    List<HiddenCppMethod> hiddenMethods = new ArrayList<>();

                    ClassReader classReader = new ClassReader(src);
                    ClassNode rawClassNode = new ClassNode(Opcodes.ASM7);
                    classReader.accept(rawClassNode, 0);

                    boolean shouldProcessClass = classMethodFilter.shouldProcess(rawClassNode);
                    LinkedHashSet<String> nativeMethodKeys = rawClassNode.methods.stream()
                            .filter(MethodProcessor::shouldProcess)
                            .filter(method -> classMethodFilter.shouldProcess(rawClassNode, method))
                            .map(method -> MethodProcessor.nameFromNode(method, rawClassNode))
                            .collect(Collectors.toCollection(LinkedHashSet::new));

                    boolean hasPartialNative = classMethodFilter.hasPartialMethodObfuscation(rawClassNode);
                    if (hasPartialNative && logger.isDebugEnabled()) {
                        logger.debug("Class {} will be partially transpiled to native code", rawClassNode.name);
                    }

                    if (!nativeMethodKeys.isEmpty()) {
                        boolean hasClinit = rawClassNode.methods.stream()
                                .filter(method -> "<clinit>".equals(method.name) && "()V".equals(method.desc))
                                .anyMatch(method -> nativeMethodKeys.contains(MethodProcessor.nameFromNode(method, rawClassNode)));
                        if (!hasClinit) {
                            nativeMethodKeys.add(rawClassNode.name + "#<clinit>!()V");
                        }
                    }

                    if (!shouldProcessClass || nativeMethodKeys.isEmpty()) {
                        logger.info("Skipping {}", rawClassNode.name);
                        if (useAnnotations) {
                            ClassMethodFilter.cleanAnnotations(rawClassNode);
                            ClassWriter clearedClassWriter = new SafeClassWriter(metadataReader, Opcodes.ASM7);
                            rawClassNode.accept(clearedClassWriter);
                            Util.writeEntry(out, entry.getName(), clearedClassWriter.toByteArray());
                            if (debug != null) {
                                Util.writeEntry(debug, entry.getName(), clearedClassWriter.toByteArray());
                            }
                            return;
                        }
                        Util.writeEntry(out, entry.getName(), src);
                        if (debug != null) {
                            Util.writeEntry(debug, entry.getName(), src);
                        }
                        return;
                    }

                    logger.info("Preprocessing {}", rawClassNode.name);

                    rawClassNode.methods.stream()
                            .filter(methodNode -> nativeMethodKeys.contains(MethodProcessor.nameFromNode(methodNode, rawClassNode)))
                            .forEach(methodNode -> PreprocessorRunner.preprocess(rawClassNode, methodNode, platform));

                    ClassWriter preprocessorClassWriter = new SafeClassWriter(metadataReader, Opcodes.ASM7 | ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                    rawClassNode.accept(preprocessorClassWriter);
                    if (debug != null) {
                        Util.writeEntry(debug, entry.getName(), preprocessorClassWriter.toByteArray());
                    }
                    classReader = new ClassReader(preprocessorClassWriter.toByteArray());
                    ClassNode classNode = new ClassNode(Opcodes.ASM7);
                    classReader.accept(classNode, 0);

                    logger.info("Processing {}", classNode.name);

                    if (classNode.methods.stream().noneMatch(x -> x.name.equals("<clinit>"))) {
                        classNode.methods.add(new MethodNode(Opcodes.ASM7, Opcodes.ACC_STATIC,
                                "<clinit>", "()V", null, new String[0]));
                    }

                    cachedStrings.clear();
                    cachedClasses.clear();
                    cachedMethods.clear();
                    cachedFields.clear();

                    try (ClassSourceBuilder cppBuilder =
                                 new ClassSourceBuilder(cppOutput, classNode.name, classIndexReference[0]++, stringPool)) {
                        StringBuilder instructions = new StringBuilder();

                        for (int i = 0; i < classNode.methods.size(); i++) {
                            MethodNode method = classNode.methods.get(i);

                            if (!MethodProcessor.shouldProcess(method)) {
                                continue;
                            }

                            if (!nativeMethodKeys.contains(MethodProcessor.nameFromNode(method, classNode))) {
                                continue;
                            }

                            MethodContext context = new MethodContext(this, method, i, classNode, currentClassId, protectionConfig);
                            methodProcessor.processMethod(context);
                            instructions.append(context.output.toString().replace("\n", "\n    "));

                            nativeMethods.append(context.nativeMethods);

                            if (context.proxyMethod != null) {
                                hiddenMethods.add(new HiddenCppMethod(context.proxyMethod, context.cppNativeMethodName));
                            }

                            if ((classNode.access & Opcodes.ACC_INTERFACE) > 0) {
                                method.access &= ~Opcodes.ACC_NATIVE;
                            }
                        }

                        if (useAnnotations) {
                            ClassMethodFilter.cleanAnnotations(classNode);
                        }

                        classNode.version = 52;
                        ClassWriter classWriter = new SafeClassWriter(metadataReader,
                                Opcodes.ASM7 | ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                        classNode.accept(classWriter);
                        Util.writeEntry(out, entry.getName(), classWriter.toByteArray());

                        cppBuilder.addHeader(cachedStrings.size(), cachedClasses.size(), cachedMethods.size(), cachedFields.size());
                        cppBuilder.addInstructions(instructions.toString());
                        cppBuilder.registerMethods(cachedStrings, cachedClasses, cachedMethods, cachedFields,
                                nativeMethods.toString(), hiddenMethods);

                        cMakeBuilder.addClassFile("output/" + cppBuilder.getHppFilename());
                        cMakeBuilder.addClassFile("output/" + cppBuilder.getCppFilename());

                        mainSourceBuilder.addHeader(cppBuilder.getHppFilename());
                        mainSourceBuilder.registerClassMethods(currentClassId, cppBuilder.getFilename());
                    }

                    currentClassId++;
                } catch (IOException ex) {
                    logger.error("Error while processing {}", entry.getName(), ex);
                }
            });

            if (platform == Platform.ANDROID) {
                for (ClassNode hiddenClass : hiddenMethodsPool.getClasses()) {
                    ClassWriter classWriter = new SafeClassWriter(metadataReader, Opcodes.ASM7 | ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                    hiddenClass.accept(classWriter);
                    Util.writeEntry(out, hiddenClass.name + ".class", classWriter.toByteArray());
                }
            } else {
                for (ClassNode hiddenClass : hiddenMethodsPool.getClasses()) {
                    String hiddenClassFileName = "data_" + Util.escapeCppNameString(hiddenClass.name.replace('/', '_'));

                    cMakeBuilder.addClassFile("output/" + hiddenClassFileName + ".hpp");
                    cMakeBuilder.addClassFile("output/" + hiddenClassFileName + ".cpp");

                    mainSourceBuilder.addHeader(hiddenClassFileName + ".hpp");
                    mainSourceBuilder.registerDefine(stringPool.get(hiddenClass.name), hiddenClassFileName);

                    ClassWriter classWriter = new SafeClassWriter(metadataReader, Opcodes.ASM7 | ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                    hiddenClass.accept(classWriter);
                    byte[] rawData = classWriter.toByteArray();
                    List<Byte> data = new ArrayList<>(rawData.length);
                    for (byte b : rawData) {
                        data.add(b);
                    }

                    if (debug != null) {
                        Util.writeEntry(debug, hiddenClass.name + ".class", rawData);
                    }

                    try (BufferedWriter hppWriter = Files.newBufferedWriter(cppOutput.resolve(hiddenClassFileName + ".hpp"))) {
                        hppWriter.append("#include \"../native_jvm.hpp\"\n\n");
                        hppWriter.append("#ifndef ").append(hiddenClassFileName.toUpperCase()).append("_HPP_GUARD\n\n");
                        hppWriter.append("#define ").append(hiddenClassFileName.toUpperCase()).append("_HPP_GUARD\n\n");
                        hppWriter.append("namespace native_jvm::data::__ngen_").append(hiddenClassFileName).append(" {\n");
                        hppWriter.append("    const jbyte* get_class_data();\n");
                        hppWriter.append("    const jsize get_class_data_length();\n");
                        hppWriter.append("}\n\n");
                        hppWriter.append("#endif\n");
                    }

                    try (BufferedWriter cppWriter = Files.newBufferedWriter(cppOutput.resolve(hiddenClassFileName + ".cpp"))) {
                        cppWriter.append("#include \"").append(hiddenClassFileName).append(".hpp\"\n\n");
                        cppWriter.append("namespace native_jvm::data::__ngen_").append(hiddenClassFileName).append(" {\n");
                        cppWriter.append("    static const jbyte class_data[").append(String.valueOf(data.size())).append("] = { ");
                        cppWriter.append(data.stream().map(String::valueOf).collect(Collectors.joining(", ")));
                        cppWriter.append("};\n");
                        cppWriter.append("    static const jsize class_data_length = ").append(String.valueOf(data.size())).append(";\n\n");
                        cppWriter.append("    const jbyte* get_class_data() { return class_data; }\n");
                        cppWriter.append("    const jsize get_class_data_length() { return class_data_length; }\n");
                        cppWriter.append("}\n");
                    }
                }
            }

            String loaderClassName = nativeDir + "/Loader";

            ClassNode loaderClass;

            if (plainLibName == null) {
                ClassReader loaderClassReader = new ClassReader(Objects.requireNonNull(NativeObfuscator.class
                        .getResourceAsStream("compiletime/LoaderUnpack.class")));
                loaderClass = new ClassNode(Opcodes.ASM7);
                loaderClassReader.accept(loaderClass, 0);
                loaderClass.sourceFile = "synthetic";
                System.out.println("/" + nativeDir + "/");
            } else {
                ClassReader loaderClassReader = new ClassReader(Objects.requireNonNull(NativeObfuscator.class
                        .getResourceAsStream("compiletime/LoaderPlain.class")));
                loaderClass = new ClassNode(Opcodes.ASM7);
                loaderClassReader.accept(loaderClass, 0);
                loaderClass.sourceFile = "synthetic";
                loaderClass.methods.forEach(method -> {
                    for (int i = 0; i < method.instructions.size(); i++) {
                        AbstractInsnNode insnNode = method.instructions.get(i);
                        if (insnNode instanceof LdcInsnNode && ((LdcInsnNode) insnNode).cst instanceof String &&
                                ((LdcInsnNode) insnNode).cst.equals("%LIB_NAME%")) {
                            ((LdcInsnNode) insnNode).cst = plainLibName;
                        }
                    }
                });
            }

            ClassNode resultLoaderClass = new ClassNode(Opcodes.ASM7);
            String originalLoaderClassName = loaderClass.name;
            loaderClass.accept(new ClassRemapper(resultLoaderClass, new Remapper() {
                @Override
                public String map(String internalName) {
                    return internalName.equals(originalLoaderClassName) ? loaderClassName : internalName;
                }
            }));

            ClassWriter classWriter = new SafeClassWriter(metadataReader, Opcodes.ASM7 | ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            resultLoaderClass.accept(classWriter);
            Util.writeEntry(out, loaderClassName + ".class", classWriter.toByteArray());

            logger.info("Jar file ready!");
            Manifest mf = jar.getManifest();
            if (mf != null) {
                out.putNextEntry(new ZipEntry(JarFile.MANIFEST_NAME));
                mf.write(out);
            }
            out.closeEntry();
            metadataReader.close();
        }

        String stringPoolSource = stringPool.build();
        lastStringPoolBytes = stringPool.getEncryptedBytes();
        Files.write(cppDir.resolve("string_pool.cpp"), stringPoolSource.getBytes(StandardCharsets.UTF_8));

        String nativeJvmOutputSource = mainSourceBuilder.build(nativeDir, currentClassId);
        lastNativeJvmSourceBytes = nativeJvmOutputSource.getBytes(StandardCharsets.UTF_8);
        Files.write(cppDir.resolve("native_jvm_output.cpp"), lastNativeJvmSourceBytes);

        Files.write(cppDir.resolve("CMakeLists.txt"), cMakeBuilder.build().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Process JAR with comprehensive configuration object
     * @param config Complete obfuscation configuration
     * @throws IOException if processing fails
     */
    public void process(ObfuscatorConfig config) throws IOException {
        processWithAntiDebug(config.getInputJarPath(), config.getOutputDir(), config.getInputLibs(),
                config.getBlackList(), config.getWhiteList(), config.getPlainLibName(),
                config.getCustomLibraryDirectory(), config.getPlatform(),
                config.isUseAnnotations(), config.isGenerateDebugJar(),
                config.getProtectionConfig(), config.getAntiDebugConfig(),
                config.isEnableJavaObfuscation(), config.getJavaObfuscationStrength(),
                config.getJavaBlackList(), config.getJavaWhiteList(), config.isEnableNativeObfuscation());
    }

    private void processWithAntiDebug(Path inputJarPath, Path outputDir, List<Path> inputLibs,
                        List<String> blackList, List<String> whiteList, String plainLibName,
                        String customLibraryDirectory,
                        Platform platform, boolean useAnnotations, boolean generateDebugJar,
                        ProtectionConfig protectionConfig, AntiDebugConfig antiDebugConfig,
                        boolean enableJavaObfuscation, String javaObfuscationStrength,
                        List<String> javaBlackList, List<String> javaWhiteList, boolean enableNativeObfuscation) throws IOException {

        // Call the existing process method but with extended functionality
        process(inputJarPath, outputDir, inputLibs, blackList, whiteList, plainLibName, customLibraryDirectory,
                platform, useAnnotations, generateDebugJar,
                protectionConfig.isVirtualizationEnabled(), protectionConfig.isJitEnabled(),
                protectionConfig.isControlFlowFlatteningEnabled(),
                protectionConfig.isStringObfuscationEnabled(), protectionConfig.isConstantObfuscationEnabled(),
                enableJavaObfuscation, javaObfuscationStrength, javaBlackList, javaWhiteList, enableNativeObfuscation);

        // Generate anti-debug configuration header if any anti-debug features are enabled
        if (antiDebugConfig.isAnyEnabled()) {
            generateAntiDebugConfig(outputDir, outputDir.resolve("cpp"), antiDebugConfig, getNativeDir() + "/Loader");

            // Update the generated native_jvm_output.cpp to include anti-debug initialization
            updateNativeJvmOutputWithAntiDebug(outputDir.resolve("cpp"), antiDebugConfig);
        }
    }

    /**
     * Generates anti-debug configuration header file
     */
    private void generateAntiDebugConfig(Path outputDir, Path cppDir, AntiDebugConfig antiDebugConfig, String loaderClassInternalName) throws IOException {
        if (loaderClassInternalName == null) {
            loaderClassInternalName = "";
        }

        byte[] stringPoolBytes = lastStringPoolBytes == null ? new byte[0] : lastStringPoolBytes;
        byte[] nativeSourceBytes = lastNativeJvmSourceBytes == null ? new byte[0] : lastNativeJvmSourceBytes;
        int stringPoolLength = Math.max(1, stringPoolBytes.length);
        byte[] stringPoolBytesForHash = new byte[stringPoolLength];
        System.arraycopy(stringPoolBytes, 0, stringPoolBytesForHash, 0, stringPoolBytes.length);
        byte[] stringPoolHash = sha256(stringPoolBytesForHash);
        byte[] nativeSourceHash = sha256(nativeSourceBytes);

        byte[] loaderClassHash = new byte[0];
        boolean hasLoaderHash = false;
        if (!loaderClassInternalName.isEmpty()) {
            Path primaryJar = findPrimaryJar(outputDir);
            if (primaryJar != null) {
                byte[] loaderBytes = readJarEntry(primaryJar, loaderClassInternalName + ".class");
                if (loaderBytes != null) {
                    loaderClassHash = sha256(loaderBytes);
                    hasLoaderHash = true;
                }
            }
        }

        StringBuilder configBuilder = new StringBuilder();
        configBuilder.append("#ifndef ANTI_DEBUG_CONFIG_HPP_GUARD\n");
        configBuilder.append("#define ANTI_DEBUG_CONFIG_HPP_GUARD\n\n");
        configBuilder.append("#include \"anti_debug.hpp\"\n");
        configBuilder.append("#include <cstddef>\n\n");
        configBuilder.append("// Auto-generated anti-debug configuration\n");
        configBuilder.append("namespace native_jvm::anti_debug::config {\n\n");

        configBuilder.append("    constexpr bool ENABLE_GHOTSPOT_STRUCT_NULLIFICATION = ")
                     .append(antiDebugConfig.isGHotSpotVMStructsNullificationEnabled() ? "true" : "false")
                     .append(";\n");
        configBuilder.append("    constexpr bool ENABLE_DEBUGGER_DETECTION = ")
                     .append(antiDebugConfig.isDebuggerDetectionEnabled() ? "true" : "false")
                     .append(";\n");
        configBuilder.append("    constexpr bool ENABLE_DEBUGGER_API_CHECKS = ")
                     .append(antiDebugConfig.isDebuggerApiChecksEnabled() ? "true" : "false")
                     .append(";\n");
        configBuilder.append("    constexpr bool ENABLE_DEBUGGER_TRACER_CHECK = ")
                     .append(antiDebugConfig.isDebuggerTracerCheckEnabled() ? "true" : "false")
                     .append(";\n");
        configBuilder.append("    constexpr bool ENABLE_DEBUGGER_PTRACE_CHECK = ")
                     .append(antiDebugConfig.isDebuggerPtraceCheckEnabled() ? "true" : "false")
                     .append(";\n");
        configBuilder.append("    constexpr bool ENABLE_DEBUGGER_PROCESS_SCAN = ")
                     .append(antiDebugConfig.isDebuggerProcessScanEnabled() ? "true" : "false")
                     .append(";\n");
        configBuilder.append("    constexpr bool ENABLE_DEBUGGER_MODULE_SCAN = ")
                     .append(antiDebugConfig.isDebuggerModuleScanEnabled() ? "true" : "false")
                     .append(";\n");
        configBuilder.append("    constexpr bool ENABLE_DEBUGGER_ENV_SCAN = ")
                     .append(antiDebugConfig.isDebuggerEnvironmentScanEnabled() ? "true" : "false")
                     .append(";\n");
        configBuilder.append("    constexpr bool ENABLE_DEBUGGER_TIMING_CHECK = ")
                     .append(antiDebugConfig.isDebuggerTimingCheckEnabled() ? "true" : "false")
                     .append(";\n");
        configBuilder.append("    constexpr bool ENABLE_VM_INTEGRITY_CHECKS = ")
                     .append(antiDebugConfig.isVmProtectionEnabled() ? "true" : "false")
                     .append(";\n");
        configBuilder.append("    constexpr bool ENABLE_JVMTI_AGENT_BLOCKING = ")
                     .append(antiDebugConfig.isJvmtiAgentBlockingEnabled() ? "true" : "false")
                     .append(";\n");
        configBuilder.append("    constexpr bool ENABLE_ANTI_TAMPER = ")
                     .append(antiDebugConfig.isAntiTamperEnabled() ? "true" : "false")
                     .append(";\n");
        configBuilder.append("    constexpr bool ENABLE_DEBUG_REGISTER_SCRUBBING = ")
                     .append(antiDebugConfig.isDebugRegisterScrubbingEnabled() ? "true" : "false")
                     .append(";\n");
        configBuilder.append("    constexpr bool ENABLE_DEBUG_LOGGING = ")
                     .append(antiDebugConfig.isDebugLoggingEnabled() ? "true" : "false")
                     .append(";\n\n");

        configBuilder.append("    constexpr std::size_t STRING_POOL_ENCRYPTED_SIZE = ")
                     .append(stringPoolLength)
                     .append("ULL;\n");
        configBuilder.append("    constexpr unsigned char STRING_POOL_EXPECTED_HASH[32] = { ")
                     .append(toCppByteArray(stringPoolHash))
                     .append(" };\n");
        configBuilder.append("    constexpr unsigned char NATIVE_SOURCE_EXPECTED_HASH[32] = { ")
                     .append(toCppByteArray(nativeSourceHash))
                     .append(" };\n");
        configBuilder.append("    constexpr unsigned char LOADER_CLASS_EXPECTED_HASH[32] = { ")
                     .append(toCppByteArray(hasLoaderHash ? loaderClassHash : null))
                     .append(" };\n");
        configBuilder.append("    constexpr bool HAS_LOADER_HASH = ")
                     .append(hasLoaderHash ? "true" : "false")
                     .append(";\n");
        configBuilder.append("    constexpr const char LOADER_CLASS_INTERNAL_NAME[] = \"")
                     .append(loaderClassInternalName)
                     .append("\";\n\n");

        configBuilder.append("    inline anti_debug::AntiDebugRuntimeConfig create_runtime_config() {\n");
        configBuilder.append("        anti_debug::AntiDebugRuntimeConfig config{};\n");
        configBuilder.append("        config.enableGHotSpotVMStructNullification = ENABLE_GHOTSPOT_STRUCT_NULLIFICATION;\n");
        configBuilder.append("        config.enableDebuggerDetection = ENABLE_DEBUGGER_DETECTION;\n");
        configBuilder.append("        config.enableDebuggerApiChecks = ENABLE_DEBUGGER_API_CHECKS;\n");
        configBuilder.append("        config.enableDebuggerTracerCheck = ENABLE_DEBUGGER_TRACER_CHECK;\n");
        configBuilder.append("        config.enableDebuggerPtraceCheck = ENABLE_DEBUGGER_PTRACE_CHECK;\n");
        configBuilder.append("        config.enableDebuggerProcessScan = ENABLE_DEBUGGER_PROCESS_SCAN;\n");
        configBuilder.append("        config.enableDebuggerModuleScan = ENABLE_DEBUGGER_MODULE_SCAN;\n");
        configBuilder.append("        config.enableDebuggerEnvironmentScan = ENABLE_DEBUGGER_ENV_SCAN;\n");
        configBuilder.append("        config.enableDebuggerTimingCheck = ENABLE_DEBUGGER_TIMING_CHECK;\n");
        configBuilder.append("        config.enableVmIntegrityChecks = ENABLE_VM_INTEGRITY_CHECKS;\n");
        configBuilder.append("        config.enableJvmtiAgentBlocking = ENABLE_JVMTI_AGENT_BLOCKING;\n");
        configBuilder.append("        config.enableAntiTamper = ENABLE_ANTI_TAMPER;\n");
        configBuilder.append("        config.enableDebugRegisterScrubbing = ENABLE_DEBUG_REGISTER_SCRUBBING;\n");
        configBuilder.append("        config.enableDebugLogging = ENABLE_DEBUG_LOGGING;\n");
        configBuilder.append("        return config;\n    }\n\n");

        configBuilder.append("} // namespace native_jvm::anti_debug::config\n\n");
        configBuilder.append("#endif // ANTI_DEBUG_CONFIG_HPP_GUARD\n");

        Files.write(cppDir.resolve("anti_debug_config.hpp"), configBuilder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static String toCppByteArray(byte[] data) {
        if (data == null || data.length == 0) {
            data = new byte[32];
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            builder.append(String.format("0x%02X", data[i] & 0xFF));
            if (i + 1 < data.length) {
                builder.append(", ");
            }
        }
        return builder.toString();
    }

    private static Path findPrimaryJar(Path outputDir) throws IOException {
        try (java.util.stream.Stream<Path> stream = Files.list(outputDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".jar")
                            && !path.getFileName().toString().equals("debug.jar"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static byte[] readJarEntry(Path jarPath, String entryName) throws IOException {
        if (jarPath == null || entryName == null || entryName.isEmpty()) {
            return null;
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ZipEntry entry = jar.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (InputStream inputStream = jar.getInputStream(entry);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                Util.transfer(inputStream, baos);
                return baos.toByteArray();
            }
        }
    }

    /**
     * Updates the generated native_jvm_output.cpp file to include anti-debug initialization
     */
    private void updateNativeJvmOutputWithAntiDebug(Path cppDir, AntiDebugConfig antiDebugConfig) throws IOException {
        Path nativeJvmOutputFile = cppDir.resolve("native_jvm_output.cpp");
        if (!Files.exists(nativeJvmOutputFile)) {
            return; // File doesn't exist, nothing to update
        }

        // Read the existing file
        String content = new String(Files.readAllBytes(nativeJvmOutputFile), StandardCharsets.UTF_8);

        // Ensure anti-debug headers are included
        if (!content.contains("#include \"anti_debug.hpp\"")) {
            content = content.replace("#include \"string_pool.hpp\"",
                    "#include \"string_pool.hpp\"\n#include \"anti_debug.hpp\"");
        }
        if (!content.contains("#include \"anti_debug_config.hpp\"")) {
            int includeIndex = content.indexOf("#include \"anti_debug.hpp\"");
            if (includeIndex >= 0) {
                int lineEnd = content.indexOf('\n', includeIndex);
                if (lineEnd >= 0) {
                    content = content.substring(0, lineEnd + 1)
                            + "#include \"anti_debug_config.hpp\"\n"
                            + content.substring(lineEnd + 1);
                }
            }
        }

        // Generate anti-debug initialization code
        String antiDebugInit = generateAntiDebugInitCode(antiDebugConfig);

        // Insert anti-debug initialization after utils::init_utils
        String searchPattern = "        utils::init_utils(env);\n        if (env->ExceptionCheck())\n            return;";
        String replacement = searchPattern + "\n\n" + antiDebugInit;

        content = content.replace(searchPattern, replacement);

        // Write the updated content back to the file
        Files.write(nativeJvmOutputFile, content.getBytes(StandardCharsets.UTF_8));
    }

    private String generateAntiDebugInitCode(AntiDebugConfig antiDebugConfig) {
        StringBuilder code = new StringBuilder();
        code.append("        // Initialize anti-debug protection\n");
        code.append("        anti_debug::init_anti_debug(env, native_jvm::anti_debug::config::create_runtime_config());\n");
        code.append("        if (env->ExceptionCheck())\n");
        code.append("            return;");
        return code.toString();
    }

    public Snippets getSnippets() {
        return snippets;
    }

    public StringPool getStringPool() {
        return stringPool;
    }

    public NodeCache<String> getCachedStrings() {
        return cachedStrings;
    }

    public NodeCache<String> getCachedClasses() {
        return cachedClasses;
    }

    public NodeCache<CachedMethodInfo> getCachedMethods() {
        return cachedMethods;
    }

    public NodeCache<CachedFieldInfo> getCachedFields() {
        return cachedFields;
    }

    public String getNativeDir() {
        return nativeDir;
    }

    public HiddenMethodsPool getHiddenMethodsPool() {
        return hiddenMethodsPool;
    }
}
