// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.AttemptWindow;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Ensure the downloaded library registry ({@link JkDirs#libraryRegistry()}) is available
 * before short-name expansion in {@code jk.toml}.
 *
 * <ul>
 * <li><b>Missing file</b> — fetch once (first-time host / race with the engine's background
 * {@code StoreFeedRefresh}). Fail soft to the bundled catalog if the network is down.
 * <li><b>Fresh file</b> (mtime younger than {@link #FRESH_FOR}) — no network at all. The
 * resident engine keeps the file warm on this same cadence; foreground commands must not stack
 * blocking fetches on top (a blackholed network stalls each one to the full HTTP timeouts, and
 * a stale-lock <em>build</em> reaches here via auto-relock).
 * <li><b>Stale file</b> — conditional-GET revalidation; a 304 re-arms the freshness window.
 * <li><b>Failed fetch</b> — the source is not asked again for {@link #RETRY_AFTER}, and the
 * engine log says so once: without the pause an unreachable origin is dialed by every
 * {@code jk lock}, and a network that drops the packets stalls each one to the HTTP timeouts.
 * </ul>
 *
 * <p>Offline callers skip the network entirely.
 */
public final class LibraryRegistrySync {

    /**
     * How long a downloaded registry counts as fresh. The engine's {@code StoreFeedRefresh}
     * cadence aliases this so the two never drift apart.
     */
    public static final Duration FRESH_FOR = Duration.ofHours(12);

    /** How long a source whose fetch failed is left alone before a command asks it again. */
    public static final Duration RETRY_AFTER = Duration.ofMinutes(10);

    /** The sources whose last fetch failed, per engine: the one long-lived process that asks. */
    private static final AttemptWindow FAILED = new AttemptWindow(RETRY_AFTER);

    private LibraryRegistrySync() {}

    /**
     * @param offline when true, no network
     * @param source registry URL
     * @param http the client the registry is fetched with, carrying any mirror credential
     * @param cacheFile local mirror path (and sibling {@code .*.etag})
     */
    public static void ensurePresent(boolean offline, URI source, Http http, Path cacheFile) {
        ensurePresent(offline, source, http, cacheFile, FAILED, Clock.SYSTEM);
    }

    /** As {@link #ensurePresent(boolean, URI, Http, Path)} with the failure window and its clock injected (tests). */
    static void ensurePresent(
            boolean offline, URI source, Http http, Path cacheFile, AttemptWindow failed, Clock clock) {
        if (offline) return;
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(http, "http");
        Objects.requireNonNull(cacheFile, "cacheFile");
        if (isFresh(cacheFile)) return;
        String key = source.toString();
        if (!failed.open(key, clock.nanos())) return;
        try {
            fetchAndStore(source, http, cacheFile);
        } catch (InterruptedException e) {
            // The command was cancelled; that says nothing about the source.
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Fail soft: missing → bundled floor; present → keep stale. Lock must not fail solely
            // because the registry is unreachable (a short name only upstream knows then reports
            // as an unknown library).
            failed.record(key, clock.nanos());
            Log.info(
                    "library registry not fetched; using the copy on disk or the built-in catalog," + " next try in "
                            + failed.window().toMinutes() + " min",
                    "source",
                    SafeUri.forMessage(source),
                    "cause",
                    Errors.text(e));
        }
    }

    /** {@code jk library update}: always hit the network and surface fetch/parse failures. */
    public static void refreshNow(URI source, Http http, Path cacheFile) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(http, "http");
        Objects.requireNonNull(cacheFile, "cacheFile");
        try {
            fetchAndStore(source, http, cacheFile);
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (RuntimeException e) {
            throw new IOException(Errors.text(e), e);
        }
    }

    private static void fetchAndStore(URI source, Http http, Path cacheFile) throws IOException, InterruptedException {
        Path etagFile = LibraryCatalog.etagFileFor(cacheFile);
        // The client skips If-None-Match when cacheFile is missing/empty, so an orphan etag
        // sidecar can never 304 us into returning without materializing the file.
        LibraryRegistryClient.Result result = new LibraryRegistryClient(http).fetch(source, etagFile, cacheFile);
        if (result instanceof LibraryRegistryClient.Result.Unchanged) {
            // Re-arm the freshness window so the next FRESH_FOR of commands skip the network.
            Files.setLastModifiedTime(cacheFile, FileTime.from(Clock.SYSTEM.instant()));
            return;
        }
        if (!(result instanceof LibraryRegistryClient.Result.Updated updated)) {
            return;
        }
        // Validate before writing — never replace a good cache (or create a bad first copy).
        LibraryCatalog.parse(new String(updated.body(), StandardCharsets.UTF_8));
        Files.createDirectories(cacheFile.getParent());
        AtomicWrites.replace(cacheFile, updated.body());
        if (updated.etag() != null && !updated.etag().isBlank()) {
            AtomicWrites.replace(etagFile, updated.etag().getBytes(StandardCharsets.UTF_8));
        } else {
            Files.deleteIfExists(etagFile);
        }
    }

    private static boolean isNonEmptyFile(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Non-empty and modified within {@link #FRESH_FOR} — recently downloaded or revalidated. */
    private static boolean isFresh(Path file) {
        try {
            if (!isNonEmptyFile(file)) return false;
            Instant mtime = Files.getLastModifiedTime(file).toInstant();
            return Duration.between(mtime, Instant.now()).compareTo(FRESH_FOR) < 0;
        } catch (IOException e) {
            return false;
        }
    }
}
