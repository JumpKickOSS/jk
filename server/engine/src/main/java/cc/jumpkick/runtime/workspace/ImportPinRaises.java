// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.resolver.LockOrchestrator;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.runtime.LockPipeline;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.version.Versions;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;

/**
 * After {@code jk import}: an imported project's exact pins meet its dependencies' POMs the way
 * every other version does, highest wins. A probe solve reads each pin as a floor; every pin a
 * parent needs higher is raised in the written manifest to the version the graph resolves, so the
 * project locks as written. Nothing else changes, and nothing is locked.
 */
public final class ImportPinRaises {

    /** Parents a line names before it counts the rest. */
    private static final int PARENTS_NAMED = 3;

    private ImportPinRaises() {}

    /** Raise the pins of the lock scope at {@code lockDir}; one line per raise, empty when none moved. */
    public static List<String> apply(Path lockDir, Path cache) throws Exception {
        return apply(lockDir, cache, null);
    }

    /** {@link #apply(Path, Path)} resolving from {@code repoUrl} in place of the default repositories. */
    static List<String> apply(Path lockDir, Path cache, @Nullable URI repoUrl) throws Exception {
        return rounds(lockDir, cache, repoUrl, true).lines();
    }

    /** What one probe round solved: member solves run and reused, and whether the merged solve ran. */
    record Round(int memberSolves, int memberReuses, int mergedSolves) {}

    /** The report lines and each round's solve counts. */
    record Outcome(List<String> lines, List<Round> rounds) {}

    /**
     * The raise, round by round. With {@code reuse}, a later round re-solves only the members whose
     * solve inputs moved and keeps the previous merged solve when no raise can move it; without, every
     * round solves everything — the same answers, at the cost of a full solve each.
     */
    static Outcome rounds(Path lockDir, Path cache, @Nullable URI repoUrl, boolean reuse) throws Exception {
        List<String> lines = new ArrayList<>();
        List<Round> spent = new ArrayList<>();
        LockOrchestrator.Rounds series =
                reuse ? new LockOrchestrator.Rounds() : LockOrchestrator.Rounds.solvingEverything();
        // A raised pin moves what the members depending on its member carry, which can put their own
        // pins below a floor in turn: probe again while a raise moves a graph the probe resolved.
        for (int round = 0; round < MAX_ROUNDS; round++) {
            LockPlans.LockScope scope = LockPlans.lockScope(lockDir);
            PROBES.increment();
            series.resetCounts();
            Lockfile probe = new LockPipeline(
                            scope.lockDir(),
                            scope.effective(),
                            cache,
                            repoUrl,
                            List.of(),
                            true,
                            new LockMode.PinFloors())
                    .withRounds(series)
                    .resolve(null, ResolveObserver.NOOP, LockPipeline.Progress.SILENT);
            spent.add(new Round(series.memberSolves(), series.memberReuses(), series.mergedSolves()));
            ManifestUpdates.Plan plan = ManifestUpdates.raise(lockDir, probe);
            if (plan.rewrites().isEmpty()) break;
            ManifestUpdates.apply(plan);
            for (ManifestUpdates.Rewrite r : plan.rewrites()) lines.add(line(r, probe));
            List<LockOrchestrator.Member> members = scope.workspace()
                    ? LockPlans.lockMembers(lockDir, scope.effective()).members()
                    : List.of();
            if (!movesAGraph(probe, lockDir, members, plan.rewrites())) break;
            series.reuseMergedNext(!movesMerged(probe, lockDir, plan.rewrites()));
        }
        return new Outcome(lines, spent);
    }

