// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import sbt.internal.inc.FileAnalysisStore;
import xsbti.compile.AnalysisContents;
import xsbti.compile.AnalysisStore;

/**
 * The incremental workdir: where the Zinc analysis lives, and every read and write of it.
 *
 * <p>This exists because of the invariant the size baseline named — the analysis store and the
 * compile that writes it must agree on the key — and it is the way to <em>keep</em> that agreement,
 * not a way around it. Before, {@code "zinc"} and {@code "aggregating"} were bare string literals
 * retyped at six sites across two methods, and every read took a {@code (store, analysisFile)} pair
 * as two independent parameters that nothing forced to describe the same file. Here the path is
 * derived once and the store is derived from the path, so the pair cannot disagree by construction.
 *
 * <p>The Windows retry ladder is here for the same reason: it is the analysis file's own lifecycle.
 */
final class ZincWorkdir {

    /** Windows may deny a delete or a replace while another handle lingers; POSIX EACCES is permanent. */
    private static final int LOCK_ATTEMPTS = 8;

    private final Path analysisFile;
    private final Path aggregatingMarker;
    private final Path analysisOffMarker;

    private ZincWorkdir(Path workdir) {
        this.analysisFile = workdir.resolve("zinc");
        this.aggregatingMarker = workdir.resolve("aggregating");
        this.analysisOffMarker = workdir.resolve("analysis-off");
    }

    static ZincWorkdir of(Path workdir) {
        return new ZincWorkdir(workdir);
    }

    /** Zinc's own store over {@link #analysisFile} — the only place the two are paired. */
    AnalysisStore store() {
        return FileAnalysisStore.binary(analysisFile.toFile());
    }

    Path analysisFile() {
        return analysisFile;
    }

    boolean hasAnalysis() {
        return Files.isRegularFile(analysisFile);
    }

    /**
     * True when the last run used an aggregating annotation processor. Such a processor sees every
     * source every round, so no partial invalidation is sound and the next run must be full.
     */
    boolean aggregating() {
        return Files.isRegularFile(aggregatingMarker);
    }

    void markAggregating(boolean aggregating) throws IOException {
        if (aggregating) {
            Files.writeString(aggregatingMarker, "1\n");
        } else {
            Files.deleteIfExists(aggregatingMarker);
        }
    }

    /** An aggregating previous run invalidates the analysis outright, so drop it. */
    void discardAnalysisIfAggregating() throws IOException {
        if (aggregating()) Files.deleteIfExists(analysisFile);
    }

