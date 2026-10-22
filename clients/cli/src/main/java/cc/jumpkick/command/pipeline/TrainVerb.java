// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.GraalResolver;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk train}: build one module, then observe a full-app run under the Graal tracing agent
 * and write {@code target/train/} (dynamic surface, reachability metadata, an optional AOT cache).
 * Never part of {@code jk build}. A workspace member's prerequisites build first.
 */
final class TrainVerb implements BuildVerb {

    private static final Words WORDS = new Words("Train", "training", "train", "Training");

    private @Nullable String trainProfile;

    /** The one module a run trains: the project itself, or the selection in a workspace. */
    private @Nullable Path moduleDir;

    private Map<Path, Path> graalHomes = Map.of();

    @Override
    public String name() {
        return "train";
    }

    @Override
    public String description() {
        return "Train reachability metadata and an optional AOT cache";
    }

    @Override
    public Words words() {
        return WORDS;
    }

    @Override
    public WorkspaceTarget target() {
        return WorkspaceTarget.TRAIN;
    }

    @Override
    public List<Opt> ownOptions() {
        return List.of(Opt.value("<name>", "Train profile (default: every profile)", "-T", "--train-profile"));
    }

    @Override
    public void parse(Invocation in, BuildRun run) {
        this.trainProfile = in.value("train-profile").orElse(null);
    }

    @Override
    public int selected(BuildRun run, List<String> moduleDirs) {
        if (moduleDirs.size() != 1) {
            run.fail("jk train runs one module — the selector matched " + moduleDirs.size());
            return Exit.USAGE;
        }
        this.moduleDir = Path.of(moduleDirs.getFirst());
        return -1;
    }

    @Override
    public int prepareWorkspace(BuildRun run, Path entryDir, BuildRun.Selection selection) {
        if (run.standalone()) moduleDir = entryDir;
        if (moduleDir == null) {
            run.fail("jk train runs one module — pass -m <module> or run from its directory");
            return Exit.USAGE;
        }
        // A Graal home makes the tracing agent available; without one the engine says so clearly.
        try {
            new GraalResolver(run.flags().jdksDir(), run.global().yes, run.mode())
                    .resolve(moduleDir, null)
                    .ifPresent(home -> graalHomes = Map.of(moduleDir, home));
        } catch (RuntimeException ignored) {
            // train fails clearly if the agent is missing
        }
        return -1;
    }

    @Override
    public WorkspaceResult workspace(BuildRun run, Path entryDir, List<String> modules, WorkspaceBuildListener listener)
            throws IOException {
        boolean force = SessionContext.current().force() || run.global().force;
        return EngineClient.buildWorkspace(
                EnginePaths.current(),
                run.request(entryDir, modules, WorkspaceSpec.train(Set.of(), graalHomes, trainProfile, force)),
                listener);
    }

    @Override
    public WorkspaceRunView.Tails tails(BuildRun run, WorkspaceRunView view, long startNanos) {
        return new WorkspaceRunView.Tails(
                (r, planned) ->
                        Theme.paint("Trained", Theme.active().focused()) + " " + BuildTails.elapsedSince(startNanos),
                r -> BuildTails.failureTail(WorkspaceRunView.failedSubject(r, "train"), startNanos));
    }
}
