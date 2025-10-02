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
import org.topdank.byteio.in.SingleJarDownloader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static dev.skidfuscator.obfuscator.util.JdkDownloader.CACHE_DIR;

public class SdkInjectorTransformer extends AbstractTransformer {
    private static final List<String> SDK_CLASS_NAMES = Arrays.asList(
            "sdk.Access",
            "sdk.ByteBufferAccess",
            "sdk.CharSequenceAccess",
            "sdk.CompactLatin1CharSequenceAccess",
            "sdk.DualHashFunction",
            "sdk.HotSpotPrior7u6StringHash",
            "sdk.LongHashFunction",
            "sdk.LongTupleHashFunction",
            "sdk.Maths",
            "sdk.ModernCompactStringHash",
            "sdk.ModernHotSpotStringHash",
            "sdk.Primitives",
            "sdk.SDK",
            "sdk.StringHash",
            "sdk.UnknownJvmStringHash",
            "sdk.UnsafeAccess",
            "sdk.Util",
            "sdk.XXH3"
    );

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

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(sdkFile))) {
            for (String className : SDK_CLASS_NAMES) {
                String entryName = className.replace('.', '/') + ".class";
                try (InputStream classStream = getClass().getClassLoader().getResourceAsStream(entryName)) {
                    if (classStream == null) {
                        throw new IOException("Could not locate SDK class " + className + " on the classpath");
                    }
                    jos.putNextEntry(new JarEntry(entryName));
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = classStream.read(buffer)) != -1) {
                        jos.write(buffer, 0, bytesRead);
                    }
                    jos.closeEntry();
                }
            }
        }
    }
}
