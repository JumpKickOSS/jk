// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ToolEntry;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The annotation processors a module's plugins contribute ({@code [[contribute.processor-dependency]]}),
 * as jars: each contribution's closure in the lock's closure-row order, fetched against the digests
 * the lock pins. The build and the forecast both derive the paths here, so both hash the same
 * {@code -processorpath}; {@link PlannerCompile#effectiveProcessorPath} appends them to the
 * module's own.
 */
final class ContributedProcessors {

    private ContributedProcessors() {}

    /** The contributed jars compile-main ({@code main}) and compile-test ({@code test}) append. */
    record Paths(List<Path> main, List<Path> test) {
        static final Paths NONE = new Paths(List.of(), List.of());

        Paths {
            main = List.copyOf(main);
            test = List.copyOf(test);
        }
    }

    /**
     * The module's contributed processor jars, {@code view} being the lock as the module reads it
     * (its {@code classpath-has} universe and the {@code [[tool]]} rows). A contribution the lock
     * pins no closure for fails naming it: a processor that silently does not run generates nothing.
     */
    static Paths resolve(JkBuild project, Path moduleDir, Lockfile view, Cas cas)
            throws IOException, InterruptedException {
        List<PluginContributions.ProcessorDep> deps = PluginContributions.processorDependencies(
                project, moduleDir, view.platformPins(), PlannerSupport.lockModules(view));
        if (deps.isEmpty()) return Paths.NONE;
        RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
        List<Path> main = new ArrayList<>();
        List<Path> test = new ArrayList<>();
        for (PluginContributions.ProcessorDep dep : deps) {
            for (Path jar : jars(dep.closure(), repos, view)) {
                if (dep.main() && !main.contains(jar)) main.add(jar);
                if (dep.test() && !test.contains(jar)) test.add(jar);
            }
        }
        return new Paths(main, test);
    }

    /**
     * {@link #resolve} for the forecast: a contribution that does not resolve leaves the forecast
     * without any, which keys a compile the build would not make — a predicted miss, never a false hit.
     */
    static Paths forecast(JkBuild project, Path moduleDir, Lockfile view, Cas cas) {
        try {
            return resolve(project, moduleDir, view, cas);
        } catch (IOException | RuntimeException e) {
            Log.debug("contributed processors of " + moduleDir + " left out of the forecast", e);
            return Paths.NONE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Paths.NONE;
        }
    }

    /** Every jar of {@code closure}'s lock row, in row order, each fetched as the lock pins it. */
    private static List<Path> jars(PluginContributions.StepDep closure, RepoGroup repos, Lockfile lock)
            throws IOException, InterruptedException {
        String root = Objects.requireNonNull(closure.coordinateSpec(), "coordinate");
        ToolEntry row = ToolEntry.closureIn(lock.tools(), root, closure.with(), closure.managedBySpec());
        if (row == null) {
            throw new IOException("jk-lock.toml pins no closure for the annotation processor " + root
                    + " a plugin contributes — run `jk lock`");
        }
        List<Path> out = new ArrayList<>();
        for (String jar : row.closure()) {
            out.add(PluginBuild.fetchPinned(repos, Coordinate.parse(jar), lock));
        }
        return out;
    }
}
