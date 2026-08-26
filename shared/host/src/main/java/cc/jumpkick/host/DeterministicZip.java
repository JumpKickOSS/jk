// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The entry writer behind every archive jk produces — thin jar, fat jar, Boot jar, minified jar,
 * Quarkus fast-jar, sources jar, AOT staging jar, AAR, APK and AAB base module.
 *
 * <p>Every entry's time is pinned through {@link ZipEntry#setTimeLocal}, never
 * {@link ZipEntry#setTime}: setTime converts to DOS time through the JVM's default timezone, so
 * the same inputs would produce different bytes on a host with a different {@code $TZ}. Identical
 * inputs must yield byte-identical archives, because raw-archive fingerprints key the action
 * cache. The build script's {@code checkSingleArchiveInstant} keeps both spellings, and the epoch
 * value itself, out of every other file.
 */
public final class DeterministicZip {

    /**
     * 1980-02-01T00:00:00Z — the instant every jk archive stamps entries with, and the floor for
     * any other. DOS time, which a ZIP local header stores, cannot represent anything earlier; a
     * pre-1980 fixed time (epoch 0) makes the JDK preserve the value in an extended-timestamp
     * extra field on every entry — 18 wasted bytes in both the local header and the central
     * directory, for a timestamp nobody reads.
     */
    public static final long EPOCH_SECONDS = 318_211_200L;

    /** The writer for an archive with no build-supplied timestamp: everything at the epoch. */
    public static final DeterministicZip PINNED = new DeterministicZip(EPOCH_SECONDS);

    private final LocalDateTime instant;

    /** A writer stamping entries at {@code epochSeconds}, clamped up to {@link #EPOCH_SECONDS}. */
    public DeterministicZip(long epochSeconds) {
        this.instant = LocalDateTime.ofEpochSecond(Math.max(epochSeconds, EPOCH_SECONDS), 0, ZoneOffset.UTC);
    }

    /** An entry for {@code name}, time pinned, ready for {@code putNextEntry}. */
    public JarEntry entry(String name) {
        JarEntry entry = new JarEntry(name);
        entry.setTimeLocal(instant);
        return entry;
    }

    /** Write {@code data} as one deflated entry. */
    public void writeEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(entry(name));
        zip.write(data);
        zip.closeEntry();
    }

    /**
     * Write {@code data} as one entry with an explicit method. {@link ZipEntry#STORED} needs size
     * and CRC-32 up front; both are computed here so no caller has to remember.
     */
    public void writeEntry(ZipOutputStream zip, String name, byte[] data, int method) throws IOException {
        JarEntry entry = entry(name);
        entry.setMethod(method);
        if (method == ZipEntry.STORED) {
            entry.setSize(data.length);
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    /** Write one entry by copying {@code file}'s bytes. */
    public void writeEntry(ZipOutputStream zip, String name, Path file) throws IOException {
        zip.putNextEntry(entry(name));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    /** Write one entry by draining {@code in}, which is closed here — constant memory. */
    public void writeEntryStreaming(ZipOutputStream zip, String name, InputStream in) throws IOException {
        // `in` is evaluated at the call site, so it is already open on entry: take ownership
        // first, or a throwing putNextEntry (duplicate entry name) leaks the caller's file
        // descriptor — it never reaches the try below.
        try (in) {
            zip.putNextEntry(entry(name));
            in.transferTo(zip);
        }
        zip.closeEntry();
    }

    /**
     * Write {@code file} as a STORED (uncompressed) entry. Spring Boot's nested-jar loader maps
     * {@code BOOT-INF/lib/*.jar} entries directly and cannot random-access a deflated one. STORED
     * needs size and CRC-32 up front, so the file is read twice — constant memory either way.
     */
    public void writeStored(ZipOutputStream zip, String name, Path file) throws IOException {
        long size = Files.size(file);
        CRC32 crc = new CRC32();
        try (InputStream in = new CheckedInputStream(Files.newInputStream(file), crc)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        JarEntry entry = entry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(size);
        entry.setCompressedSize(size);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(zip);
        }
        zip.closeEntry();
    }

    /**
     * Write {@code manifest} as an entry — callers do this first, instead of
     * {@code new JarOutputStream(out, manifest)}. That convenience constructor stamps the manifest
     * with the current time, which is the one thing that churns an otherwise-stable jar.
     */
    public void writeManifest(ZipOutputStream zip, Manifest manifest) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        manifest.write(buf);
        writeEntry(zip, JarFile.MANIFEST_NAME, buf.toByteArray());
    }

    /** Emit the directory entry {@code dir} once; {@code emitted} is what the archive carries. */
    public void writeDir(ZipOutputStream zip, String dir, Set<String> emitted) throws IOException {
        if (!emitted.add(dir)) return;
        zip.putNextEntry(entry(dir));
        zip.closeEntry();
    }

    /**
     * Directory entries for every ancestor of {@code name}, parents first, each once.
     *
     * <p>Frameworks that enumerate resource <em>directories</em> from the classpath (Micronaut's
     * SoftServiceLoader over {@code META-INF/micronaut/...}) resolve them through the archive's
     * directory entries; an archive holding file entries only makes those lookups come back empty.
     * Thin, fat and minified jars owe the same contract, so they share one implementation.
     */
    public void writeParentDirs(ZipOutputStream zip, String name, Set<String> emitted) throws IOException {
        int slash = -1;
        while ((slash = name.indexOf('/', slash + 1)) >= 0) {
            writeDir(zip, name.substring(0, slash + 1), emitted);
        }
    }
}
