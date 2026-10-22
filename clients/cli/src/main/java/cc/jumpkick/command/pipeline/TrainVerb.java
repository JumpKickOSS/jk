// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.GraalResolver;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk train}: package one module, then observe a full-app run under the Graal tracing agent
 * and write {@code target/train/} (dynamic surface, reachability metadata, an optional AOT cache).
 * Never part of {@code jk build}.
 */
final class TrainVerb implements BuildVerb {

    private static final Words WORDS = new Words("Train", "training", "train", "Training");

    private @Nullable String trainProfile;

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
    public boolean workspaceShaped() {
        return false;
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
    public WorkspaceResult workspace(
            BuildRun run, Path entryDir, List<String> modules, WorkspaceBuildListener listener) {
        throw new IllegalStateException("jk train runs one module, never a workspace");
    }

    @Override
    public WorkspaceRunView.Tails tails(BuildRun run, WorkspaceRunView view, long startNanos) {
        throw new IllegalStateException("jk train runs one module, never a workspace");
    }

    @Override
    public int single(BuildRun run, Path dir) {
        BuildFlags flags = run.flags();
        // A Graal home makes the tracing agent available; without one the engine says so clearly.
        Path graalHome = null;
        try {
            graalHome = new GraalResolver(flags.jdksDir(), run.global().yes, run.mode())
                    .resolve(dir, null)
                    .orElse(null);
        } catch (RuntimeException ignored) {
            // train fails clearly if the agent is missing
        }
        var session = SessionContext.current();
        var request = new EngineRequests.TrainRequest(
                dir,
                flags.cache(),
                flags.jdksDir(),
                graalHome,
                trainProfile,
                session.force() || run.global().force,
                run.skipTests(),
                session.offline(),
                run.global().verbose);
        ConsoleSpec spec = new ConsoleSpec(
                WORDS.name(), r -> Theme.paint("Trained", Theme.active().focused()), r -> "Train failed");
        String module = ProjectInfos.buildTarget(ManifestPaths.manifestIn(dir), dir);
        return run.settleSingle(
                spec, module, null, listeners -> EngineClient.runTrain(EnginePaths.current(), request, listeners));
    }
}
