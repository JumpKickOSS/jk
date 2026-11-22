// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.StoreWipe;
import cc.jumpkick.config.SelfUpdateState;
import cc.jumpkick.config.WhatsNew;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.tool.TrustedPlugins;
import cc.jumpkick.tool.TrustedSources;
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
 * their top, and the store carries its layout number ({@link JkStores#LAYOUT}) in a {@value
 * #LAYOUT_STAMP} file. Before an engine starts with none running, a root whose stamp names
 * something else is emptied and stamped again: jk rebuilds what it needs and never reads another
 * version's layout. A root with no stamp yet is this version's, and is stamped as it is.
 *
 * <p>{@code creds}, {@code config.toml}, {@code bin}, {@code lib} and {@code changes} are never
 * touched. Inside {@code state}, {@code engine} (sockets, pid files, the install lock) and {@code
 * tmp} (scratch a running client may hold open) stay: they are how live processes find each other,
 * not what they remember. So do the plugin and source trust lists, which are the user's security
 * decisions, and the self-update record, its log and the release highlights: they describe the move
 * between two versions (an update to announce, a rollback's hold on auto-update), so both sides of
 * the move read them. The store keeps this version's own first-party shelf, as {@link StoreWipe}
 * does.
 *
 * <p>Nothing is emptied while an engine of this home runs, a draining predecessor included: that
 * engine would lose files under it. The next start with none running empties the roots then.
 */
final class VersionedState {

    /** The file naming the version that wrote a cache or state root. */
    static final String STAMP = "jk-version";

    /** The file naming the store's layout. */
    static final String LAYOUT_STAMP = "jk-layout";

    /** What {@code state} keeps: see the class comment. */
    static final Set<String> STATE_KEPT = Set.of(
            "engine",
            "tmp",
            TrustedPlugins.FILE_NAME,
            TrustedSources.FILE_NAME,
            SelfUpdateState.FILE_NAME,
            SelfUpdateState.FILE_NAME + ".lock",
            "self-update.log",
            WhatsNew.FILE_NAME);

    /** What a root keeps when it is emptied, beside its stamp. */
    @FunctionalInterface
    interface Kept {
        List<Path> in(Path dir) throws IOException;

        static Kept names(Set<String> names) {
            return dir -> names.stream().map(dir::resolve).toList();
        }
    }

    /** A root named {@code name} in what jk says, stamped with {@code stamp} in {@code stampFile}. */
    record Root(String name, Path dir, String stampFile, String stamp, Kept kept) {}

    private VersionedState() {}

    /** The roots of the current home as {@code version} stamps them, with what each keeps. */
    static List<Root> current(String version) {
        return List.of(
                new Root("cache", JkDirs.cache(), STAMP, version, Kept.names(Set.of())),
                new Root("state", JkDirs.state(), STAMP, version, Kept.names(STATE_KEPT)),
                new Root(
                        "store",
                        JkStores.store(),
                        LAYOUT_STAMP,
                        Integer.toString(JkStores.LAYOUT),
                        dir -> StoreWipe.kept(dir, version)));
    }

    /**
     * Stamp every root that has no stamp yet, and empty and stamp again every root whose stamp names
     * something else, unless an engine of this home runs. {@code lock} serializes two clients
     * starting an engine at once. Returns the names of the roots it emptied, empty when it emptied
     * none.
     */
    static List<String> claim(List<Root> roots, Path lock, BooleanSupplier engineRunning, Consumer<String> log)
            throws IOException {
        if (allStamped(roots)) return List.of();
        Files.createDirectories(lock.getParent());
        return FileLocks.withLock(lock, () -> {
            List<Root> foreign = new ArrayList<>();
            for (Root root : roots) {
                String was = stamp(root);
                if (root.stamp().equals(was)) continue;
                if (was == null) {
                    writeStamp(root);
                } else {
                    foreign.add(root);
                }
            }
            if (foreign.isEmpty()) return List.of();
            if (engineRunning.getAsBoolean()) {
                log.accept("left what another jk version wrote: an engine of this home still runs");
                return List.of();
            }
            List<String> emptied = new ArrayList<>();
            for (Root root : foreign) {
                log.accept("discarding " + root.dir() + ": its " + root.stampFile() + " names " + stamp(root)
                        + ", this jk " + root.stamp());
                List<Path> kept = new ArrayList<>(root.kept().in(root.dir()));
                kept.add(root.dir().resolve(root.stampFile()));
                PathUtil.deleteTrees(StoreWipe.doomed(root.dir(), kept), new PathUtil.Removed());
                writeStamp(root);
                emptied.add(root.name());
            }
            return List.copyOf(emptied);
        });
    }

    private static boolean allStamped(List<Root> roots) {
        for (Root root : roots) if (!root.stamp().equals(stamp(root))) return false;
        return true;
    }

    /** What a root's stamp names, or {@code null} when it has no readable stamp. */
    static @Nullable String stamp(Root root) {
        try {
            String text = Files.readString(root.dir().resolve(root.stampFile()), StandardCharsets.UTF_8)
                    .strip();
            return text.isEmpty() ? null : text;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** {@code names} as a reader says them: {@code cache}, {@code cache and state}, {@code a, b and c}. */
    static String spoken(List<String> names) {
        if (names.size() < 2) return String.join("", names);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    private static void writeStamp(Root root) throws IOException {
        Files.createDirectories(root.dir());
        AtomicWrites.replace(root.dir().resolve(root.stampFile()), root.stamp() + "\n");
    }
}
