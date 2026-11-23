// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.HostTemp;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Where the engines that draw on one memory budget keep their {@link HostLeases} file: one
 * directory per user and per budget, whatever state directory each engine runs from. Engines with
 * different homes in one container (a gate's nested engines, a test's own engine beside the
 * user's) share the cgroup's memory, so they lease from one total rather than each from the whole.
 *
 * <p>The budget is the cgroup v2 group with the tightest finite {@code memory.max} above this
 * process, named {@code cgroup-<inode>} by the kernel's id for that group, or {@code host} when no
 * group above sets one, and everywhere but Linux. The directory is {@code jk-<user>-leases/<budget>}
 * under {@link HostTemp#dir()}, {@code 0700}. An engine that cannot create it, or finds it owned
 * by someone else, open to others, or a link, keeps the file in its own state directory as before.
 *
 * <p>An engine started inside a worker of an engine recorded in one of those directories, such as a
 * test's engine inside a test JVM, leases in {@value #NESTED} under that directory instead, with
 * every other engine started inside a worker of that budget's engines. The outer engine charges
 * each worker at its whole tree's resident set, so the inner engines cannot lease from the outer
 * total too: their forks would count twice, and would wait on memory only their own worker's exit
 * frees. Nor can each lease alone, or from its worker's own group, which caps it at a share of the
 * budget: the inner engines of two test JVMs would then each be granted that share.
 */
final class LeaseScope {

    private LeaseScope() {}

    /** The name of the budget no cgroup limits. */
    static final String HOST = "host";

    private static final Set<PosixFilePermission> OWNER_ONLY =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    /** The directory under a budget's where the engines inside its engines' workers share their leases. */
    static final String NESTED = "nested";

    /** The directory this engine's leases are shared in; {@code engineDir} when they are its own. */
    static Path dir(Path engineDir) {
        Path leases = HostTemp.dir().resolve(userDir(System.getProperty("user.name")));
        Path inner = innerOf(leases, ancestry(ProcessHandle.current()));
        Path shared = inner != null ? inner : leases.resolve(budget(OwnCgroup.HOST, Os.isLinux()));
        return usable(shared, engineDir);
    }

    /**
     * {@code <dir>/}{@value #NESTED} for the directory under {@code leases} where an engine recorded
     * there forked one of {@code ancestors}, said in the engine log; null when no engine did. Each
     * budget's directory is read, and the {@value #NESTED} ones below it, the deepest match winning:
     * an engine inside a worker of an inner engine leases a level further down, apart from the inner
     * engine that charges it.
     */
    static @Nullable Path innerOf(Path leases, Set<Long> ancestors) {
        Path found = null;
        HostLeases.Owner forker = null;
        int deepest = -1;
        for (Path budget : budgets(leases)) {
            int depth = 0;
            for (Path dir = budget; Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS); dir = dir.resolve(NESTED)) {
                HostLeases.Owner owner = HostLeases.forkedOneOf(dir, ancestors);
                if (owner != null && depth > deepest) {
                    found = dir;
                    forker = owner;
                    deepest = depth;
                }
                depth++;
            }
        }
        if (found == null || forker == null) return null;
        Path inner = found.resolve(NESTED);
        Log.info("jk engine: worker leases shared at " + inner + " with every engine inside a worker of " + found
                + ": this engine runs inside a worker of engine pid " + forker.pid()
                + ", which already charges its memory");
        return inner;
    }

    /** The budget directories under {@code leases}; none when it cannot be listed. */
    private static List<Path> budgets(Path leases) {
        try {
            return PathUtil.childDirectories(leases).stream().sorted().toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** {@code process} and every process above it, nearest first. */
    static Set<Long> ancestry(ProcessHandle process) {
        Set<Long> pids = new LinkedHashSet<>();
        Optional<ProcessHandle> p = Optional.of(process);
        while (p.isPresent() && pids.add(p.get().pid())) p = p.get().parent();
        return pids;
    }

    /**
     * The name of the budget {@code root}'s cgroups set: {@code cgroup-<inode>} of the group with the
     * tightest finite {@code memory.max} above this process, {@code host} when none, or off Linux.
     */
    static String budget(Path root, boolean linux) {
        if (!linux) return HOST;
        Path group = tightestGroup(OwnCgroup.chain(root));
        return group == null ? HOST : "cgroup-" + groupId(group);
    }

    /** The group along {@code chain} whose finite {@code memory.max} is the smallest; null when none is finite. */
    static @Nullable Path tightestGroup(List<Path> chain) {
        Path tightest = null;
        long least = -1L;
        for (Path group : chain) {
            long max = OwnCgroup.limit(OwnCgroup.read(group.resolve("memory.max")));
            if (max > 0 && (least < 0 || max < least)) {
                least = max;
                tightest = group;
            }
        }
        return tightest;
    }

    /** {@code jk-<user>-leases}, the user name kept to letters, digits, {@code .}, {@code _} and {@code -}. */
    static String userDir(@Nullable String user) {
        String name = user == null ? "" : user.replaceAll("[^A-Za-z0-9._-]", "_");
        return "jk-" + (name.isEmpty() ? "_" : name) + "-leases";
    }

    /**
     * The kernel's id for {@code group}, its inode: two containers that both see their group as the
     * mount's root still differ. A hash of the path when the filesystem does not say.
     */
    private static String groupId(Path group) {
        try {
            Object ino = Files.getAttribute(group, "unix:ino");
            if (ino instanceof Number n) return Long.toString(n.longValue());
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            // The path below names the group as well, only not across containers.
        }
        return "p"
                + Hashing.sha256Hex(group.toAbsolutePath().normalize().toString())
                        .substring(0, 16);
    }

    /**
     * {@code shared}, created {@code 0700} with its parent, when both are this user's own real
     * directories open to no one else; otherwise {@code fallback}, said in the engine log.
     */
    static Path usable(Path shared, Path fallback) {
        try {
            OwnerOnlyFiles.directory(shared);
            Path parent = shared.getParent();
            String refused = parent == null ? null : refusal(parent);
            if (refused == null) refused = refusal(shared);
            if (refused == null) return shared;
            Log.warn("jk engine: worker leases kept to this engine's home, not shared at " + shared + ": " + refused);
        } catch (IOException | RuntimeException e) {
            Log.warn("jk engine: worker leases kept to this engine's home, not shared at " + shared + ": "
                    + e.getMessage());
        }
        return fallback;
    }

    /** Why {@code dir} cannot hold the shared file, or null when it can. */
    private static @Nullable String refusal(Path dir) throws IOException {
        if (Files.isSymbolicLink(dir)) return dir + " is a link";
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return dir + " is not a directory";
        PosixFileAttributeView view =
                Files.getFileAttributeView(dir, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) return null;
        PosixFileAttributes attrs = view.readAttributes();
        Object owner = Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS);
        Object self = selfUid();
        if (self != null && !self.equals(owner)) return dir + " belongs to another user";
        if (!OWNER_ONLY.containsAll(attrs.permissions())) return dir + " is open to other users";
        return null;
    }

    /** This process's uid, read off its own {@code /proc/self}; null where there is none. */
    private static @Nullable Object selfUid() {
        try {
            return Files.getAttribute(Path.of("/proc/self"), "unix:uid");
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return null;
        }
    }
}
