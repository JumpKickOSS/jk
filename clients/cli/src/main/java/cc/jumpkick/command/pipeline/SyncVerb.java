// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk sync}: the first phase of every build, run on its own. The lock is freshened and every
 * artifact, plugin, tool and JDK the lock pins is put on disk by the same preflight a build runs
 * ({@link WorkspaceTarget#SYNC}), drawn by the same region and recorded the same way, then the run
 * ends. A sync is therefore never a second implementation of a build's sync: what one shows, the
 * other does.
 */
final class SyncVerb implements BuildVerb {

    private static final Words WORDS = new Words("Sync", "syncing", "sync", "Syncing");

    private boolean sources;
    private @Nullable URI repoUrl;

    @Override
    public String name() {
        return "sync";
    }

    @Override
    public String description() {
        return "Ensure our local cache has all project dependencies";
    }

    @Override
    public Words words() {
        return WORDS;
    }

    @Override
    public WorkspaceTarget target() {
        return WorkspaceTarget.SYNC;
    }

    @Override
    public List<Opt> ownOptions() {
        return List.of(
                Opt.flag("Also fetch every library's sources JAR (for the IDE).", "-s", "--sources"),
                Opt.value("<url>", "Override declared repos with a single URL (for tests).", "--repo-url")
                        .hide());
    }

    @Override
    public void parse(Invocation in, BuildRun run) {
        this.sources = in.isSet("sources");
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
    }

    @Override
    public WorkspaceResult workspace(BuildRun run, Path entryDir, List<String> modules, WorkspaceBuildListener listener)
            throws IOException {
        return EngineClient.buildWorkspace(
                EnginePaths.current(), run.request(entryDir, modules, WorkspaceSpec.sync(sources, repoUrl)), listener);
    }

    @Override
    public WorkspaceRunView.Tails tails(BuildRun run, WorkspaceRunView view, long startNanos) {
        return new WorkspaceRunView.Tails(
                (r, planned) -> Theme.colorize("Synced", Theme.active().focused())
                        + summary(r.sync())
                        + " "
                        + BuildTails.elapsedSince(startNanos),
                r -> BuildTails.failureTail(WorkspaceRunView.failedSubject(r, "sync"), startNanos));
    }

    /** {@code : 12 fetched, 171 up-to-date}, or {@code : already up to date}; nothing without counts. */
    static String summary(WorkspaceResult.@Nullable SyncCounts sync) {
        if (sync == null) return "";
        if (sync.fetched() == 0 && sync.upToDate() == 0) return ": already up to date";
        return ": " + sync.fetched() + " fetched, " + sync.upToDate() + " up-to-date";
    }
}
