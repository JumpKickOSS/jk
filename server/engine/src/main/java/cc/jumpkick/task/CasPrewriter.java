// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.util.Hashing;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 */
public final class CasPrewriter implements AutoCloseable {

    private static final long POLL_INTERVAL_MILLIS = 100;

    private final Cas cas;
    private final Path outputDir;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<Path, Snapshot> tracked = new ConcurrentHashMap<>();
    private final ConcurrentMap<Path, Processed> processed = new ConcurrentHashMap<>();
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
        try (Stream<Path> stream = Files.walk(outputDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                if (FreshnessStamp.isStampFile(file.getFileName().toString())) continue;

                String relPath = outputDir.relativize(file).toString().replace(File.separatorChar, '/');
                // Authoritative pass always content-hashes. Size+mtime alone can miss a
                // same-size rewrite within one filesystem mtime tick (JK-1069 / coarse mtime).
                // Poll-time CAS ingest is still a win when the hex matches (put is a no-op hit).
                String hex = Hashing.sha256Hex(file);
                Processed pre = processed.get(file);
                if (pre == null || !pre.hex.equals(hex)) {
                    cas.putFile(file, hex);
                }
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
        try (Stream<Path> stream = Files.walk(outputDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                if (FreshnessStamp.isStampFile(file.getFileName().toString())) continue;
                if (processed.containsKey(file)) continue;
                handleCandidate(file);
            }
        } catch (IOException ignored) {
            // Polling is best-effort — a transient walk failure just delays
            // processing until the next tick or the final pass.
        }
    }

    /**
     * Two-poll stability rule: if a file's (size, mtime) matches what we saw last poll, it's been
     * quiet for at least one interval — safe to hash. Otherwise update the snapshot and revisit next
     * tick.
     */
    private void handleCandidate(Path file) {
        try {
            long size = Files.size(file);
            long mtime = Files.getLastModifiedTime(file).toMillis();
            Snapshot prev = tracked.get(file);
            if (prev != null && prev.size == size && prev.mtime == mtime) {
                String hex = hashAndLink(file);
                processed.put(file, new Processed(hex, size, mtime));
                tracked.remove(file);
            } else {
                tracked.put(file, new Snapshot(size, mtime));
            }
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

    private record Processed(String hex, long size, long mtime) {}
}
