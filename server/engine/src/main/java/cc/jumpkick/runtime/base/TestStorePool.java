// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The repository trees every test sandbox on a host shares: {@code <home>/test-store/repos/<id>}
 * for the public origins jk ships with ({@code central}, {@code google}, {@code jumpkick}), each
 * linked into a sandbox store as its {@code repos/<id>}. A worktree's first suite therefore finds
 * every artifact any other worktree's suites already fetched, instead of starting cold.
 *
 * <p>Only those trees. A public-origin tree holds what that origin served and nothing else: a store
 * is keyed by origin, so a loopback stub, a {@code file:} repository or a settings mirror lands in a
 * tree of its own, and a download lands through a temp file and an atomic rename, so concurrent
 * engines writing one tree see whole files. The rest of a sandbox store stays its own: the {@code
 * jk-local} shelf a suite installs onto, version lists, tools, the store CAS, credentials.
 *
 * <p>The pool sits beside {@code test-homes}, outside every sandbox home, so a test that nukes or
 * cleans its home deletes the links and not the pool: jk's deletes never follow a link. A test that
 * writes fake bytes under a public coordinate, or deletes from the ambient store's trees, takes a
 * private store ({@code jk.env.JK_STORE_DIR}); so does one that must see a cold download.
 */
public final class TestStorePool {

    /** Under the product home ({@link JkDirs#home()}), beside {@code test-homes}. */
    public static final String DIR = "test-store";

    /** The {@code repos/<id>} trees the pool holds: public origins, keyed by their origin. */
    static final List<String> TREES =
            List.of(RepositorySpec.CENTRAL, RepositorySpec.GOOGLE, RepositorySpec.JUMPKICK_NAME);

    private static final AtomicLong SEQ = new AtomicLong();

    private TestStorePool() {}

    /** {@code <home>/test-store}: under {@code JK_HOME} when set, else {@code ~/.jk}. */
    public static Path root() {
        return JkDirs.home().resolve(DIR);
    }

    /**
     * Make each of {@link #TREES} in {@code sandboxStore} a link into {@code pool}; returns how many
     * links were placed. A private tree already there is replaced, once the link is known to work, so
     * a host that cannot make links keeps the sandbox's own tree.
     */
    public static int link(Path pool, Path sandboxStore) throws IOException {
        Path repos = Files.createDirectories(sandboxStore.resolve("repos"));
        int placed = 0;
        for (String id : TREES) {
            Path shared = Files.createDirectories(pool.resolve("repos").resolve(id))
                    .toAbsolutePath()
                    .normalize();
            Path entry = repos.resolve(id);
            if (linksTo(entry, shared)) continue;
            Path staged =
                    repos.resolve("." + id + ".link-" + ProcessHandle.current().pid() + "-" + SEQ.incrementAndGet());
            Files.createSymbolicLink(staged, shared);
            try {
                if (Files.exists(entry, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(entry)) {
                    PathUtil.deleteRecursivelyOrThrow(entry);
                }
                Files.move(staged, entry, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                placed++;
            } finally {
                Files.deleteIfExists(staged);
            }
        }
        return placed;
    }

    /** Whether {@code entry} is a link naming {@code target}. */
    static boolean linksTo(Path entry, Path target) {
        if (!Files.isSymbolicLink(entry)) return false;
        try {
            return Files.readSymbolicLink(entry).equals(target);
        } catch (IOException | UnsupportedOperationException unreadable) {
            return false;
        }
    }
}
