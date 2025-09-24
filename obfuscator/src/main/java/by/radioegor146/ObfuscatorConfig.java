package by.radioegor146;

import by.radioegor146.javaobf.JavaObfuscationConfig;

import java.nio.file.Path;
import java.util.List;

/**
 * Comprehensive configuration class for the Native Obfuscator.
 * This class encapsulates all configuration options including protection settings,
 * anti-debug features, and Java obfuscation parameters.
 */
public class ObfuscatorConfig {

    // Basic obfuscation settings
    private final Path inputJarPath;
    private final Path outputDir;
    private final List<Path> inputLibs;
    private final List<String> blackList;
    private final List<String> whiteList;
    private final String plainLibName;
    private final String customLibraryDirectory;
    private final Platform platform;
    private final boolean useAnnotations;
    private final boolean generateDebugJar;

    // Protection configuration
    private final ProtectionConfig protectionConfig;

    // Anti-debug configuration
    private final AntiDebugConfig antiDebugConfig;

    // Java obfuscation configuration
    private final boolean enableJavaObfuscation;
    private final String javaObfuscationStrength;
    private final List<String> javaBlackList;
    private final List<String> javaWhiteList;
    private final boolean enableNativeObfuscation;

    public ObfuscatorConfig(Path inputJarPath, Path outputDir, List<Path> inputLibs,
                           List<String> blackList, List<String> whiteList,
                           String plainLibName, String customLibraryDirectory,
                           Platform platform, boolean useAnnotations, boolean generateDebugJar,
                           ProtectionConfig protectionConfig, AntiDebugConfig antiDebugConfig,
                           boolean enableJavaObfuscation, String javaObfuscationStrength,
                           List<String> javaBlackList, List<String> javaWhiteList,
                           boolean enableNativeObfuscation) {
        this.inputJarPath = inputJarPath;
        this.outputDir = outputDir;
        this.inputLibs = inputLibs;
        this.blackList = blackList;
        this.whiteList = whiteList;
        this.plainLibName = plainLibName;
        this.customLibraryDirectory = customLibraryDirectory;
        this.platform = platform;
        this.useAnnotations = useAnnotations;
        this.generateDebugJar = generateDebugJar;
        this.protectionConfig = protectionConfig;
        this.antiDebugConfig = antiDebugConfig;
        this.enableJavaObfuscation = enableJavaObfuscation;
        this.javaObfuscationStrength = javaObfuscationStrength;
        this.javaBlackList = javaBlackList;
        this.javaWhiteList = javaWhiteList;
        this.enableNativeObfuscation = enableNativeObfuscation;
    }

    // Getters for basic settings
    public Path getInputJarPath() { return inputJarPath; }
    public Path getOutputDir() { return outputDir; }
    public List<Path> getInputLibs() { return inputLibs; }
    public List<String> getBlackList() { return blackList; }
    public List<String> getWhiteList() { return whiteList; }
    public String getPlainLibName() { return plainLibName; }
    public String getCustomLibraryDirectory() { return customLibraryDirectory; }
    public Platform getPlatform() { return platform; }
    public boolean isUseAnnotations() { return useAnnotations; }
    public boolean isGenerateDebugJar() { return generateDebugJar; }

    // Getters for configuration objects
    public ProtectionConfig getProtectionConfig() { return protectionConfig; }
    public AntiDebugConfig getAntiDebugConfig() { return antiDebugConfig; }

    // Getters for Java obfuscation
    public boolean isEnableJavaObfuscation() { return enableJavaObfuscation; }
    public String getJavaObfuscationStrength() { return javaObfuscationStrength; }
    public List<String> getJavaBlackList() { return javaBlackList; }
    public List<String> getJavaWhiteList() { return javaWhiteList; }
    public boolean isEnableNativeObfuscation() { return enableNativeObfuscation; }

    // Convenience methods for accessing nested configuration properties
    public boolean isVirtualizationEnabled() { return protectionConfig.isVirtualizationEnabled(); }
    public boolean isJitEnabled() { return protectionConfig.isJitEnabled(); }
    public boolean isControlFlowFlatteningEnabled() { return protectionConfig.isControlFlowFlatteningEnabled(); }
    public boolean isAntiDebugEnabled() { return antiDebugConfig.isAnyEnabled(); }

    /**
     * Validates the entire configuration and prints warnings for problematic combinations.
     */
    public void validateAndWarn() {
        protectionConfig.validateAndWarn();
        antiDebugConfig.validateAndWarn();

        // Cross-configuration validations
        if (antiDebugConfig.isAnyEnabled() && !enableNativeObfuscation) {
            System.out.println("Warning: Anti-debug features are enabled but native obfuscation is disabled.");
            System.out.println("Anti-debug protection will only be applied to Java-layer obfuscation.");
        }

        if (protectionConfig.isVirtualizationEnabled() && antiDebugConfig.isGHotSpotVMStructsNullificationEnabled()) {
            System.out.println("Info: VM virtualization and gHotSpotVMStructs nullification are both enabled.");
            System.out.println("This provides maximum protection against reverse engineering.");
        }

        if (enableJavaObfuscation && enableNativeObfuscation && antiDebugConfig.isAnyEnabled()) {
            System.out.println("Info: Full protection stack is enabled (Java + Native + Anti-Debug).");
        }
    }

