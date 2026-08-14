// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.http.Http;
import cc.jumpkick.library.LibraryCatalog;
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

/**
 * Ensure the downloaded library registry ({@link LibraryCatalog#downloadedFile()}) is available
 * before short-name expansion in {@code jk.toml}.
 *
 * <ul>
 * <li><b>Missing file</b> — fetch once (first-time host / race with the engine's background
 * {@code StoreFeedRefresh}). Fail soft to the bundled catalog if the network is down.
 * <li><b>Fresh file</b> (mtime younger than {@link #FRESH_FOR}) — no network at all. The
 * resident engine keeps the file warm on this same cadence; foreground commands must not stack
 * blocking fetches on top (a blackholed network stalls each one to the full HTTP timeouts, and
 * a stale-lock <em>build</em> reaches here via auto-relock).
 * <li><b>Stale file</b> — conditional-GET revalidation (same as historical {@code jk lock}
 * behaviour); a 304 re-arms the freshness window.
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

    private LibraryRegistrySync() {}

    /** Default source + default on-disk path. */
    public static void ensurePresent(boolean offline) {
        ensurePresent(offline, LibraryRegistryClient.DEFAULT_SOURCE, LibraryCatalog.downloadedFile());
    }

    /**
     * @param offline when true, no network
     * @param source registry URL
     * @param cacheFile local mirror path (and sibling {@code .*.etag})
     */
    public static void ensurePresent(boolean offline, URI source, Path cacheFile) {
        if (offline) return;
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(cacheFile, "cacheFile");
        if (isFresh(cacheFile)) return;
        Path etagFile = LibraryCatalog.etagFileFor(cacheFile);
        try {
            // The client skips If-None-Match when cacheFile is missing/empty, so an orphan etag
            // sidecar can never 304 us into returning without materializing the file.
            LibraryRegistryClient.Result result =
                    new LibraryRegistryClient(new Http()).fetch(source, etagFile, cacheFile);
            if (result instanceof LibraryRegistryClient.Result.Unchanged) {
                // Re-arm the freshness window so the next FRESH_FOR of commands skip the network.
                Files.setLastModifiedTime(cacheFile, FileTime.from(Instant.now()));
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
        } catch (Exception e) {
            // Fail soft: missing → bundled floor; present → keep stale. Lock must not fail solely
            // because the registry is unreachable (except operators may still lack short names only
            // present upstream — then parse reports unknown library).
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
