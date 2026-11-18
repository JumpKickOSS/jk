// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.run.ContextPropagator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * A suite's discovered reads ({@link SuiteReads}) as a list that may leave the machine, and a key
 * taken as if the module's kept list were another one.
 *
 * <p>The list names each file portably: {@code ws:<workspace path>}, or under one of jk's roots
 * ({@code store:}, {@code cache:}, {@code jk-home:}). A suite found to start a program or reach the
 * network, or to read a file outside the workspace and jk's roots, has no such list: its result is
 * shared only with hosts that found the same reads themselves. A list read from elsewhere is held to
 * the same spellings, with no {@code ..} segment, so it can name nothing outside those roots.
 */
public final class SuiteReadLists {

    private SuiteReadLists() {}

    /** The module and the reads that stand in for its kept ones while a key is taken. */
    private record StandIn(Path moduleDir, SuiteReads.Discovered reads) {}

    /** Bound for the extent of {@link #as}, and carried to whichever lane takes the key. */
    private static final ScopedValue<StandIn> STAND_IN = ScopedValue.newInstance();

    static {
        ContextPropagator.carry(STAND_IN);
    }

    /**
     * Run {@code body} with {@code reads} standing in for what {@code moduleDir}'s suites were found
     * to read: a stamp key {@code body} takes for that module folds in {@code reads} and nothing kept.
     * {@link SuiteReads.Discovered#NONE} gives the key a host that never ran the suite takes.
     */
    public static <T extends @Nullable Object, X extends Throwable> T as(
            Path moduleDir, SuiteReads.Discovered reads, ScopedValue.CallableOp<T, X> body) throws X {
        return ScopedValue.where(STAND_IN, new StandIn(normal(moduleDir), reads))
                .call(body);
    }

    /** What stands in for {@code moduleDir}'s kept reads here, or null when nothing does. */
    static SuiteReads.@Nullable Discovered standIn(Path moduleDir) {
        StandIn s = ContextPropagator.get(STAND_IN);
        return s != null && s.moduleDir().equals(normal(moduleDir)) ? s.reads() : null;
    }

    /**
     * {@code reads} as portable names, for {@code moduleDir}'s workspace; null when they hold what no
     * such name carries (a program the suite started, the network) or a file outside the workspace and
     * jk's roots, or nothing at all.
     */
    public static @Nullable List<String> portable(Path moduleDir, SuiteReads.Discovered reads) {
        if (reads.files().isEmpty() || !reads.commands().isEmpty() || reads.network()) return null;
        Path workspace = workspace(moduleDir);
        List<String> out = new ArrayList<>();
        for (Path f : reads.files()) {
            String name = SuiteReads.keep(workspace, f);
            if (!portableName(name)) return null;
            out.add(name);
        }
        return out;
    }

    /**
     * The reads {@code names} spell, on this checkout and host; null when one is not a portable name
     * ({@link #portableName}) or the list is empty.
     */
    public static SuiteReads.@Nullable Discovered fromPortable(Path moduleDir, List<String> names) {
        if (names.isEmpty()) return null;
        Path workspace = workspace(moduleDir);
        Set<Path> files = new TreeSet<>();
        for (String name : names) {
            if (!portableName(name)) return null;
            files.add(SuiteReads.unspell(workspace, name));
        }
        return new SuiteReads.Discovered(files, Set.of(), Set.of());
    }

    /**
     * Whether {@code name} names a file under the workspace or one of jk's roots, by a relative path
     * with no empty, {@code .} or {@code ..} segment and no backslash.
     */
    static boolean portableName(String name) {
        int colon = name.indexOf(':');
        if (colon <= 0) return false;
        String root = name.substring(0, colon);
        if (!List.of("ws", "store", "cache", "jk-home").contains(root)) return false;
        String rel = name.substring(colon + 1);
        if (rel.isEmpty() || rel.contains("\\") || rel.startsWith("/") || rel.contains(":")) return false;
        for (String segment : rel.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    private static Path workspace(Path moduleDir) {
        return SuiteReads.workspaceOf(moduleDir);
    }

    private static Path normal(Path p) {
        return p.toAbsolutePath().normalize();
    }
}