    @Override
    public String toString() {
        return String.format("ObfuscatorConfig{\n" +
                "  inputJar=%s,\n" +
                "  outputDir=%s,\n" +
                "  platform=%s,\n" +
                "  enableJavaObfuscation=%s,\n" +
                "  enableNativeObfuscation=%s,\n" +
                "  protectionConfig=%s,\n" +
                "  antiDebugConfig=%s\n" +
                "}",
                inputJarPath, outputDir, platform, enableJavaObfuscation,
                enableNativeObfuscation, protectionConfig, antiDebugConfig);
    }

    /**
     * Builder class for constructing ObfuscatorConfig instances.
     */
    public static class Builder {
        private Path inputJarPath;
        private Path outputDir;
        private List<Path> inputLibs;
        private List<String> blackList;
        private List<String> whiteList;
        private String plainLibName;
        private String customLibraryDirectory;
        private Platform platform = Platform.HOTSPOT;
        private boolean useAnnotations = false;
        private boolean generateDebugJar = false;
        private ProtectionConfig protectionConfig = ProtectionConfig.createDefault();
        private AntiDebugConfig antiDebugConfig = AntiDebugConfig.createDefault();
        private boolean enableJavaObfuscation = false;
        private String javaObfuscationStrength = "MEDIUM";
        private List<String> javaBlackList;
        private List<String> javaWhiteList;
        private boolean enableNativeObfuscation = true;

        public Builder setInputJarPath(Path inputJarPath) {
            this.inputJarPath = inputJarPath;
            return this;
        }

        public Builder setOutputDir(Path outputDir) {
            this.outputDir = outputDir;
            return this;
        }

        public Builder setInputLibs(List<Path> inputLibs) {
            this.inputLibs = inputLibs;
            return this;
        }

        public Builder setBlackList(List<String> blackList) {
            this.blackList = blackList;
            return this;
        }

        public Builder setWhiteList(List<String> whiteList) {
            this.whiteList = whiteList;
            return this;
        }

        public Builder setPlainLibName(String plainLibName) {
            this.plainLibName = plainLibName;
            return this;
        }

        public Builder setCustomLibraryDirectory(String customLibraryDirectory) {
            this.customLibraryDirectory = customLibraryDirectory;
            return this;
        }

        public Builder setPlatform(Platform platform) {
            this.platform = platform;
            return this;
        }

        public Builder setUseAnnotations(boolean useAnnotations) {
            this.useAnnotations = useAnnotations;
            return this;
        }

        public Builder setGenerateDebugJar(boolean generateDebugJar) {
            this.generateDebugJar = generateDebugJar;
            return this;
        }

        public Builder setProtectionConfig(ProtectionConfig protectionConfig) {
            this.protectionConfig = protectionConfig;
            return this;
        }

        public Builder setAntiDebugConfig(AntiDebugConfig antiDebugConfig) {
            this.antiDebugConfig = antiDebugConfig;
            return this;
        }

        public Builder setEnableJavaObfuscation(boolean enableJavaObfuscation) {
            this.enableJavaObfuscation = enableJavaObfuscation;
            return this;
        }

        public Builder setJavaObfuscationStrength(String javaObfuscationStrength) {
            this.javaObfuscationStrength = javaObfuscationStrength;
            return this;
        }

        public Builder setJavaBlackList(List<String> javaBlackList) {
            this.javaBlackList = javaBlackList;
            return this;
        }

        public Builder setJavaWhiteList(List<String> javaWhiteList) {
            this.javaWhiteList = javaWhiteList;
            return this;
        }

        public Builder setEnableNativeObfuscation(boolean enableNativeObfuscation) {
            this.enableNativeObfuscation = enableNativeObfuscation;
            return this;
        }

        public ObfuscatorConfig build() {
            if (inputJarPath == null || outputDir == null) {
                throw new IllegalArgumentException("Input JAR path and output directory are required");
            }
            return new ObfuscatorConfig(inputJarPath, outputDir, inputLibs, blackList, whiteList,
                    plainLibName, customLibraryDirectory, platform, useAnnotations, generateDebugJar,
                    protectionConfig, antiDebugConfig, enableJavaObfuscation, javaObfuscationStrength,
                    javaBlackList, javaWhiteList, enableNativeObfuscation);
        }
    }
}