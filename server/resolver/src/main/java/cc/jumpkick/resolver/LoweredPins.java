// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.version.Versions;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rows of a prior lock that stop speaking for a relock because the manifest now pins a module
 * below the version that lock held for it.
 *
 * <p>A relock reads the lock it replaces as preferences (keep-pins) or floors (a float), so a package
 * stays where it was. When the manifest pins a module lower, the pin rules the module's old version
 * out, but the old version's own graph would stay behind: the module's other classified rows (the
 * {@code ${javafx.platform}} jar an OpenJFX POM names) and every package that version alone pulled
 * in. That leaves the pinned module at two versions and its family above anything a POM now asks
 * for. Those rows go: the pinned module's rows, and every row reachable from them along the old
 * lock's {@code deps} that no other top row of the old lock reaches without passing through a
 * pinned module. A package something else still reaches keeps its old version as before.
 */
final class LoweredPins {

    private LoweredPins() {}

    /** {@code lock} without the rows a lower pin in {@code project} releases; {@code lock} itself when none does. */
    static Lockfile held(Lockfile lock, JkBuild project) {
        Map<String, String> pins = new HashMap<>();
        for (List<Dependency> deps : project.dependencies().byScope().values()) {
            for (Dependency d : deps) {
                if (d.isWorkspace()) continue;
                // The highest pin of a module pinned in several scopes: a test pin above main's is no lower pin.
                if (d.version() instanceof VersionSelector.Exact exact) {
                    pins.merge(d.module(), exact.version(), (a, b) -> Versions.compare(a, b) >= 0 ? a : b);
                }
            }
        }
        Set<String> lowered = new HashSet<>();
        for (Lockfile.Artifact row : lock.artifacts()) {
            String pin = pins.get(ga(row.name()));
            if (pin != null && Versions.compare(row.version(), pin) > 0) lowered.add(ga(row.name()));
        }
        if (lowered.isEmpty()) return lock;

        Map<String, Lockfile.Artifact> byCoord = new HashMap<>();
        Set<String> reached = new HashSet<>();
        for (Lockfile.Artifact row : lock.artifacts()) {
            byCoord.put(coord(row), row);
            reached.addAll(row.deps());
        }
        Deque<String> fromPins = new ArrayDeque<>();
        Deque<String> fromOthers = new ArrayDeque<>();
        for (Lockfile.Artifact row : lock.artifacts()) {
            if (lowered.contains(ga(row.name()))) fromPins.add(coord(row));
            else if (!reached.contains(coord(row))) fromOthers.add(coord(row));
        }
        Set<String> released = walk(fromPins, byCoord, Set.of());
        Set<String> kept = walk(fromOthers, byCoord, lowered);
        released.removeAll(kept);
        // Rows of the pinned module itself go whoever reaches them: the pin decides their version.
        for (Lockfile.Artifact row : lock.artifacts()) {
            if (lowered.contains(ga(row.name()))) released.add(coord(row));
        }
        return lock.withArtifacts(lock.artifacts().stream()
                .filter(row -> !released.contains(coord(row)))
                .toList());
    }

    /** Every row coordinate reachable from {@code start} along {@code deps}, never entering a module in {@code stop}. */
    private static Set<String> walk(Deque<String> start, Map<String, Lockfile.Artifact> byCoord, Set<String> stop) {
        Set<String> seen = new HashSet<>();
        while (!start.isEmpty()) {
            String c = start.pop();
            Lockfile.Artifact row = byCoord.get(c);
            if (row == null || !seen.add(c)) continue;
            for (String dep : row.deps()) {
                Lockfile.Artifact next = byCoord.get(dep);
                if (next != null && !stop.contains(ga(next.name()))) start.push(dep);
            }
        }
        return seen;
    }

    /** A row as its dependents' {@code deps} name it: {@code name@version}. */
    private static String coord(Lockfile.Artifact row) {
        return row.name() + "@" + row.version();
    }

    private static String ga(String name) {
        return PackageId.isMavenPackageKey(name) ? PackageId.parse(name).ga() : name;
    }
}
