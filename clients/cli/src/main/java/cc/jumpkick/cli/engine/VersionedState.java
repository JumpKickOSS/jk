// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.config.SelfUpdateState;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.util.FileLocks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The cache and state roots carry the version of jk that wrote them, in a {@value #STAMP} file at
 * their top. Before an engine starts with none running, a root another version wrote is emptied and
 * stamped with this one: jk rebuilds what it needs and never reads another version's layout.
 *
 * <p>Only those two roots: {@code creds} is the one root kept readable across versions, and {@code
 * store}, {@code bin}, {@code lib}, {@code config} and {@code changes} hold what the install itself,
 * the user and the network put there. Inside {@code state}, {@code engine} (sockets, pid files, the
 * install lock) and {@code tmp} (scratch a running client may hold open) stay: they are how live
 * processes find each other, not what they remember. So does the self-update record and its log:
 * they describe the move between two versions (an update to announce, a rollback's hold on
 * auto-update), so both sides of the move read them.
 *
 * <p>Nothing is emptied while an engine of this home runs, a draining predecessor included: that
 * engine would lose files under it. The next start with none running empties the roots then.
 */
final class VersionedState {

    /** The file naming the version that wrote a root. */
    static final String STAMP = "jk-version";

    /** What {@code state} keeps: see the class comment. */
    private static final Set<String> STATE_KEPT =
            Set.of("engine", "tmp", SelfUpdateState.FILE_NAME, SelfUpdateState.FILE_NAME + ".lock", "self-update.log");

    /** What a root of another version keeps when it is emptied. */
    record Root(Path dir, Set<String> keep) {}

    private VersionedState() {}

    /** The roots of the current home, with what each keeps. */
    static List<Root> current() {
        return List.of(new Root(JkDirs.cache(), Set.of()), new Root(JkDirs.state(), STATE_KEPT));
    }

    /**
     * Empty every root another version wrote, unless an engine of this home runs; then stamp each
     * root with {@code version}. {@code lock} serializes two clients starting an engine at once.
     * Returns the versions found on the roots it emptied, empty when it emptied none.
     */
    static List<String> claim(
            List<Root> roots, String version, Path lock, BooleanSupplier engineRunning, Consumer<String> log)
            throws IOException {
        if (allStamped(roots, version)) return List.of();
        Files.createDirectories(lock.getParent());
        return FileLocks.withLock(lock, () -> {
            List<Root> foreign = new ArrayList<>();
            for (Root root : roots) {
                if (version.equals(stamp(root.dir()))) continue;
                if (holdsOnlyKept(root)) {
                    writeStamp(root.dir(), version);
                } else {
                    foreign.add(root);
                }
            }
            if (foreign.isEmpty()) return List.of();
            if (engineRunning.getAsBoolean()) {
                log.accept("left the cache and state another jk version wrote: an engine of this home still runs");
                return List.of();
            }
            List<String> found = new ArrayList<>();
            for (Root root : foreign) {
                String was = stamp(root.dir());
                String what = was == null ? "an older jk" : "jk " + was;
                log.accept("discarding " + root.dir() + ", written by " + what);
                List<Path> doomed = new ArrayList<>();
                PathUtil.forEachChild(root.dir(), (child, attrs) -> !discarded(root, child) || doomed.add(child));
                PathUtil.deleteTrees(doomed, new PathUtil.Removed());
                writeStamp(root.dir(), version);
                if (!found.contains(what)) found.add(what);
            }
            return List.copyOf(found);
        });
    }

    private static boolean allStamped(List<Root> roots, String version) {
        for (Root root : roots) if (!version.equals(stamp(root.dir()))) return false;
        return true;
    }

    /** The version a root names, or {@code null} when it has no readable stamp. */
    static @Nullable String stamp(Path dir) {
        try {
            String text =
                    Files.readString(dir.resolve(STAMP), StandardCharsets.UTF_8).strip();
            return text.isEmpty() ? null : text;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Whether a root is absent or holds nothing but what it keeps: a fresh root, stamped as is. */
    private static boolean holdsOnlyKept(Root root) throws IOException {
        boolean[] other = {false};
        PathUtil.forEachChild(root.dir(), (child, attrs) -> {
            other[0] = discarded(root, child);
            return !other[0];
        });
        return !other[0];
    }

    /** Whether emptying {@code root} deletes {@code child}: everything but the stamp and what it keeps. */
    private static boolean discarded(Root root, Path child) {
        String name = child.getFileName().toString();
        return !name.equals(STAMP) && !root.keep().contains(name);
    }

    private static void writeStamp(Path dir, String version) throws IOException {
        Files.createDirectories(dir);
        AtomicWrites.replace(dir.resolve(STAMP), version + "\n");
    }
}
