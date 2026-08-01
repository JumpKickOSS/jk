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
import java.util.Objects;

/**
 * Ensure the downloaded library registry ({@link LibraryCatalog#downloadedFile()}) is available
 * before short-name expansion in {@code jk.toml}.
 *
 * <ul>
 * <li><b>Missing file</b> — fetch once (first-time host / race with the engine's background
 * {@code StoreFeedRefresh}). Fail soft to the bundled catalog if the network is down.
 * <li><b>Present file</b> — conditional-GET revalidation (same as historical {@code jk lock}
 * behaviour).
 * </ul>
 *
 * <p>Offline callers skip the network entirely.
 */
public final class LibraryRegistrySync {

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
        Path etagFile = LibraryCatalog.etagFileFor(cacheFile);
        boolean missing = !isNonEmptyFile(cacheFile);
        try {
            LibraryRegistryClient.Result result = new LibraryRegistryClient(new Http()).fetch(source, etagFile);
            if (result instanceof LibraryRegistryClient.Result.Unchanged) {
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
            if (missing) {
                // leave absent
            }
        }
    }

    private static boolean isNonEmptyFile(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }
}
