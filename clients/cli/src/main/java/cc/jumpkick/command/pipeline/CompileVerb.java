// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk compile}: main and test sources compiled with the same incremental cache as {@code jk
 * build}; nothing packaged, nothing run. A workspace member's prerequisites package first.
 */
final class CompileVerb implements BuildVerb {

    private final Words words;
    private final String done;

    CompileVerb() {
        this("Compile", "Compiled");
    }

    /** A compile told under another name: the dev loop's {@code Watch} … {@code Recompiled}. */
    CompileVerb(String name, String done) {
        this.words = new Words(name, "compiling", "compile", "Compiling");
        this.done = done;
    }

    @Override
    public String name() {
        return "compile";
    }

    @Override
    public String description() {
        return "Compile this project's source code";
    }

    @Override
    public Words words() {
        return words;
    }

    @Override
    public WorkspaceTarget target() {
        return WorkspaceTarget.COMPILE;
    }

    @Override
    public WorkspaceResult workspace(BuildRun run, Path entryDir, List<String> modules, WorkspaceBuildListener listener)
            throws IOException {
        return EngineClient.runCompileWorkspace(EnginePaths.current(), request(run, entryDir, modules), listener);
    }

    @Override
    public WorkspaceRunView.Tails tails(BuildRun run, WorkspaceRunView view, long startNanos) {
        return new WorkspaceRunView.Tails(
                (r, planned) -> doneLine(startNanos),
                r -> BuildTails.failureTail(WorkspaceRunView.failedSubject(r, "compile"), startNanos));
    }

    @Override
    public int single(BuildRun run, Path dir) {
        long start = Clock.SYSTEM.nanos();
        ConsoleSpec spec = new ConsoleSpec(words.name(), r -> doneLine(start), r -> "Compilation failed");
        String module = ProjectInfos.buildTarget(ManifestPaths.manifestIn(dir), dir);
        return run.settleSingle(
                spec,
                module,
                null,
                listeners -> EngineClient.runCompile(EnginePaths.current(), request(run, dir, List.of()), listeners));
    }

    private String doneLine(long startNanos) {
        return Theme.colorize(done, Theme.active().focused()) + " " + BuildTails.elapsedSince(startNanos);
    }

    private static EngineRequests.CompileRequest request(BuildRun run, Path dir, List<String> modules) {
        var session = SessionContext.current();
        BuildFlags flags = run.flags();
        return new EngineRequests.CompileRequest(
                dir,
                flags.cache(),
                flags.jdksDir(),
                flags.profile(),
                session.offline(),
                session.force(),
                run.global().verbose,
                flags.keepGoing(),
                modules);
    }
}
