// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.runtime.ImagePush;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.ReleaseOutcome;
import cc.jumpkick.wire.runtime.TailArgs;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk publish}: the build, then the upload of each selected module's artifacts. {@link
 * PublishCommand} owns the options and the credential, resolved client-side; it hands the run here.
 */
final class PublishVerb implements BuildVerb {

    /**
     * What a finished publish prints: each module's library lane, then the release lane, then the
     * image lane. {@code released} names the Central deployments the release lane published after
     * the release.
     */
    interface Report {
        void module(ModuleOutcome module, ModuleOutcome.Published published, List<String> released) throws IOException;

        void release(ReleaseOutcome release) throws IOException;

        void images(List<ImagePush> images) throws IOException;
    }

    private static final Words WORDS = new Words("Publish", "publishing", "publish", "Publishing");

    private final TailArgs.Publish args;
    private final boolean nativeRelease;
    private final Report report;
    private @Nullable WorkspaceResult result;

    /** Client-resolved GraalVM home per module whose run links a native image. */
    private NativeGraal.Graal graal = NativeGraal.Graal.NONE;

    /**
     * {@code nativeRelease}: the run's release lane carries this host's platform-specific files, so
     * it builds the native executable of every module that ships one.
     */
    PublishVerb(TailArgs.Publish args, boolean nativeRelease, Report report) {
        this.args = args;
        this.nativeRelease = nativeRelease;
        this.report = report;
    }

    @Override
    public String name() {
        return "publish";
    }

    @Override
    public String description() {
        return "Publish libraries, release files and container images";
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
    public int prepareWorkspace(BuildRun run, Path entryDir, BuildRun.Selection selection) {
        Optional<NativeGraal.Graal> resolved = NativeGraal.reached(run, entryDir, selection, nativeRelease);
        if (resolved.isEmpty()) return Exit.FAILURE; // the resolver printed why
        this.graal = resolved.get();
        return -1;
    }

    @Override
    public WorkspaceResult workspace(BuildRun run, Path entryDir, List<String> modules, WorkspaceBuildListener listener)
            throws IOException {
        WorkspaceResult r = EngineClient.buildWorkspace(
                EnginePaths.current(),
                run.request(entryDir, modules, graal.onto(WorkspaceSpec.publish(Set.of(), args))),
                listener);
        this.result = r;
        return r;
    }

    @Override
    public WorkspaceRunView.Tails tails(BuildRun run, WorkspaceRunView view, long startNanos) {
        return new WorkspaceRunView.Tails(
                (r, planned) -> Theme.colorize(
                                args.dryRun() ? "Dry run assembled" : "Published",
                                Theme.active().success())
                        + " " + shipped(r) + " " + BuildTails.elapsedSince(startNanos),
                r -> BuildTails.failureTail(WorkspaceRunView.failedSubject(r, "publish"), startNanos));
    }

    /** What the run shipped, as its settle line names it: the modules' libraries, the release and the images. */
    static String shipped(WorkspaceResult r) {
        long modules = r.modules().stream().filter(m -> m.published() != null).count();
        ReleaseOutcome release = r.release();
        int images = r.images().size();
        List<String> parts = new ArrayList<>();
        if (modules > 0 || (release == null && images == 0)) parts.add(modules + " module" + (modules == 1 ? "" : "s"));
        if (release != null) parts.add(release.stage() ? "this platform's release files" : "the release");
        if (images > 0) parts.add(images + " image" + (images == 1 ? "" : "s"));
        if (parts.size() == 1) return parts.getFirst();
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
    }

    /**
     * Every module's report, success or not: a Portal deployment that was refused has an id and
     * errors to print beside the diagnostics already shown. Then the release lane's, when it ran,
     * and the images the image lane pushed.
     */
    @Override
    public int after(BuildRun run, int code) {
        WorkspaceResult r = result;
        if (r == null) return code;
        try {
            List<String> released =
                    r.release() == null ? List.of() : r.release().released();
            for (ModuleOutcome m : r.modules()) {
                if (m.published() != null) report.module(m, m.published(), released);
            }
            if (r.release() != null) report.release(r.release());
            if (!r.images().isEmpty()) report.images(r.images());
        } catch (IOException e) {
            CliOutput.err(e.getMessage());
            return code == 0 ? Exit.SOFTWARE : code;
        }
        return code;
    }
}
