package dev.skidfuscator.obfuscator.transform.impl.sdk;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.creator.SkidApplicationClassSource;
import dev.skidfuscator.obfuscator.event.annotation.Listen;
import dev.skidfuscator.obfuscator.event.impl.transform.skid.InitSkidTransformEvent;
import dev.skidfuscator.obfuscator.phantom.jphantom.PhantomJarDownloader;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;
import dev.skidfuscator.obfuscator.util.MapleJarUtil;
import org.mapleir.app.service.ApplicationClassSource;
import org.mapleir.app.service.LibraryClassSource;
import org.mapleir.asm.ClassNode;
import org.topdank.byteengineer.commons.data.JarClassData;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static dev.skidfuscator.obfuscator.util.JdkDownloader.CACHE_DIR;

public class SdkInjectorTransformer extends AbstractTransformer {
    public SdkInjectorTransformer(Skidfuscator skidfuscator) {
        super(skidfuscator, "SDK");
    }

    @Listen
    void handle(final InitSkidTransformEvent event) {
        try {
            // Create cache directory if it doesn't exist
            if (!Files.exists(CACHE_DIR)) {
                Files.createDirectories(CACHE_DIR);
            }

            // Extract SDK jar from resources to cache
            File sdkFile = CACHE_DIR.resolve("sdk.jar").toFile();
            extractSdkArchive(sdkFile);

            // Import the SDK jar classes
            final PhantomJarDownloader<ClassNode> downloader = MapleJarUtil.importPhantomJar(
                sdkFile,
                skidfuscator
            );

            // Add SDK classes to the input jar
            for (JarClassData classData : downloader.getJarContents().getClassContents()) {
                skidfuscator.getJarContents().getClassContents().add(classData);
            }
            ApplicationClassSource library = new SkidApplicationClassSource("Library",
                    false,
                    downloader.getJarContents(),
                    skidfuscator
            );

            skidfuscator.getClassSource().addLibraries(new LibraryClassSource(library, 5));

        } catch (IOException e) {
            throw new RuntimeException("Failed to inject SDK", e);
        }
    }

    private void extractSdkArchive(File sdkFile) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("resources/sdk.jar")) {
            if (is != null) {
                try (OutputStream os = new FileOutputStream(sdkFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }
                return;
            }
        }

        URL sdkRoot = getClass().getClassLoader().getResource("sdk");
        if (sdkRoot == null) {
            throw new IOException("Could not locate sdk classes on the classpath");
        }

        try {
            URI sdkUri = sdkRoot.toURI();
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(sdkFile))) {
                if ("jar".equalsIgnoreCase(sdkUri.getScheme())) {
                    String uriStr = sdkUri.toString();
                    int bangIndex = uriStr.indexOf("!/");
                    URI jarUri = URI.create(uriStr.substring(0, bangIndex));
                    String internalPath = uriStr.substring(bangIndex + 1);

                    FileSystem fs = null;
                    boolean closeFs = false;
                    try {
                        try {
                            fs = FileSystems.newFileSystem(jarUri, Collections.emptyMap());
                            closeFs = true;
                        } catch (java.nio.file.FileSystemAlreadyExistsException ex) {
                            fs = FileSystems.getFileSystem(jarUri);
                        }
                        writeSdkEntries(fs.getPath(internalPath), jos);
                    } finally {
                        if (closeFs && fs != null) {
                            fs.close();
                        }
                    }
                } else {
                    Path rootPath = Path.of(sdkUri);
                    writeSdkEntries(rootPath, jos);
                }
            }
        } catch (URISyntaxException e) {
            throw new IOException("Failed to resolve sdk resource location", e);
        }
    }

    private void writeSdkEntries(Path root, JarOutputStream jos) throws IOException {
        if (root == null || !Files.exists(root)) {
            throw new IOException("SDK path does not exist: " + root);
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .forEach(path -> {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        String entryName = "sdk/" + relative;
                        try {
                            jos.putNextEntry(new JarEntry(entryName));
                            Files.copy(path, jos);
                            jos.closeEntry();
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    });
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException io) {
                throw io;
            }
            throw ex;
        }
    }
}
