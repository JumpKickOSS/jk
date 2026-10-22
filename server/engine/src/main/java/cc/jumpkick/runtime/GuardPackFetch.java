// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.rules.GuardPacks;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Brings the rule packs the lock pins into the store before the rules load. A pinned pack is a
 * locked artifact: a fresh clone, a CI runner, {@code jk clean} or a store prune leaves neither
 * its unpacked copy nor its jar, and it is fetched through the root project's repositories the
 * way a locked library is. The loader then verifies it against the lock and unpacks it; a pack
 * that cannot be fetched is still the loader's error.
 */
final class GuardPackFetch {

    private GuardPackFetch() {}

    /** Downloads one pack into the store; false when no repository has it. */
    @FunctionalInterface
    interface Fetcher {
        boolean fetch(GuardPacks.Coordinate pack) throws IOException, InterruptedException;
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
                if (pin == null || present(root, c, pin, store)) continue;
                if (fetcher.fetch(c)) fetched.add(c.gav());
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
        Path dir = GuardPacks.unpackedDir(root, c);
        String hex = pin.sha256Hex();
        boolean unpacked =
                hex != null ? GuardPacks.unpackedAs(dir, hex) : Files.isRegularFile(GuardPacks.fragment(dir));
        return unpacked || GuardPacks.locateJar(c, pin, store) != null;
    }

    /** The root project's repositories, built on the first pack that needs them. */
    private static final class RepoFetcher implements Fetcher {
        private final Path root;
        private @Nullable RepoGroup repos;

        RepoFetcher(Path root) {
            this.root = root;
        }

        @Override
        public boolean fetch(GuardPacks.Coordinate pack) throws IOException, InterruptedException {
            RepoGroup group = repos;
            if (group == null) {
                group = RepoGroupBuilder.buildFor(JkBuildParser.parse(ManifestPaths.manifestIn(root)));
                repos = group;
            }
            return group.tryFetchArtifact(Coordinate.of(pack.group(), pack.artifact(), pack.version()))
                    .isPresent();
        }
    }
}