    /**
     * Whether {@code rewrites} can move the merged solve: a raise of the root's or the workspace's
     * entry, or of a member pin the merged rows hold below its new version. A member pin is a floor in
     * the merged solve, so raising it to a version the merged graph already holds changes nothing.
     */
    static boolean movesMerged(Lockfile probe, Path lockDir, List<ManifestUpdates.Rewrite> rewrites) {
        Path root = lockDir.toAbsolutePath().normalize();
        List<Lockfile.Artifact> merged =
                probe.artifacts().stream().filter(a -> !a.isPartition()).toList();
        for (ManifestUpdates.Rewrite r : rewrites) {
            if (r.dir().toAbsolutePath().normalize().equals(root)) return true;
            if (r.table().equals(ManifestUpdates.WORKSPACE_TABLE)) return true;
            if (below(merged, r)) return true;
        }
        return false;
    }

    /** Probe solves run so far: the seam that proves a round is spent only where it can find a raise. */
    static final LongAdder PROBES = new LongAdder();

    /**
     * Whether {@code rewrites} can change a graph {@code probe} resolved, so a further probe may find
     * a raise. A raised pin reaches the graphs that read it — every graph for the root's or the
     * workspace's entry, else the members that carry the module through the member whose pin rose —
     * and changes one only where that graph holds the module below its new version. Where none does,
     * the probe that planned the raises has already seen everything they lead to.
     */
    static boolean movesAGraph(
            Lockfile probe,
            Path lockDir,
            List<LockOrchestrator.Member> members,
            List<ManifestUpdates.Rewrite> rewrites) {
        Path root = lockDir.toAbsolutePath().normalize();
        for (ManifestUpdates.Rewrite r : rewrites) {
            Path dir = r.dir().toAbsolutePath().normalize();
            boolean shared = dir.equals(root) || r.table().equals(ManifestUpdates.WORKSPACE_TABLE);
            if (shared
                    && below(
                            probe.artifacts().stream()
                                    .filter(a -> !a.isPartition())
                                    .toList(),
                            r)) return true;
            String from = root.relativize(dir).toString().replace('\\', '/');
            for (LockOrchestrator.Member m : members) {
                boolean reads = shared || (!m.path().equals(from) && m.carried().contains(r.module()));
                if (reads && below(probe.forMember(m.path()).artifacts(), r)) return true;
            }
        }
        return false;
    }

    /** Whether {@code rows} hold {@code r}'s module below the version it was raised to. */
    private static boolean below(List<Lockfile.Artifact> rows, ManifestUpdates.Rewrite r) {
        for (Lockfile.Artifact a : rows) {
            if (!PackageId.isMavenPackageKey(a.name())) continue;
            if (PackageId.parse(a.name()).ga().equals(r.module()) && Versions.compare(a.version(), r.to()) < 0)
                return true;
        }
        return false;
    }

    /** Probe rounds before the raise stops; each round moves only pins below a version the graph holds. */
    static final int MAX_ROUNDS = 4;

    /** One report line for {@code r}, naming up to {@value #PARENTS_NAMED} of the modules that depend on it. */
    static String line(ManifestUpdates.Rewrite r, Lockfile probe) {
        List<String> parents = new ArrayList<>();
        for (Lockfile.Artifact a : probe.artifacts()) {
            if (!PackageId.isMavenPackageKey(a.name())) continue;
            for (String dep : a.deps()) {
                if (!dep.startsWith(r.module() + ":")) continue;
                String parent = PackageId.parse(a.name()).ga() + " " + a.version();
                if (!parents.contains(parent)) parents.add(parent);
                break;
            }
        }
        String via = parents.isEmpty()
                ? ""
                : " (depended on by " + String.join(", ", parents.subList(0, Math.min(PARENTS_NAMED, parents.size())))
                        + (parents.size() > PARENTS_NAMED ? " and " + (parents.size() - PARENTS_NAMED) + " more" : "")
                        + ")";
        return "`" + r.module() + "` " + r.from() + " → " + r.to()
                + " ([" + r.table() + "]" + (r.moduleLabel().isEmpty() ? "" : " in " + r.moduleLabel()) + ")"
                + ": the POM's pin sits below what its dependencies need" + via
                + "; highest wins, so the pin is raised to the version the lock resolves";
    }
}
