// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.rules.GuardPacks;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Brings the rule packs the lock pins into the store before the rules load. A pinned pack is a
 * locked artifact: a fresh clone, a CI runner, {@code jk clean} or a store prune leaves neither
 * its unpacked copy nor its jar, and it is fetched through the root project's repositories the
 * way a locked library is, pinned to the lock's digest so a repository that serves other bytes at
 * that version is refused before they reach the store. The loader then verifies the jar against the
 * lock and unpacks it; a pack that cannot be fetched, or a row with no digest, is the loader's error.
 */
final class GuardPackFetch {

    private GuardPackFetch() {}

    /** Downloads one pack with the digest {@code sha256} into the store; false when no repository has it. */
    @FunctionalInterface
    interface Fetcher {
        boolean fetch(GuardPacks.Coordinate pack, Lockfile.PluginEntry pin) throws IOException, InterruptedException;
    }

    /** Fetch every pack {@code root} pins that is neither unpacked nor in {@code store}. */
    static void fetchMissing(Path root) {
        fetchMissing(root, JkDirs.store(), new RepoFetcher(root));
    }

    /** As above with the store and the fetcher given; returns the packs fetched. */
    static List<String> fetchMissing(Path root, Path store, Fetcher fetcher) {
        List<String> fetched = new ArrayList<>();
        List<String> declared;
        try {
            declared = GuardPacks.declared(root);
        } catch (IOException e) {
            return fetched; // the loader reports an unreadable rules file
        }
        for (String text : declared) {
            GuardPacks.Coordinate c = GuardPacks.Coordinate.parse(text);
            if (c == null) continue;
            try {
                Lockfile.PluginEntry pin = GuardPacks.pin(root, c);
                String hex = pin == null ? null : pin.sha256Hex();
                if (pin == null || hex == null || present(root, c, pin, store)) continue;
                if (fetcher.fetch(c, pin)) fetched.add(c.gav());
            } catch (IOException e) {
                Log.debug("rule pack " + c.gav() + ": fetch failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return fetched;
            }
        }
        return fetched;
    }

    private static boolean present(Path root, GuardPacks.Coordinate c, Lockfile.PluginEntry pin, Path store)
            throws IOException {
        String hex = pin.sha256Hex();
        return (hex != null && GuardPacks.unpackedAs(GuardPacks.unpackedDir(root, c), hex))
                || GuardPacks.locateJar(c, pin, store) != null;
    }

    /** The root project's repositories, built on the first pack that needs them. */
    private static final class RepoFetcher implements Fetcher {
        private final Path root;
        private @Nullable RepoGroup repos;
        private boolean m2integration;

        RepoFetcher(Path root) {
            this.root = root;
        }

        @Override
        public boolean fetch(GuardPacks.Coordinate pack, Lockfile.PluginEntry pin)
                throws IOException, InterruptedException {
            Cas cas = JkStores.storeCas();
            RepoGroup group = repos;
            if (group == null) {
                var build = JkBuildParser.parse(ManifestPaths.manifestIn(root));
                m2integration = build.project().m2integration();
                group = LockPipeline.repositoriesForLock(build, root, null, cas);
                repos = group;
            }
            return LockPipeline.fetchLocked(
                            group,
                            Coordinate.of(pack.group(), pack.artifact(), pack.version()),
                            Objects.requireNonNull(pin.sha256Hex()),
                            pin.source(),
                            cas,
                            m2integration,
                            root)
                    .isPresent();
        }
    }
}
