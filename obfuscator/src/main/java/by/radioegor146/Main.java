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

            new NativeObfuscator().process(jarFile.toPath(), Paths.get(outputDirectory),
                    libs, blackList, whiteList, libraryName, customLibraryDirectory, platform, useAnnotations, generateDebugJar,
                    enableVirtualization, enableJit, flattenControlFlow);

            return 0;
        }
    }

    public static void main(String[] args) throws IOException {
        System.exit(new CommandLine(new NativeObfuscatorRunner())
                .setCaseInsensitiveEnumValuesAllowed(true).execute(args));
    }
}
