// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
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

    /**
     * 1980-02-01T00:00:00Z — the canonical fixed jar timestamp (Gradle uses the same instant).
     * DOS time, which the ZIP local header stores, cannot represent anything before 1980; a
     * pre-1980 fixed time (epoch 0) makes the JDK preserve the value in an extended-timestamp
     * extra field on EVERY entry — 18 wasted bytes per entry in both the local header and the
     * central directory, for a timestamp nobody reads.
     */
    public static final long DEFAULT_EPOCH_SECONDS = 318_211_200L;

    /** A {@link JarEntry} for {@code name} stamped with the fixed {@code epochSeconds} time. */
    static JarEntry entry(String name, long epochSeconds) {
        // Clamp pre-DOS-epoch times to the canonical instant so no caller can reintroduce the
        // extra-field bloat; every fixed timestamp below 1980 means "the deterministic default".
        if (epochSeconds < DEFAULT_EPOCH_SECONDS) epochSeconds = DEFAULT_EPOCH_SECONDS;
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
        // `in` is evaluated at the call site, so it is already open on entry: take ownership
        // first, or a throwing putNextEntry (duplicate entry name) leaks the caller's file
        // descriptor — it never reaches the try below.
        try (in) {
            jos.putNextEntry(entry(name, epochSeconds));
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

    /**
     * Directory entries for every ancestor of {@code name}, parents first, each once —
     * {@code dirs} accumulates what has already been emitted across a whole jar.
     *
     * <p>Frameworks that enumerate resource <em>directories</em> from the classpath (Micronaut's
     * SoftServiceLoader over {@code META-INF/micronaut/...}) resolve them via the jar's directory
     * entries; a jar with file entries only makes those lookups come back empty. Thin
     * and fat jars owe the same contract, so they share one implementation.
     */
    static void writeParentDirs(JarOutputStream jos, String name, long epochSeconds, Set<String> dirs)
            throws IOException {
        int slash = -1;
        while ((slash = name.indexOf('/', slash + 1)) >= 0) {
            String dir = name.substring(0, slash + 1);
            if (dirs.add(dir)) {
                jos.putNextEntry(entry(dir, epochSeconds));
                jos.closeEntry();
            }
        }
    }

    /** jk's freshness/skip stamps — build-host metadata that must never enter a jar. */
    static boolean isBuildStamp(String name) {
        return name.endsWith(".jstamp") || name.endsWith(".kstamp") || name.endsWith(".test-stamp");
    }
}
