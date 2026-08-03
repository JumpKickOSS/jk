// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.JdkCatalogClient;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.repo.LibraryRegistryClient;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Quiet store-feed revalidation (libs registry + JDK catalog). Driven by {@link
 * EngineMaintenance} on a wall-clock 12 h cadence (checked every minute) — not a 12 h process
 * sleep, so laptop suspend/resume still refreshes.
 *
 * <ul>
 * <li>{@code store/libs.global.toml} — conditional GET / ETag
 * <li>{@code store/jdks.json} — TTL + If-Modified-Since
 * <li>optional {@code afterTick} — e.g. queue cache GC + host warmup
 * </ul>
 *
 * <p>Failures never surface to builds — offline / 5xx leave the on-disk copy alone; no retries.
 */
public final class StoreFeedRefresh implements AutoCloseable {

    /**
     * Shared cadence for feeds and the scheduled cache-GC enqueue. Aliases the client-side
     * freshness window so foreground {@code ensurePresent} gating and this refresher agree.
     */
    public static final Duration INTERVAL = cc.jumpkick.repo.LibraryRegistrySync.FRESH_FOR;

    private final Consumer<String> log;
    private final Http http;
    private final Supplier<Path> librariesFile;
    private final Supplier<Path> jdksFile;
    private final URI librariesSource;
    private final URI jdkFeed;
    /** Engine hook: queue idle-boundary cache GC (never blocks the tick on builds). */
    private final Runnable afterTick;

    private final AtomicBoolean closed = new AtomicBoolean();

    public StoreFeedRefresh(Consumer<String> log) {
        this(log, null);
    }

    /**
     * @param afterTick optional hook run after each feed pass (e.g. enqueue cache prune). Exceptions
     *     are logged and swallowed so a GC failure never aborts the tick.
     */
    public StoreFeedRefresh(Consumer<String> log, Runnable afterTick) {
        this(
                log,
                new Http(),
                LibraryCatalog::downloadedFile,
                JdkCatalogClient::defaultCachePath,
                LibraryRegistryClient.DEFAULT_SOURCE,
                URI.create(JdkCatalogClient.DEFAULT_FEED_URL),
                afterTick);
    }

    /** Test seam: injectable HTTP, paths, feed URIs, and after-tick hook. */
    StoreFeedRefresh(
            Consumer<String> log,
            Http http,
            Supplier<Path> librariesFile,
            Supplier<Path> jdksFile,
            URI librariesSource,
            URI jdkFeed,
            Runnable afterTick) {
        this.log = log != null ? log : s -> {};
        this.http = Objects.requireNonNull(http, "http");
        this.librariesFile = Objects.requireNonNull(librariesFile, "librariesFile");
        this.jdksFile = Objects.requireNonNull(jdksFile, "jdksFile");
        this.librariesSource = Objects.requireNonNull(librariesSource, "librariesSource");
        this.jdkFeed = Objects.requireNonNull(jdkFeed, "jdkFeed");
        this.afterTick = afterTick;
    }

    /**
     * No-op scheduler. {@link EngineMaintenance} calls {@link #tickQuietly()} on the wall-clock 12 h
     * cadence (checked every minute). Kept for call sites / tests that still invoke {@code start()}.
     */
    public void start() {
        // scheduling lives in EngineMaintenance
    }

    /**
     * One pass over store feeds only (no afterTick). Used by {@link HostWarmup} before AOT/cal so
     * catalogs are warm without enqueueing GC twice.
     */
    public void refreshFeedsQuietly() {
        if (closed.get()) return;
        try {
            refreshLibraries();
        } catch (Throwable t) {
            // Quiet: never fail the engine for a hygiene refresh. No retries.
        }
        try {
            refreshJdks();
        } catch (Throwable t) {
            // Quiet — leave on-disk copy.
        }
    }

    /** Feeds + optional afterTick (GC / warmup enqueue). */
    void tickQuietly() {
        if (closed.get()) return;
        refreshFeedsQuietly();
        if (afterTick != null && !closed.get()) {
            try {
                afterTick.run();
            } catch (Throwable t) {
                // Quiet
            }
        }
    }

    /**
     * {@code libs.global.toml}: when missing or older than {@link #INTERVAL}, conditional-GET the
     * registry; write only after the body validates as a library catalog.
     */
    void refreshLibraries() throws IOException, InterruptedException {
        Path cacheFile = librariesFile.get();
        Path etagFile = LibraryCatalog.etagFileFor(cacheFile);
        if (!needsRefresh(cacheFile, INTERVAL)) return;

        LibraryRegistryClient.Result result =
                new LibraryRegistryClient(http).fetch(librariesSource, etagFile, cacheFile);
        if (result instanceof LibraryRegistryClient.Result.Unchanged) {
            touch(cacheFile);
            return;
        }
        if (!(result instanceof LibraryRegistryClient.Result.Updated updated)) return;

        String body = new String(updated.body(), StandardCharsets.UTF_8);
        // Refuse to replace a good cache with a malformed payload (same rule as `jk library update`).
        LibraryCatalog.parse(body);

        Files.createDirectories(cacheFile.getParent());
        AtomicWrites.replace(cacheFile, updated.body());
        if (updated.etag() != null && !updated.etag().isBlank()) {
            AtomicWrites.replace(etagFile, updated.etag().getBytes(StandardCharsets.UTF_8));
        } else {
            Files.deleteIfExists(etagFile);
        }
        log.accept("jk engine: refreshed library registry (" + cacheFile.getFileName() + ")");
    }

    /**
     * {@code jdks.json}: reuse {@link JdkCatalogClient}'s TTL + {@code If-Modified-Since} path.
     * Calling {@code fetch(false)} only hits the network when the on-disk copy is missing or stale.
     */
    void refreshJdks() throws IOException, InterruptedException {
        Path cacheFile = jdksFile.get();
        if (!needsRefresh(cacheFile, INTERVAL) && Files.isRegularFile(cacheFile)) {
            // Still warm — skip. (JdkCatalogClient would no-op the same way via its TTL.)
            return;
        }
        new JdkCatalogClient(http, jdkFeed, cacheFile, INTERVAL)
                .onWarning(msg -> {}) // quiet
                .fetch(false);
    }

    static boolean needsRefresh(Path file, Duration maxAge) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) return true;
        Instant mtime = Files.getLastModifiedTime(file).toInstant();
        return Duration.between(mtime, Instant.now()).compareTo(maxAge) >= 0;
    }

    private static void touch(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
        }
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
