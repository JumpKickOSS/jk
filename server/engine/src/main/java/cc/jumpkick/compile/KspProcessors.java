// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Splits {@code [processor-dependencies]} by registered SPI: KSP
 * {@code SymbolProcessorProvider} vs javac {@code Processor}. Dual-registered jars count as KSP only.
 */
public final class KspProcessors {

    private static final String KSP_SERVICE =
            "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider";

    private KspProcessors() {}

    /** The processor-path split: KSP jars and javac-only jars, in declaration order. */
    public record Split(List<Path> ksp, List<Path> javac) {}

    public static Split split(List<Path> processorPath) {
        List<Path> ksp = new ArrayList<>();
        List<Path> javac = new ArrayList<>();
        for (Path jar : processorPath) {
            if (isKspProcessor(jar)) ksp.add(jar);
            else javac.add(jar);
        }
        return new Split(ksp, javac);
    }

    /** True when {@code jar} registers a KSP {@code SymbolProcessorProvider}. */
    public static boolean isKspProcessor(Path jar) {
        if (!Files.isRegularFile(jar)) return false;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(KSP_SERVICE);
            return entry != null;
        } catch (IOException e) {
            return false; // unreadable jar — let javac surface the real error downstream
        }
    }
}
