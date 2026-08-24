// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * Builds a Maven-style {@code <artifact>-<version>-sources.jar} by zipping the project's source
 * directories. Entries are stored relative to each root ({@code src/main/java/com/foo/Bar.java}
 * becomes {@code com/foo/Bar.java} in the archive). Deterministic: entries are sorted and every
 * mtime — the manifest's included — is pinned to {@link #ENTRY_TIME}.
 */
public final class SourcesJar {

    /**
     * 1980-02-01T00:00:00Z — the one pinned instant every jk archive writer stamps entries with.
     * Applied via {@link ZipEntry#setTimeLocal}, never {@code setTime}: setTime's DOS-time
     * conversion runs through the JVM's default timezone, so the same inputs would produce
     * different bytes on a host with a different {@code $TZ}. The value is the zip epoch's first
     * month — anything before 1980 is unrepresentable in DOS time and costs an extended-timestamp
     * extra field (18 bytes) on every entry.
     */
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.ofEpochSecond(318_211_200L, 0, ZoneOffset.UTC);

    private SourcesJar() {}

    /**
     * Build a sources jar in memory from the given source roots. Empty or absent roots are skipped.
     */
    public static byte[] build(List<Path> sourceRoots) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Created-By", "jk");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // NOT `new JarOutputStream(baos, mf)`: the convenience constructor stamps the manifest
        // entry with System.currentTimeMillis(), so every rebuild would churn the first entry.
        try (JarOutputStream jos = new JarOutputStream(baos)) {
            ByteArrayOutputStream manifestBytes = new ByteArrayOutputStream();
            mf.write(manifestBytes);
            jos.putNextEntry(pinned("META-INF/MANIFEST.MF"));
            jos.write(manifestBytes.toByteArray());
            jos.closeEntry();
            for (Path root : sourceRoots) {
                if (!Files.isDirectory(root)) continue;
                appendTree(jos, root);
            }
        }
        return baos.toByteArray();
    }

    private static ZipEntry pinned(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(ENTRY_TIME);
        return entry;
    }

    private static void appendTree(JarOutputStream jos, Path root) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(entries::add);
        }
        Collections.sort(entries);
        for (Path file : entries) {
            String name = root.relativize(file).toString().replace('\\', '/');
            jos.putNextEntry(pinned(name));
            Files.copy(file, jos);
            jos.closeEntry();
        }
    }
}
