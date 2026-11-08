// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.engine.EngineClient;
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
import java.util.Optional;

/** {@code jk build}: lock, sync, compile, test and package. */
final class PackageVerb implements BuildVerb {

    private static final String AOT_CACHE = "aot-cache";

    private final Words words;
    private final boolean docs;
    private boolean aotCache;

    /** Client-resolved GraalVM home per always-native member; empty when the workspace links none. */
    private AlwaysNativeGraal.Graal graal = AlwaysNativeGraal.Graal.NONE;

    PackageVerb() {
        this("Build");
    }

    /** A build told under another name: the dev loop's {@code Watch}. */
    PackageVerb(String name) {
        this(new Words(name, "building", "build", "Building"), false);
    }

    private PackageVerb(Words words, boolean docs) {
        this.words = words;
        this.docs = docs;
    }

    /**
     * {@code jk javadoc}: the build, with the javadoc jar of every library that did not say
     * {@code javadoc = false}, and no test run. A plain build writes javadoc only where a module
     * opted in.
     */
    static PackageVerb javadoc() {
        return new PackageVerb(new Words("Javadoc", "documenting", "document", "Documenting"), true);
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
    public WorkspaceTarget target() {
        return WorkspaceTarget.PACKAGE;
    }

    @Override
    public List<Opt> ownOptions() {
        return List.of(Opt.flag("Package with JVM startup AOT cache", "-T", "--" + AOT_CACHE));
    }

    @Override
    public void parse(Invocation in, BuildRun run) {
        this.aotCache = in.isSet(AOT_CACHE);
        if (docs) run.flags(run.flags().withSkipTests());
    }

    @Override
    public int prepareWorkspace(BuildRun run, Path entryDir, BuildRun.Selection selection) {
        if (aotCache && !run.standalone()) {
            run.fail("--aot-cache packages one application; run it from the module directory");
            return Exit.USAGE;
        }
        // The GraalVM home for every always-native module this build reaches, before any progress UI.
        Optional<AlwaysNativeGraal.Graal> resolved = AlwaysNativeGraal.reached(run, entryDir, selection);
        if (resolved.isEmpty()) return Exit.FAILURE; // the resolver printed why
        this.graal = resolved.get();
        return -1;
    }

    @Override
    public WorkspaceResult workspace(BuildRun run, Path entryDir, List<String> modules, WorkspaceBuildListener listener)
            throws IOException {
        return EngineClient.buildWorkspace(
                EnginePaths.current(), run.request(entryDir, modules, graal.onto(WorkspaceSpec.DEFAULT)), listener);
    }

    @Override
    public WorkspaceRunView.Tails tails(BuildRun run, WorkspaceRunView view, long startNanos) {
        return new WorkspaceRunView.Tails(
                (r, planned) -> run.standalone()
                        ? BuildTails.projectTail(r.modules(), run.global().workingDir()) + " "
                                + BuildTails.elapsedSince(startNanos)
                        : BuildTails.successTail(r.modules(), planned, startNanos),
                r -> BuildTails.failureTail(WorkspaceRunView.failedSubject(r, "build"), startNanos));
    }

    @Override
    public int after(BuildRun run, int code) {
        if (code != 0 || !run.standalone()) return code;
        Path dir = run.global().workingDir();
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