    /**
     * The type Zinc's Java analysis could not load with this worker's JDK the last time it ran here
     * over a classpath digesting to {@code classpathDigest}, or empty when the analysis runs. While
     * set, every compile in this workdir over that classpath is a full javac run with no analysis.
     * A marker left by another classpath is stale — the entry whose supertype could not be loaded
     * may have changed — so it is dropped and the analysis is tried again; {@code jk clean} takes the
     * marker with the workdir.
     */
    Optional<String> analysisOff(String classpathDigest) {
        try {
            if (!Files.isRegularFile(analysisOffMarker)) return Optional.empty();
            List<String> lines = Files.readAllLines(analysisOffMarker);
            if (lines.size() >= 2 && lines.get(0).strip().equals(classpathDigest)) {
                return Optional.of(lines.get(1).strip());
            }
            Files.deleteIfExists(analysisOffMarker);
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Turn the analysis off for this workdir under the classpath digesting to {@code
     * classpathDigest}, naming the type it could not load, and drop what it wrote.
     */
    void markAnalysisOff(String missingType, String classpathDigest) throws IOException {
        Files.createDirectories(analysisOffMarker.getParent());
        Files.writeString(analysisOffMarker, classpathDigest + "\n" + missingType + "\n");
        tryDeleteAnalysis();
    }

    /**
     * What a compile classpath looks like right now, as one digest: each entry's path, then for a
     * jar its size and mtime and for a classes directory the sorted (relative path, size, mtime) of
     * every file in it; an absent entry digests as absent. Cheap against reading the bytes, and
     * different whenever a compiler rewrote, added or deleted a class an entry holds or the lock
     * moved a jar.
     */
    static String classpathDigest(List<Path> classpath) throws IOException {
        MessageDigest digest = Hashing.newSha256();
        for (Path entry : classpath) {
            digest.update((entry.toAbsolutePath().normalize() + "\n").getBytes(StandardCharsets.UTF_8));
            if (Files.isDirectory(entry)) {
                List<String> listing = new ArrayList<>();
                PathUtil.forEachRegularFile(
                        entry,
                        (file, attrs) -> listing.add(
                                entry.relativize(file).toString().replace(File.separatorChar, '/') + shape(attrs)));
                Collections.sort(listing);
                for (String line : listing) digest.update((line + "\n").getBytes(StandardCharsets.UTF_8));
            } else if (Files.isRegularFile(entry)) {
                digest.update((shape(Files.readAttributes(entry, BasicFileAttributes.class)) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            } else {
                digest.update("absent\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        return Hashing.hex(digest.digest());
    }

    private static String shape(BasicFileAttributes attrs) {
        return "\t" + attrs.size() + "\t" + attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS);
    }

    /**
     * Read the persisted Zinc analysis, tolerating corruption. A truncated file or a schema bump
     * (e.g. a Zinc dependency upgrade) must not fail every build until a manual {@code --rebuild}:
     * delete the unreadable file and report "no analysis" so the caller falls through to a clean
     * full compile.
     *
     * <p>Unreadability surfaces either way — a thrown parse error, or an empty {@link Optional} over
     * a file that plainly exists — and both mean the same thing, so both delete it. Left in place it
     * is a file every later {@code store.set} must replace and no read can ever use.
     *
     * <p>Do not call {@code store.get()} on a non-gzip file. Zinc opens a {@code FileInputStream}
     * then wraps it in {@link GZIPInputStream}; a bad magic throws in that constructor and never
     * closes the stream. Windows then refuses to delete or replace the analysis file.
     */
    Optional<AnalysisContents> readAnalysis(AnalysisStore store) {
        if (!Files.isRegularFile(analysisFile)) {
            return Optional.empty();
        }
        if (!gzipHeaderReadable(analysisFile)) {
            tryDeleteAnalysis();
            return Optional.empty();
        }
        try {
            Optional<AnalysisContents> got = store.get();
            if (got.isEmpty()) {
                tryDeleteAnalysis();
            }
            return got;
        } catch (RuntimeException e) {
            tryDeleteAnalysis();
            return Optional.empty();
        }
    }

    /**
     * Zinc's binary store is gzip. Opens and closes the file ourselves so a bad header cannot leak
     * a handle the way {@code store.get()} does. Shared with {@link ClasspathAnalyses}, which reads
     * other modules' analysis files under the same store.
     */
    static boolean gzipHeaderReadable(Path analysisFile) {
        try (InputStream raw = Files.newInputStream(analysisFile)) {
            new GZIPInputStream(raw).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * {@code store.set}, retried while Windows refuses to replace the analysis file because another
     * handle still holds it. Zinc's Scala {@code set} declares no checked exceptions yet lets {@code
     * IO.move}'s {@link IOException} escape at runtime, so the catch has to be {@link Exception} for
     * the retry to see it at all.
     */
    void persistAnalysis(AnalysisStore store, AnalysisContents contents) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                store.set(contents);
                return;
            } catch (Exception e) {
                if (!Os.isWindows() || !isSharingViolation(e) || attempt == LOCK_ATTEMPTS) {
                    if (e instanceof RuntimeException re) throw re;
                    throw e instanceof IOException io ? io : new IOException(e);
                }
                tryDeleteAnalysis();
                sleepBriefly(attempt);
            }
        }
    }

    /**
     * A Windows sharing denial, recognised by exception type: the system message is localized, so
     * matching its English text would silently never fire on a German or Japanese host. Only asked
     * on Windows — a POSIX {@link AccessDeniedException} is EACCES and permanent.
     */
    private static boolean isSharingViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof FileSystemException) return true;
        }
        return false;
    }

    /**
     * Remove the analysis file. On POSIX one {@code deleteIfExists} is the whole story. Windows may
     * deny the delete while another handle lingers ({@link FileSystemException}, not
     * {@link AccessDeniedException} — sharing violation is ERROR_SHARING_VIOLATION), so rename it
     * out of the way — allowed where deleting is not — and retry when even that is refused.
     */
    private void tryDeleteAnalysis() {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.deleteIfExists(analysisFile);
                return;
            } catch (IOException e) {
                if (!Os.isWindows() || !isSharingViolation(e) || attempt == LOCK_ATTEMPTS) return;
                if (renameAside(analysisFile)) return;
                sleepBriefly(attempt);
            }
        }
    }

    /** Move {@code file} aside so a fresh one can take its name; false when even that is denied. */
    private static boolean renameAside(Path file) {
        Path junk = file.resolveSibling(file.getFileName() + ".stale-" + System.nanoTime());
        try {
            Files.move(file, junk, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            return false;
        }
        try {
            Files.deleteIfExists(junk);
        } catch (IOException ignored) {
            junk.toFile().deleteOnExit();
        }
        return true;
    }

    private static void sleepBriefly(int attempt) {
        try {
            Thread.sleep(5L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
