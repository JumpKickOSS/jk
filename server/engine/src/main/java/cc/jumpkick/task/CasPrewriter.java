// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Hashing;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Background CAS ingest of an output dir while a compiler still writes it. Stable {@code (size,
 * mtime)} across polls is hashed and copied into CAS; {@link #finish} re-checks drift and is the
 * authoritative pass.
 *
 * <p>Every walk here carries the attributes the tree walk already read, and every poll rejects an
 * ingested file on the in-memory map before it looks at the filesystem. A poll runs every {@value
 * #POLL_INTERVAL_MILLIS} ms for the whole compile over a tree that only grows, so a single stat
 * per file per poll is hundreds of stats per file — on NTFS that costs more than the compile.
 */
public final class CasPrewriter implements AutoCloseable {

    private static final long POLL_INTERVAL_MILLIS = 100;

    private final Cas cas;
    private final Path outputDir;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<Path, Snapshot> tracked = new ConcurrentHashMap<>();
    private final ConcurrentMap<Path, String> processed = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    private CasPrewriter(Cas cas, Path outputDir) {
        this.cas = cas;
        this.outputDir = outputDir;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jk-cas-prewriter");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start a prewriter watching {@code outputDir}. The caller's responsibility to call {@link
     * #finish} before reading the result — {@code finish} also stops the background poller.
     */
    public static CasPrewriter watching(Cas cas, Path outputDir) {
        CasPrewriter p = new CasPrewriter(cas, outputDir);
        p.scheduler.scheduleWithFixedDelay(
                p::pollOnce, POLL_INTERVAL_MILLIS, POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        return p;
    }

    /**
     * Stop polling and run the final correctness pass. Returns the {@code (relPath → hex)} map ready
     * for {@link ActionCache#storeWithOutputs}.
     */
    public Map<String, String> finish() throws IOException {
        running = false;
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Map<String, String> outputs = new TreeMap<>();
        if (!Files.exists(outputDir)) return outputs;
        try (Stream<Path> stream = Files.find(outputDir, Integer.MAX_VALUE, (p, attrs) -> attrs.isRegularFile())) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (BuildStamps.isStampFile(file.getFileName().toString())) continue;

                String relPath = outputDir.relativize(file).toString().replace(File.separatorChar, '/');
                // Authoritative pass always content-hashes, and this is deliberately NOT routed
                // through FileHashMemo. tried that and
                // CasPrewriterTest.finish_rehashes_when_content_changes_without_size_mtime_change
                // rejected it: a tool can rewrite equal-length bytes and restore the previous
                // FileTime, and a restored FileTime carries the same nanoseconds — so even the memo's
                // nanosecond provenance rule cannot tell the two apart. Every cheaper identity for
                // this file is forgeable; only the bytes are not. Poll-time CAS ingest is still a win
                // when the hex matches (put is a no-op hit).
                String hex = Hashing.sha256Hex(file);
                if (!hex.equals(processed.get(file))) {
                    cas.putFile(file, hex);
                }
                // Seed here rather than at poll time: this hash is the authoritative one, so
                // downstream ClasspathFingerprint passes over the same tree stop re-hashing it, and
                // nothing upstream of this line is trusted to have got it right.
                FileHashMemo.rememberContent(file, hex);
                outputs.put(relPath, hex);
            }
        }
        return outputs;
    }

    @Override
    public void close() {
        if (running) {
            running = false;
            scheduler.shutdownNow();
        }
    }

    // --- polling ---------------------------------------------------------

    private void pollOnce() {
        if (!running) return;
        if (!Files.isDirectory(outputDir)) return;
        try {
            Files.walkFileTree(outputDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!running) return FileVisitResult.TERMINATE;
                    // Cheapest rejections first: the walk already paid for `attrs`, and an
                    // already-ingested file must cost a map lookup rather than a syscall.
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    if (processed.containsKey(file)) return FileVisitResult.CONTINUE;
                    if (BuildStamps.isStampFile(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                    handleCandidate(file, attrs);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // A file that vanished mid-walk is the compiler's business, not ours.
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Polling is best-effort — a transient walk failure just delays
            // processing until the next tick or the final pass.
        }
    }

    /**
     * Two-poll stability rule: if a file's (size, mtime) matches what we saw last poll, it's been
     * quiet for at least one interval — safe to hash. Otherwise update the snapshot and revisit next
     * tick. {@code attrs} comes from the walk, so deciding this costs no filesystem call.
     */
    private void handleCandidate(Path file, BasicFileAttributes attrs) {
        long size = attrs.size();
        long mtime = attrs.lastModifiedTime().toMillis();
        Snapshot prev = tracked.get(file);
        if (prev == null || prev.size != size || prev.mtime != mtime) {
            tracked.put(file, new Snapshot(size, mtime));
            return;
        }
        try {
            String hex = hashAndLink(file);
            processed.put(file, hex);
            tracked.remove(file);
        } catch (IOException ignored) {
            // Skip; either the file vanished mid-poll or we hit a permission
            // hiccup. The final pass will pick it up.
        }
    }

    /**
     * Streamed SHA-256 then {@link Cas#putFile} <em>copy</em> into CAS (never hard-link — compile
     * trees rewrite class files in place; linking would poison the blob). Name kept for call sites.
     */
    private String hashAndLink(Path file) throws IOException {
        String hex = Hashing.sha256Hex(file);
        cas.putFile(file, hex);
        return hex;
    }

    private record Snapshot(long size, long mtime) {}
}
