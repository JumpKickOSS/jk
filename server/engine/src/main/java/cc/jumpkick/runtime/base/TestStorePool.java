// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The verified downloads every test sandbox on a host shares, under {@code <home>/test-store}: the
 * public-origin repository trees and the build-tool distributions, each linked into a sandbox store
 * at the same relative path ({@link #SHARED}), and a managed Android SDK a suite reaches through
 * {@code -Djk.test.pool.dir} ({@link #ANDROID_SDK}). A worktree's first suite therefore finds what
 * any other worktree's suites already fetched, instead of starting cold.
 *
 * <p>Only content that is verified and published whole is pooled. A repository tree holds what its
 * origin served: a store is keyed by origin, so a loopback stub or a {@code file:} repository lands
 * in a tree of its own, and an artifact lands through a temp file and an atomic rename. A tool
 * distribution is checked against its pin or its publisher's checksum and published by one rename
 * of a staged tree; an Android SDK component likewise. The rest of a sandbox store stays its own:
 * the {@code jk-local} shelf a suite installs onto, version lists, the store CAS and the closure
 * indexes into it, credentials.
 *
 * <p>The pool sits beside {@code test-homes}, outside every sandbox home, so a test that nukes or
 * cleans its home deletes the links and not the pool: jk's deletes never follow a link. A test that
 * writes fake bytes under a public coordinate or a real tool version, mirrors Central at a stub, or
 * must see a cold download takes a private store ({@code jk.env.JK_STORE_DIR}).
 */
public final class TestStorePool {

    /** Under the product home ({@link JkDirs#home()}), beside {@code test-homes}. */
    public static final String DIR = "test-store";

    /** Store-relative trees the pool holds, each linked into a sandbox store at the same path. */
    static final List<String> SHARED = List.of(
            "repos/" + RepositorySpec.CENTRAL,
            "repos/" + RepositorySpec.GOOGLE,
            "repos/" + RepositorySpec.JUMPKICK_NAME,
            "tools/kotlin",
            "tools/gradle",
            "tools/maven");

    /** The managed Android SDK root under the pool. */
    public static final String ANDROID_SDK = "android-sdk";

    /** The test JVM property naming the pool, for caches a suite opts into by name. */
    public static final String PROPERTY = "jk.test.pool.dir";

    /** A staged download or install this old was left by a process that died. */
    static final long LEAK_MILLIS = 60L * 60 * 1000;

    /** A pool swept this recently is not swept again by this process. */
    static final long SWEEP_EVERY_MILLIS = 10L * 60 * 1000;

    private static final ConcurrentHashMap<Path, Long> SWEPT = new ConcurrentHashMap<>();

    private static final AtomicLong SEQ = new AtomicLong();

    private TestStorePool() {}

    /** {@code <home>/test-store}: under {@code JK_HOME} when set, else {@code ~/.jk}. */
    public static Path root() {
        return JkDirs.home().resolve(DIR);
    }

    /**
     * Make each of {@link #SHARED} in {@code sandboxStore} a link into {@code pool}; returns how many
     * links were placed. A private tree already there is replaced, once the link is known to work, so
     * a host that cannot make links keeps the sandbox's own tree. Sweeps the pool's leaked temps first.
     */
    public static int link(Path pool, Path sandboxStore) throws IOException {
        sweepIfDue(pool, Clock.SYSTEM.millis());
        int placed = 0;
        for (String rel : SHARED) {
            Path shared =
                    Files.createDirectories(pool.resolve(rel)).toAbsolutePath().normalize();
            Path entry = sandboxStore.resolve(rel);
            if (linksTo(entry, shared)) continue;
            Path parent = Files.createDirectories(entry.getParent());
            Path staged = parent.resolve("." + entry.getFileName() + ".link-"
                    + ProcessHandle.current().pid() + "-" + SEQ.incrementAndGet());
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

    /** {@link #sweep} unless this process swept {@code pool} within {@link #SWEEP_EVERY_MILLIS}. */
    static void sweepIfDue(Path pool, long nowMillis) {
        Long last = SWEPT.get(pool);
        if (last != null && nowMillis - last < SWEEP_EVERY_MILLIS) return;
        SWEPT.put(pool, nowMillis);
        int removed = sweep(pool, nowMillis);
        if (removed > 0) Log.debug("TestStorePool: swept " + removed + " leaked temps from " + pool);
    }

    /**
     * Remove what a killed process left staged in the pool, older than {@link #LEAK_MILLIS}: a
     * repository download's {@code .put-*.tmp} at a tree's root, a tool's {@code jk-tool-stage-*}
     * beside its versions, an Android download under {@code .downloads}. One listing per directory,
     * never a walk; returns how many entries went. A younger entry may belong to a live download.
     */
    static int sweep(Path pool, long nowMillis) {
        long cutoff = nowMillis - LEAK_MILLIS;
        int[] removed = {0};
        List<Path> dirs = new ArrayList<>();
        for (String rel : SHARED) dirs.add(pool.resolve(rel));
        dirs.add(pool.resolve(ANDROID_SDK).resolve(".downloads"));
        for (Path dir : dirs) {
            try {
                PathUtil.forEachChild(dir, (child, attrs) -> {
                    String name = child.getFileName().toString();
                    boolean staged = (name.startsWith(".put-") && name.endsWith(".tmp"))
                            || name.startsWith("jk-tool-stage-")
                            || (name.startsWith("jk-sdk-") && name.endsWith(".zip"));
                    if (staged && attrs.lastModifiedTime().toMillis() < cutoff) {
                        try {
                            PathUtil.deleteRecursivelyOrThrow(child);
                            removed[0]++;
                        } catch (IOException held) {
                            // Still open somewhere, or gone already: the next sweep decides.
                        }
                    }
                    return true;
                });
            } catch (IOException | RuntimeException e) {
                Log.debug("TestStorePool: a pool directory that will not list is swept next time", e);
            }
        }
        return removed[0];
    }
}
