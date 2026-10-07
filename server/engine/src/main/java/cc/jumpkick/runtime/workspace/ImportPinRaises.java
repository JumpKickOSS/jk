// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.runtime.LockPipeline;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.base.LockMode;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        List<String> lines = new ArrayList<>();
        // A raised pin moves what the members depending on its member carry, which can put their own
        // pins below a floor in turn: probe again until a round raises nothing.
        for (int round = 0; round < MAX_ROUNDS; round++) {
            LockPlans.LockScope scope = LockPlans.lockScope(lockDir);
            Lockfile probe = new LockPipeline(
                            scope.lockDir(),
                            scope.effective(),
                            cache,
                            repoUrl,
                            List.of(),
                            true,
                            new LockMode.PinFloors())
                    .resolve(null, ResolveObserver.NOOP, LockPipeline.Progress.SILENT);
            ManifestUpdates.Plan plan = ManifestUpdates.raise(lockDir, probe);
            if (plan.rewrites().isEmpty()) break;
            ManifestUpdates.apply(plan);
            for (ManifestUpdates.Rewrite r : plan.rewrites()) lines.add(line(r, probe));
        }
        return lines;
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
