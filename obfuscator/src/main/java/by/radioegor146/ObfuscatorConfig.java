package by.radioegor146;

import dev.skidfuscator.obfuscator.FlowExceptionMode;

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
    private final boolean skidStringObfuscation;
    private final boolean skidNumberObfuscation;
    private final boolean skidFlowObfuscation;
    private final boolean skidSdkInjection;
    private final boolean skidVmHashing;
    private final boolean skidInvokeDynamicObfuscation;
    private final FlowExceptionMode skidFlowExceptionMode;
    private final JavaFlowSettings javaFlowSettings;
    
    // Renamer configuration
    private final RenamerConfig renamerConfig;

    public ObfuscatorConfig(Path inputJarPath, Path outputDir, List<Path> inputLibs,
                           List<String> blackList, List<String> whiteList,
                           String plainLibName, String customLibraryDirectory,
                           Platform platform, boolean useAnnotations, boolean generateDebugJar,
                           ProtectionConfig protectionConfig, AntiDebugConfig antiDebugConfig,
                           boolean enableJavaObfuscation, String javaObfuscationStrength,
                           List<String> javaBlackList, List<String> javaWhiteList,
                           boolean enableNativeObfuscation,
                           boolean skidStringObfuscation, boolean skidNumberObfuscation,
                           boolean skidFlowObfuscation, boolean skidSdkInjection,
                           boolean skidVmHashing, boolean skidInvokeDynamicObfuscation,
                           FlowExceptionMode skidFlowExceptionMode,
                           JavaFlowSettings javaFlowSettings, RenamerConfig renamerConfig) {
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
        this.skidStringObfuscation = skidStringObfuscation;
        this.skidNumberObfuscation = skidNumberObfuscation;
        this.skidFlowObfuscation = skidFlowObfuscation;
        this.skidSdkInjection = skidSdkInjection;
        this.skidVmHashing = skidVmHashing;
        this.skidInvokeDynamicObfuscation = skidInvokeDynamicObfuscation;
        this.skidFlowExceptionMode = skidFlowExceptionMode;
        this.javaFlowSettings = javaFlowSettings == null ? JavaFlowSettings.createDefault() : javaFlowSettings;
        this.renamerConfig = renamerConfig == null ? RenamerConfig.createDisabled() : renamerConfig;
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
    public boolean isSkidStringObfuscation() { return skidStringObfuscation; }
    public boolean isSkidNumberObfuscation() { return skidNumberObfuscation; }
    public boolean isSkidFlowObfuscation() { return skidFlowObfuscation; }
    public boolean isSkidSdkInjection() { return skidSdkInjection; }
    public boolean isSkidVmHashing() { return skidVmHashing; }
    public boolean isSkidInvokeDynamicObfuscation() { return skidInvokeDynamicObfuscation; }
    public FlowExceptionMode getSkidFlowExceptionMode() { return skidFlowExceptionMode; }
    public JavaFlowSettings getJavaFlowSettings() { return javaFlowSettings; }
    public RenamerConfig getRenamerConfig() { return renamerConfig; }

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
                "  skidStringObfuscation=%s,\n" +
                "  skidNumberObfuscation=%s,\n" +
                "  skidFlowObfuscation=%s,\n" +
                "  skidSdkInjection=%s,\n" +
                "  skidVmHashing=%s,\n" +
                "  skidInvokeDynamicObfuscation=%s,\n" +
                "  skidFlowExceptionMode=%s,\n" +
                "  javaFlowSettings=%s,\n" +
                "  renamerConfig=%s,\n" +
                "  protectionConfig=%s,\n" +
                "  antiDebugConfig=%s\n" +
                "}",
                inputJarPath, outputDir, platform, enableJavaObfuscation,
                enableNativeObfuscation, skidStringObfuscation, skidNumberObfuscation,
                skidFlowObfuscation, skidSdkInjection, skidVmHashing,
                skidInvokeDynamicObfuscation,
                skidFlowExceptionMode,
                javaFlowSettings,
                renamerConfig,
                protectionConfig, antiDebugConfig);
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
        private boolean skidStringObfuscation = true;
        private boolean skidNumberObfuscation = true;
        private boolean skidFlowObfuscation = true;
        private boolean skidSdkInjection = false;
        private boolean skidVmHashing = false;
        private boolean skidInvokeDynamicObfuscation = false;
        private FlowExceptionMode skidFlowExceptionMode = FlowExceptionMode.STANDARD;
        private JavaFlowSettings javaFlowSettings = JavaFlowSettings.createDefault();
        private RenamerConfig renamerConfig = RenamerConfig.createDisabled();

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

        public Builder setSkidStringObfuscation(boolean skidStringObfuscation) {
            this.skidStringObfuscation = skidStringObfuscation;
            return this;
        }

        public Builder setSkidNumberObfuscation(boolean skidNumberObfuscation) {
            this.skidNumberObfuscation = skidNumberObfuscation;
            return this;
        }

        public Builder setSkidFlowObfuscation(boolean skidFlowObfuscation) {
            this.skidFlowObfuscation = skidFlowObfuscation;
            return this;
        }

        public Builder setSkidSdkInjection(boolean skidSdkInjection) {
            this.skidSdkInjection = skidSdkInjection;
            return this;
        }

        public Builder setSkidVmHashing(boolean skidVmHashing) {
            this.skidVmHashing = skidVmHashing;
            return this;
        }

        public Builder setSkidInvokeDynamicObfuscation(boolean skidInvokeDynamicObfuscation) {
            this.skidInvokeDynamicObfuscation = skidInvokeDynamicObfuscation;
            return this;
        }

        public Builder setSkidFlowExceptionMode(FlowExceptionMode skidFlowExceptionMode) {
            this.skidFlowExceptionMode = skidFlowExceptionMode;
            return this;
        }

        public Builder setJavaFlowSettings(JavaFlowSettings javaFlowSettings) {
            if (javaFlowSettings != null) {
                this.javaFlowSettings = javaFlowSettings;
            }
            return this;
        }
        
        public Builder setRenamerConfig(RenamerConfig renamerConfig) {
            if (renamerConfig != null) {
                this.renamerConfig = renamerConfig;
            }
            return this;
        }

        public ObfuscatorConfig build() {
            if (inputJarPath == null || outputDir == null) {
                throw new IllegalArgumentException("Input JAR path and output directory are required");
            }
            return new ObfuscatorConfig(inputJarPath, outputDir, inputLibs, blackList, whiteList,
                    plainLibName, customLibraryDirectory, platform, useAnnotations, generateDebugJar,
                    protectionConfig, antiDebugConfig, enableJavaObfuscation, javaObfuscationStrength,
                    javaBlackList, javaWhiteList, enableNativeObfuscation,
                    skidStringObfuscation, skidNumberObfuscation,
                    skidFlowObfuscation, skidSdkInjection, skidVmHashing,
                    skidInvokeDynamicObfuscation, skidFlowExceptionMode, javaFlowSettings, renamerConfig);
        }
    }
}
