// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.protocol.CatalogReadAck;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.resolver.Versions;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Layered library catalog + optional cached-version walk for {@code jk library list}/{@code search}. */
public final class CatalogReadOps {

    private CatalogReadOps() {}

    public record Request(
            Path dir,
            Path cache,
            // Store root for cached-version lookups (repos/ lives there, JK-2176); null =
            // derive from the ambient store. Explicit so tests can isolate.
            Path store,
            String query,
            List<String> terms,
            boolean offline,
            boolean includeCached,
            boolean bundledOnly) {}

    public static CatalogReadAck read(Request req) {
        List<String> warnings = new ArrayList<>();
        Path dir = Objects.requireNonNull(req.dir(), "catalog-read request names no dir");
        LibraryCatalog catalog =
                req.bundledOnly() ? LibraryCatalog.bundled() : LibraryCatalog.forProject(dir, warnings::add);
        Path cache = req.cache() != null ? req.cache() : JkDirs.cache();
        boolean search = "search".equals(req.query());
        List<String> lowerTerms = req.terms() == null
                ? List.of()
                : req.terms().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();

        List<CatalogReadAck.Entry> entries = new ArrayList<>();
        for (String name : catalog.names()) {
            var src = catalog.source(name).orElseThrow();
            if (search
                    && !allMatch(
                            lowerTerms,
                            name.toLowerCase(Locale.ROOT),
                            src.module().group().toLowerCase(Locale.ROOT),
                            src.module().artifact().toLowerCase(Locale.ROOT))) {
                continue;
            }
            List<String> cached = List.of();
            if (req.includeCached()) {
                // repos/ lives under the STORE root — the same tree MavenRepo writes through
                // cas.root() (JK-2176); the cache root never holds repo artifacts.
                Path store = req.store() != null ? req.store() : JkStores.storeRootFor(cache);
                List<String> versions = new ArrayList<>(RepoArtifactStore.allVersions(
                        store, src.module().group(), src.module().artifact()));
                versions.sort((a, b) -> Versions.compare(b, a));
                cached = List.copyOf(versions);
                if (req.offline() && cached.isEmpty()) continue;
            }
            entries.add(new CatalogReadAck.Entry(
                    name, src.module().group(), src.module().artifact(), src.layer(), cached));
        }
        return CatalogReadAck.of(warnings, catalog.layerNames(), entries);
    }

    private static boolean allMatch(List<String> terms, String... fields) {
        for (String t : terms) {
            boolean found = false;
            for (String f : fields) {
                if (f.contains(t)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }
}
