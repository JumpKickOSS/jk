// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.TailArgs;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk publish}: the build, then the upload of each selected module's artifacts. {@link
 * PublishCommand} owns the options and the credential, resolved client-side; it hands the run here.
 */
final class PublishVerb implements BuildVerb {

    /** What a finished publish prints for one module: where it went, what it wrote. */
    @FunctionalInterface
    interface Report {
        void module(ModuleOutcome module, ModuleOutcome.Published published) throws IOException;
    }

    private static final Words WORDS = new Words("Publish", "publishing", "publish", "Publishing");

    private final TailArgs.Publish args;
    private final Report report;
    private @Nullable WorkspaceResult result;

    PublishVerb(TailArgs.Publish args, Report report) {
        this.args = args;
        this.report = report;
    }

    @Override
    public String name() {
        return "publish";
    }

    @Override
    public String description() {
        return "Publish artifacts to a package repository";
    }

    @Override
    public Words words() {
        return WORDS;
    }

    @Override
    public WorkspaceTarget target() {
        return WorkspaceTarget.PUBLISH;
    }

    @Override
    public WorkspaceResult workspace(BuildRun run, Path entryDir, List<String> modules, WorkspaceBuildListener listener)
            throws IOException {
        WorkspaceResult r = EngineClient.buildWorkspace(
                EnginePaths.current(), run.request(entryDir, modules, WorkspaceSpec.publish(Set.of(), args)), listener);
        this.result = r;
        return r;
    }

    @Override
    public WorkspaceRunView.Tails tails(BuildRun run, WorkspaceRunView view, long startNanos) {
        return new WorkspaceRunView.Tails(
                (r, planned) -> {
                    long published = r.modules().stream()
                            .filter(m -> m.published() != null)
                            .count();
                    String what = args.dryRun() ? "Dry run assembled" : "Published";
                    return Theme.colorize(what, Theme.active().success()) + " " + published + " module"
                            + (published == 1 ? "" : "s") + " " + BuildTails.elapsedSince(startNanos);
                },
                r -> BuildTails.failureTail(WorkspaceRunView.failedSubject(r, "publish"), startNanos));
    }

    /**
     * Every module's report, success or not: a Portal deployment that was refused has an id and
     * errors to print beside the diagnostics already shown.
     */
    @Override
    public int after(BuildRun run, int code) {
        WorkspaceResult r = result;
        if (r == null) return code;
        try {
            for (ModuleOutcome m : r.modules()) {
                if (m.published() != null) report.module(m, m.published());
            }
        } catch (IOException e) {
            CliOutput.err(e.getMessage());
            return code == 0 ? Exit.SOFTWARE : code;
        }
        return code;
    }
}
