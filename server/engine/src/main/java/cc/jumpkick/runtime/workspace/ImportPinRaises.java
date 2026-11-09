// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.compat.ImportProgress;
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
 * every other version does: the highest declared floor wins. A probe solve reads each pin as a floor; every pin a
 * parent needs higher is raised in the written manifest to the version the graph resolves, so the
 * project locks as written. Nothing else changes, and nothing is locked.
 */
public final class ImportPinRaises {

    /** Parents a line names before it counts the rest. */
    private static final int PARENTS_NAMED = 3;

    private ImportPinRaises() {}

    /**
     * Raise the pins of the lock scope at {@code lockDir}; one line per raise, empty when none moved.
     * {@code progress} hears each probe round and the packages its solves settle.
     */
    public static List<String> apply(Path lockDir, Path cache, ImportProgress progress) throws Exception {
        return rounds(lockDir, cache, null, true, progress).lines();
    }

    /** {@link #apply(Path, Path, ImportProgress)} resolving from {@code repoUrl} in place of the default repositories. */
    static List<String> apply(Path lockDir, Path cache, @Nullable URI repoUrl) throws Exception {
        return rounds(lockDir, cache, repoUrl, true).lines();
    }

    /**
     * What one probe round solved: member solves run and reused, whether the merged solve ran, and the
     * catalog and POM reads its solves completed.
     */
    record Round(int memberSolves, int memberReuses, int mergedSolves, long reads) {}

    /** The report lines and each round's solve counts. */
    record Outcome(List<String> lines, List<Round> rounds) {}

    /**
     * The raise, round by round. With {@code reuse}, a later round re-solves only the members whose
     * solve inputs moved and keeps the previous merged solve when no raise can move it; without, every
     * round solves everything — the same answers, at the cost of a full solve each.
     */
    static Outcome rounds(Path lockDir, Path cache, @Nullable URI repoUrl, boolean reuse) throws Exception {
        return rounds(lockDir, cache, repoUrl, reuse, ImportProgress.NONE);
    }

    private static Outcome rounds(
            Path lockDir, Path cache, @Nullable URI repoUrl, boolean reuse, ImportProgress progress) throws Exception {
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
            String prefix = "round " + (round + 1) + " · ";
            progress.detail(prefix + "resolving with each pin as a minimum version");
            Lockfile probe = new LockPipeline(
                            scope.lockDir(),
                            scope.effective(),
                            cache,
                            repoUrl,
                            List.of(),
                            true,
                            new LockMode.PinFloors())
                    .withRounds(series)
                    .resolve(null, observer(progress, prefix), lockProgress(progress, prefix));
            spent.add(new Round(series.memberSolves(), series.memberReuses(), series.mergedSolves(), series.reads()));
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

    /** The probe's resolve phases and settled packages as the import step's detail and units. */
    private static ResolveObserver observer(ImportProgress progress, String prefix) {
        return new ResolveObserver() {
            private int seen;
            private int settled;

            @Override
            public synchronized void onTotal(int total) {
                if (total > seen) progress.grow(total - seen);
                seen = Math.max(seen, total);
            }

            @Override
            public void onPackage(String module, String version) {}

            @Override
            public void onPhase(String label) {
                if (label != null && !label.isBlank()) progress.detail(prefix + label);
            }

            @Override
            public synchronized void onGraphPackage(String module, String version) {
                // Units only up to the total the solver announced: a round re-solving settled members
                // must not run the bar past its end.
                if (settled < seen) {
                    settled++;
                    progress.advance(1);
                }
                progress.detail(prefix + "resolved " + module + " " + version);
            }
        };
    }

    /** The probe's lock labels as the import step's detail. */
    private static LockPipeline.Progress lockProgress(ImportProgress progress, String prefix) {
        return new LockPipeline.Progress() {
            @Override
            public void label(String text) {
                progress.detail(prefix + text);
            }
        };
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
                : "; needed by " + String.join(", ", parents.subList(0, Math.min(PARENTS_NAMED, parents.size())))
                        + (parents.size() > PARENTS_NAMED ? " and " + (parents.size() - PARENTS_NAMED) + " more" : "");
        return r.module() + " raised " + r.from() + " → " + r.to()
                + (r.moduleLabel().isEmpty() ? "" : " in " + r.moduleLabel()) + via;
    }
}
