// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * A runnable copy of a thin jar: its manifest gains a {@code Class-Path} naming jars relative to
 * the jar's own directory, and a {@code Main-Class} when it has none, so {@code java -jar} runs it
 * from wherever the jar and its lib directory are moved together.
 */
public final class ClassPathJar {

    private static final DeterministicZip ZIP = DeterministicZip.PINNED;

    private ClassPathJar() {}

    /**
     * Copy {@code from} to {@code to} with {@code classPath} (relative URLs, forward slashes) as its
     * {@code Class-Path}, and {@code mainClass} as its {@code Main-Class} unless the source names
     * one or {@code mainClass} is empty. An empty {@code classPath} leaves the attribute out.
     *
     * <p>Every entry, the manifest included, is pinned by {@link DeterministicZip}, so the same
     * input yields the same bytes. That is why the manifest is written by hand rather than through
     * {@code new JarOutputStream(out, manifest)}, which stamps it with the wall clock.
     */
    public static void write(Path from, Path to, List<String> classPath, String mainClass) throws IOException {
        try (var jarIn = new JarInputStream(Files.newInputStream(from))) {
            Manifest manifest = jarIn.getManifest();
            if (manifest == null) manifest = new Manifest();
            Attributes main = manifest.getMainAttributes();
            main.putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0");
            if (main.getValue(Attributes.Name.MAIN_CLASS) == null && !mainClass.isEmpty()) {
                main.put(Attributes.Name.MAIN_CLASS, mainClass);
            }
            if (classPath.isEmpty()) {
                main.remove(Attributes.Name.CLASS_PATH);
            } else {
                main.put(Attributes.Name.CLASS_PATH, String.join(" ", classPath));
            }
            Path parent = to.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (var jarOut = new JarOutputStream(DeterministicZip.archiveStream(to))) {
                ZIP.writeManifest(jarOut, manifest);
                JarEntry entry;
                while ((entry = jarIn.getNextJarEntry()) != null) {
                    if (entry.getName().equals(JarFile.MANIFEST_NAME)) continue;
                    // jarIn stays open for the next entry, so this must not be writeEntryStreaming.
                    jarOut.putNextEntry(ZIP.entry(entry.getName()));
                    jarIn.transferTo(jarOut);
                    jarOut.closeEntry();
                }
            }
        }
    }

    /** {@code names} under {@code dir}: the {@code Class-Path} of a jar beside that directory. */
    public static List<String> under(String dir, List<String> names) {
        return names.stream().map(n -> dir + "/" + n).toList();
    }
}
