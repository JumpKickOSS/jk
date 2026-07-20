// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * The shared reproducible-jar entry writer for the packagers ({@link JarPackager},
 * {@link AssemblyPackager}). Every entry's timestamp is pinned to a fixed epoch via
 * {@link JarEntry#setTimeLocal} — NOT {@link JarEntry#setTime}, whose DOS-time conversion is
 * timezone-sensitive — so identical inputs yield byte-identical jars regardless of build host,
 * clock, or {@code $TZ}. Extracted because both packagers had duplicated the exact same entry,
 * manifest, and build-stamp handling.
 */
final class DeterministicJar {

    private DeterministicJar() {}

    /** A {@link JarEntry} for {@code name} stamped with the fixed {@code epochSeconds} time. */
    static JarEntry entry(String name, long epochSeconds) {
        JarEntry entry = new JarEntry(name);
        entry.setTimeLocal(LocalDateTime.ofEpochSecond(epochSeconds, 0, ZoneOffset.UTC));
        return entry;
    }

    /** Write {@code data} as a single entry. */
    static void writeEntry(JarOutputStream jos, String name, byte[] data, long epochSeconds) throws IOException {
        jos.putNextEntry(entry(name, epochSeconds));
        jos.write(data);
        jos.closeEntry();
    }

    /** Write an entry by copying {@code file}'s bytes. */
    static void writeEntry(JarOutputStream jos, String name, Path file, long epochSeconds) throws IOException {
        jos.putNextEntry(entry(name, epochSeconds));
        Files.copy(file, jos);
        jos.closeEntry();
    }

    /** Write an entry by draining {@code in} (closed here) — constant memory for large entries. */
    static void writeEntryStreaming(JarOutputStream jos, String name, InputStream in, long epochSeconds)
            throws IOException {
        jos.putNextEntry(entry(name, epochSeconds));
        try (in) {
            in.transferTo(jos);
        }
        jos.closeEntry();
    }

    /**
     * Write {@code manifest} as an entry. Callers write it first, with the fixed timestamp, rather
     * than via {@code new JarOutputStream(out, manifest)} — the convenience constructor stamps the
     * manifest with the current time, which is the one thing that churns an otherwise-stable jar.
     */
    static void writeManifest(JarOutputStream jos, Manifest manifest, long epochSeconds) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        manifest.write(buf);
        writeEntry(jos, "META-INF/MANIFEST.MF", buf.toByteArray(), epochSeconds);
    }

    /** jk's freshness/skip stamps — build-host metadata that must never enter a jar. */
    static boolean isBuildStamp(String name) {
        return name.endsWith(".jstamp") || name.endsWith(".kstamp") || name.endsWith(".test-stamp");
    }
}
