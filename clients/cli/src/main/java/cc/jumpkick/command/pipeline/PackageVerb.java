// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.GraalResolver;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.tui.Coord;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** {@code jk build}: lock, sync, compile, test and package. */
final class PackageVerb implements BuildVerb {

    private static final String AOT_CACHE = "aot-cache";

    private final Words words;
    private boolean aotCache;

    /** Client-resolved GraalVM home per always-native member; empty when the workspace links none. */
    private Map<Path, Path> graalHomes = Map.of();

    PackageVerb() {
        this("Build");
    }

    /** A build told under another name: the dev loop's {@code Watch}. */
    PackageVerb(String name) {
        this.words = new Words(name, "building", "build", "Building");
    }

    @Override
    public String name() {
        return "build";
    }

    @Override
    public String description() {
        return "Compile, test, and package the project";
    }

    @Override
    public Words words() {
        return words;
    }

    @Override
    public BuildFlags.Reach reach() {
        return BuildFlags.Reach.DELIVER;
    }

    @Override
    public List<Opt> ownOptions() {
        return List.of(Opt.flag("Package with JVM startup AOT cache", "--" + AOT_CACHE));
    }

    @Override
    public void parse(Invocation in, BuildRun run) {
        this.aotCache = in.isSet(AOT_CACHE);
    }

    @Override
    public int prepareWorkspace(BuildRun run, Path entryDir, BuildRun.Selection selection) {
        if (aotCache) {
            run.fail("--aot-cache packages a single application project; run it from the module directory.");
            return Exit.USAGE;
        }
        // The GraalVM home for every always-native member this build reaches, before any progress UI
        // opens: the resolver may prompt or install, and the request carries the answer (the engine is
        // a daemon and must not pick one from the shell that started it). A member the selection
        // leaves out is not asked about.
        List<AlwaysNativeGraal.Module> members = AlwaysNativeGraal.fromManifests(entryDir);
        if (selection.confines()) members = AlwaysNativeGraal.within(members, selection.dirs());
        // A member that inherits its `java` from the root declares none of its own; the summary has it.
        if (!members.isEmpty()) {
            members = AlwaysNativeGraal.withEffectiveReleases(members, ProjectInfos.orNull(entryDir));
        }
        Optional<Map<Path, Path>> graal = AlwaysNativeGraal.homes(
                members, new GraalResolver(run.flags().jdksDir(), run.global().yes, run.mode())::resolve);
        if (graal.isEmpty()) return Exit.FAILURE; // the resolver printed why
        this.graalHomes = graal.get();
        return -1;
    }

    @Override
    public WorkspaceResult workspace(BuildRun run, Path entryDir, List<String> modules, WorkspaceBuildListener listener)
            throws IOException {
        BuildFlags flags = run.flags();
        WorkspaceRequest request = run.workspaceRequest(entryDir, modules, flags.skipTests(), flags.jobs())
                .withSpec(WorkspaceSpec.DEFAULT.withGraalByDir(graalHomes));
        return EngineClient.buildWorkspace(EnginePaths.current(), request, listener);
    }

    @Override
    public WorkspaceRunView.Tails tails(BuildRun run, WorkspaceRunView view, long startNanos) {
        return new WorkspaceRunView.Tails(
                (r, planned) -> BuildTails.successTail(r.modules(), planned, startNanos),
                r -> BuildTails.failureTail(WorkspaceRunView.failedSubject(r, "build"), startNanos));
    }

    @Override
    public int single(BuildRun run, Path dir) {
        long start = Clock.SYSTEM.nanos();
        BuildFlags flags = run.flags();
        String target = ProjectInfos.buildTarget(ManifestPaths.manifestIn(dir), dir);
        // Fully cached: the engine's forecast answers in one round trip and no region opens. A
        // distrusting build (--force / --redo) never takes the trust-the-cache shortcut.
        if (BuildPlanConsole.isInteractiveTerminal() && !run.global().outputIsJson() && !flags.rebuild()) {
            try {
                var forecast = EngineClient.forecast(EnginePaths.current(), dir, flags.cache(), flags.skipTests());
                if (!forecast.hasErrors() && !forecast.empty() && forecast.fullyCached()) {
                    run.ok(BuildTails.buildOk() + ", project up to date " + BuildTails.elapsedSince(start));
                    return 0;
                }
            } catch (IOException | RuntimeException ignored) {
                // best-effort shortcut — fall through to the real build
            }
        }
        // The outcome rides the wire (only the engine knows it); the layout the tail points at is the
        // engine's project summary of the same directory.
        TestSummary[] tests = new TestSummary[1];
        String[] outcome = new String[1];
        ProjectInfo info = ProjectInfos.orNull(dir);
        Path graalHome = alwaysNativeGraal(run, dir, info);
        if (graalHome == NO_GRAAL) return Exit.FAILURE; // the resolver printed why
        ConsoleSpec spec = new ConsoleSpec(
                words.name(),
                r -> BuildTails.projectTail(outcome[0], dir, info),
                r -> Coord.module(target).renderLine(),
                true);
        var session = SessionContext.current();
        var request = new EngineRequests.SingleBuildRequest(
                dir,
                flags.cache(),
                flags.jdksDir(),
                flags.workers(),
                flags.profile(),
                flags.skipTests(),
                run.global().verbose,
                session.offline(),
                session.force(),
                run.variant(),
                run.clientEnv(),
                graalHome);
        return run.settleSingle(
                spec,
                target,
                tests,
                listeners -> EngineClient.runSingleBuild(EnginePaths.current(), request, listeners, tests, outcome));
    }

    /** Marks a GraalVM home the resolver could not settle; the build stops. */
    private static final Path NO_GRAAL = Path.of("");

    /** A lone always-native module ships its GraalVM home; null for any other project. */
    private static @Nullable Path alwaysNativeGraal(BuildRun run, Path dir, @Nullable ProjectInfo info) {
        AlwaysNativeGraal.Module alwaysNative = AlwaysNativeGraal.fromManifest(dir);
        if (alwaysNative == null) return null;
        // The engine's summary has the root's inheritance applied; the manifest read does not.
        int javaRelease = info != null ? info.javaRelease() : alwaysNative.javaRelease();
        return new GraalResolver(run.flags().jdksDir(), run.global().yes, run.mode())
                .resolve(dir, alwaysNative.graalSpec(), javaRelease)
                .orElse(NO_GRAAL);
    }

    @Override
    public int afterSingle(BuildRun run, Path dir, int code) {
        if (code != 0) return code;
        if (aotCache) {
            // Extract the layout and run the training run client-side; the engine's exec plan names them.
            return AotCachePackage.run(dir, run.flags().cache());
        }
        // A cache the build just invalidated looks like an artifact and does nothing: drop it now.
        AotCachePackage.discardIfStale(dir);
        return code;
    }

    /** {@code jk build} for {@code in}, as another command runs it. */
    static int run(Invocation in) {
        return BuildVerbCommand.run(new PackageVerb(), in, true);
    }
}
