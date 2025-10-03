package by.radioegor146;

import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public class Main {

    private static final String VERSION = "3.5.4r";

    @CommandLine.Command(name = "native-obfuscator", mixinStandardHelpOptions = true, version = "native-obfuscator " + VERSION,
            description = "Transpiles .jar file into .cpp files and generates output .jar file")
    private static class NativeObfuscatorRunner implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Jar file to transpile")
        private File jarFile;

        @CommandLine.Parameters(index = "1", description = "Output directory")
        private String outputDirectory;

        @CommandLine.Option(names = {"-l", "--libraries"}, description = "Directory for dependent libraries")
        private File librariesDirectory;

        @CommandLine.Option(names = {"-b", "--black-list"}, description = "File with a list of blacklist classes/methods for transpilation")
        private File blackListFile;

        @CommandLine.Option(names = {"-w", "--white-list"}, description = "File with a list of whitelist classes/methods for transpilation")
        private File whiteListFile;

        @CommandLine.Option(names = {"--plain-lib-name"}, description = "Plain library name for LoaderPlain")
        private String libraryName;

        @CommandLine.Option(names = {"--custom-lib-dir"}, description = "Custom library directory for LoaderUnpack")
        private String customLibraryDirectory;

        @CommandLine.Option(names = {"-p", "--platform"}, defaultValue = "hotspot",
                description = "Target platform: hotspot - standard standalone HotSpot JRE, std_java - java standard, android - for Android builds (w/o DefineClass)")
        private Platform platform;

        @CommandLine.Option(names = {"-a", "--annotations"}, description = "Use annotations to ignore/include native obfuscation")
        private boolean useAnnotations;

        @CommandLine.Option(names = {"--debug"}, description = "Enable generation of debug .jar file (non-executable)")
        private boolean generateDebugJar;

        @CommandLine.Option(names = {"--enable-virtualization"}, description = "Enable VM-based code virtualization for enhanced protection")
        private boolean enableVirtualization;

        @CommandLine.Option(names = {"--enable-jit"}, description = "Enable JIT compilation in virtualized methods for better performance")
        private boolean enableJit;

        @CommandLine.Option(names = {"--flatten-control-flow"}, description = "Enable control flow flattening in native methods")
        private boolean flattenControlFlow;

        @CommandLine.Option(names = {"--obfuscate-strings"}, negatable = true, defaultValue = "true",
                description = "Encrypt string literals stored in the native string pool")
        private boolean obfuscateStrings = true;

        @CommandLine.Option(names = {"--obfuscate-constants"}, negatable = true, defaultValue = "true",
                description = "Encode primitive LDC constants and decode them at runtime")
        private boolean obfuscateConstants = true;

        @CommandLine.Option(names = {"--enable-java-obfuscation"}, description = "Enable Java-layer obfuscation with control flow flattening")
        private boolean enableJavaObfuscation;

        @CommandLine.Option(names = {"--java-obf-strength"}, defaultValue = "MEDIUM", description = "Java obfuscation strength: LOW, MEDIUM, HIGH")
        private String javaObfuscationStrength;

        @CommandLine.Option(names = {"-jb", "--java-black-list"}, description = "File with a list of blacklist classes/methods for Java obfuscation")
        private File javaBlackListFile;

        @CommandLine.Option(names = {"-jw", "--java-white-list"}, description = "File with a list of whitelist classes/methods for Java obfuscation")
        private File javaWhiteListFile;

        @CommandLine.Option(names = {"--enable-native-obfuscation"}, defaultValue = "true", description = "Enable native obfuscation (default: true)")
        private boolean enableNativeObfuscation;

        @CommandLine.Option(names = {"--enable-anti-debug"}, description = "Enable anti-debug protection")
        private boolean enableAntiDebug;

        @CommandLine.Option(names = {"--ghot-struct-nullification"}, description = "Enable gHotSpotVMStructs nullification")
        private boolean gHotStructNullification;

        @CommandLine.Option(names = {"--debugger-detection"}, description = "Enable debugger detection")
        private boolean debuggerDetection;

        @CommandLine.Option(names = {"--vm-protection"}, description = "Enable VM protection")
        private boolean vmProtection;

        @CommandLine.Option(names = {"--anti-tamper"}, description = "Enable anti-tamper checks")
        private boolean antiTamper;
        @CommandLine.Option(names = {"--anti-debug-api-checks"}, negatable = true,
                description = "Toggle API-based debugger detection (default: enabled when debugger detection is enabled)")
        private Boolean antiDebugApiChecks;

        @CommandLine.Option(names = {"--anti-debug-tracer-check"}, negatable = true,
                description = "Toggle tracer PID checks (default: enabled when debugger detection is enabled)")
        private Boolean antiDebugTracerCheck;

        @CommandLine.Option(names = {"--anti-debug-ptrace-check"}, negatable = true,
                description = "Toggle ptrace self-test detection (default: enabled when debugger detection is enabled)")
        private Boolean antiDebugPtraceCheck;

        @CommandLine.Option(names = {"--anti-debug-process-scan"}, negatable = true,
                description = "Toggle debugger process scanning heuristics (default: enabled when debugger detection is enabled)")
        private Boolean antiDebugProcessScan;

        @CommandLine.Option(names = {"--anti-debug-module-scan"}, negatable = true,
                description = "Toggle suspicious module scanning (default: enabled when debugger detection is enabled)")
        private Boolean antiDebugModuleScan;

        @CommandLine.Option(names = {"--anti-debug-env-scan"}, negatable = true,
                description = "Toggle environment-based debugger detection (default: enabled when debugger detection is enabled)")
        private Boolean antiDebugEnvScan;

        @CommandLine.Option(names = {"--anti-debug-timing-check"}, negatable = true,
                description = "Toggle timing anomaly detection (default: enabled when debugger detection is enabled)")
        private Boolean antiDebugTimingCheck;

        @CommandLine.Option(names = {"--anti-debug-agent-blocking"}, negatable = true,
                description = "Toggle JVMTI agent blocking hooks (default: follows --vm-protection)")
        private Boolean antiDebugAgentBlocking;

        @CommandLine.Option(names = {"--anti-debug-register-scrub"}, negatable = true,
                description = "Toggle hardware breakpoint scrubbing (default: enabled when debugger detection is enabled)")
        private Boolean antiDebugRegisterScrub;

        @CommandLine.Option(names = {"--anti-debug-logging"}, negatable = true,
                description = "Toggle verbose anti-debug debug logging output")
        private Boolean antiDebugLogging;


        @Override
        public Integer call() throws Exception {
            List<Path> libs = new ArrayList<>();
            if (librariesDirectory != null) {
                Files.walk(librariesDirectory.toPath(), FileVisitOption.FOLLOW_LINKS)
                        .filter(f -> f.toString().endsWith(".jar") || f.toString().endsWith(".zip"))
                        .forEach(libs::add);
            }

            List<String> blackList = new ArrayList<>();
            if (blackListFile != null) {
                blackList = Files.readAllLines(blackListFile.toPath(), StandardCharsets.UTF_8);
            }

            List<String> whiteList = null;
            if (whiteListFile != null) {
                whiteList = Files.readAllLines(whiteListFile.toPath(), StandardCharsets.UTF_8);
            }

            // Java obfuscation configuration
            List<String> javaBlackList = new ArrayList<>();
            if (javaBlackListFile != null) {
                javaBlackList = Files.readAllLines(javaBlackListFile.toPath(), StandardCharsets.UTF_8);
            }

            List<String> javaWhiteList = new ArrayList<>();
            if (javaWhiteListFile != null) {
                javaWhiteList = Files.readAllLines(javaWhiteListFile.toPath(), StandardCharsets.UTF_8);
            }

            // Create protection configuration
            ProtectionConfig protectionConfig = new ProtectionConfig(enableVirtualization, enableJit, flattenControlFlow,
                    obfuscateStrings, obfuscateConstants);

            // Create anti-debug configuration
            AntiDebugConfig.Builder antiDebugBuilder = new AntiDebugConfig.Builder()
                    .setGHotSpotVMStructsNullification(gHotStructNullification)
                    .setDebuggerDetection(debuggerDetection)
                    .setVmProtectionEnabled(vmProtection)
                    .setAntiTamperEnabled(antiTamper);

            if (antiDebugAgentBlocking != null) {
                antiDebugBuilder.setJvmtiAgentBlocking(antiDebugAgentBlocking);
            }
            if (antiDebugApiChecks != null) {
                antiDebugBuilder.setDebuggerApiChecks(antiDebugApiChecks);
            }
            if (antiDebugTracerCheck != null) {
                antiDebugBuilder.setDebuggerTracerCheck(antiDebugTracerCheck);
            }
            if (antiDebugPtraceCheck != null) {
                antiDebugBuilder.setDebuggerPtraceCheck(antiDebugPtraceCheck);
            }
            if (antiDebugProcessScan != null) {
                antiDebugBuilder.setDebuggerProcessScan(antiDebugProcessScan);
            }
            if (antiDebugModuleScan != null) {
                antiDebugBuilder.setDebuggerModuleScan(antiDebugModuleScan);
            }
            if (antiDebugEnvScan != null) {
                antiDebugBuilder.setDebuggerEnvironmentScan(antiDebugEnvScan);
            }
            if (antiDebugTimingCheck != null) {
                antiDebugBuilder.setDebuggerTimingCheck(antiDebugTimingCheck);
            }
            if (antiDebugRegisterScrub != null) {
                antiDebugBuilder.setDebugRegisterScrubbing(antiDebugRegisterScrub);
            }
            if (antiDebugLogging != null) {
                antiDebugBuilder.setDebugLoggingEnabled(antiDebugLogging);
            }

            AntiDebugConfig antiDebugConfig = antiDebugBuilder.build();

            // Create comprehensive configuration
            ObfuscatorConfig config = new ObfuscatorConfig.Builder()
                    .setInputJarPath(jarFile.toPath())
                    .setOutputDir(Paths.get(outputDirectory))
                    .setInputLibs(libs)
                    .setBlackList(blackList)
                    .setWhiteList(whiteList)
                    .setPlainLibName(libraryName)
                    .setCustomLibraryDirectory(customLibraryDirectory)
                    .setPlatform(platform)
                    .setUseAnnotations(useAnnotations)
                    .setGenerateDebugJar(generateDebugJar)
                    .setProtectionConfig(protectionConfig)
                    .setAntiDebugConfig(antiDebugConfig)
                    .setEnableJavaObfuscation(enableJavaObfuscation)
                    .setJavaObfuscationStrength(javaObfuscationStrength)
                    .setJavaBlackList(javaBlackList)
                    .setJavaWhiteList(javaWhiteList)
                    .setEnableNativeObfuscation(enableNativeObfuscation)
                    .build();

            // Validate configuration
            config.validateAndWarn();

            // Process with new configuration structure
            new NativeObfuscator().process(config);

            return 0;
        }
    }

    public static void main(String[] args) throws IOException {
        System.exit(new CommandLine(new NativeObfuscatorRunner())
                .setCaseInsensitiveEnumValuesAllowed(true).execute(args));
    }
}
