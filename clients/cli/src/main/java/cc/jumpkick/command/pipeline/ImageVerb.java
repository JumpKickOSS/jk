// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk image}: the build, then an OCI image of exactly one module — pushed, loaded into a
 * daemon, or written as a tarball. A workspace member's prerequisites package first.
 */
final class ImageVerb implements BuildVerb {

    private static final Words WORDS = new Words("Image", "building", "build", "Building");

    private @Nullable String mainClass;
    private @Nullable String registry;
    private @Nullable String tag;
    private @Nullable String tarball;
    private @Nullable String dockerExecutable;

    /** The one module a workspace run images. */
    private @Nullable Path moduleDir;

    @Override
    public String name() {
        return "image";
    }

    @Override
    public String description() {
        return "Bundle this project into an OCI image";
    }

    @Override
    public Words words() {
        return WORDS;
    }

    @Override
    public BuildFlags.Reach reach() {
        return BuildFlags.Reach.DELIVER;
    }

    @Override
    public List<Opt> ownOptions() {
        return List.of(
                Opt.value("<class>", "Main class to set as the image entrypoint.", "-T", "--main"),
                Opt.value("<registry>", "Override image.registry from jk.toml.", "-U", "--registry"),
                Opt.value("<tag>", "Override image.tag from jk.toml.", "-W", "--tag"),
                Opt.value("<path>", "Write an OCI tarball instead of pushing.", "-Z", "--tarball")
                        .withFallback(""),
                Opt.value("<exe>", "Docker/Podman binary (default: auto)", "-X", "--docker-executable"));
    }

    @Override
    public void parse(Invocation in, BuildRun run) {
        this.mainClass = in.value("main").orElse(null);
        this.registry = in.value("registry").orElse(null);
        this.tag = in.value("tag").orElse(null);
        this.tarball = tarballPath(in);
        this.dockerExecutable = in.value("docker-executable").orElse(null);
    }

    /** {@code --tarball}, absolute against the invocation directory: the engine writes it, and its cwd is not ours. */
    static @Nullable String tarballPath(Invocation in) {
        return in.value("tarball").map(t -> CliPaths.abs(t).toString()).orElse(null);
    }

    @Override
    public int selected(BuildRun run, List<String> moduleDirs) {
        if (moduleDirs.size() != 1) {
            run.fail("an image is built for exactly one module — the selector matched " + moduleDirs.size());
            return Exit.USAGE;
        }
        this.moduleDir = Path.of(moduleDirs.getFirst());
        return -1;
    }

    @Override
    public int prepareWorkspace(BuildRun run, Path entryDir, BuildRun.Selection selection) {
        if (moduleDir == null) {
            run.fail("an image is built for exactly one module — pass -m <module> or run from its directory");
            return Exit.USAGE;
        }
        return -1;
    }

    @Override
    public WorkspaceResult workspace(BuildRun run, Path entryDir, List<String> modules, WorkspaceBuildListener listener)
            throws IOException {
        // The module's own directory: the engine expands the workspace cone around it.
        Path dir = moduleDir != null ? moduleDir : entryDir;
        return EngineClient.runImageWorkspace(EnginePaths.current(), request(run, dir), listener);
    }

    @Override
    public WorkspaceRunView.Tails tails(BuildRun run, WorkspaceRunView view, long startNanos) {
        return new WorkspaceRunView.Tails(
                (r, planned) -> {
                    ModuleOutcome.Image img = null;
                    for (var o : r.modules()) {
                        if (o.success() && o.image() != null) img = o.image();
                    }
                    String tail = img != null
                            ? imageSuccessTail(img.tarball(), img.name(), img.version(), img.daemonExe(), img.ref())
                            : "image built";
                    return tail + " " + BuildTails.elapsedSince(startNanos);
                },
                r -> "image failed " + BuildTails.elapsedSince(startNanos));
    }

    @Override
    public int single(BuildRun run, Path dir) {
        EngineRequests.ImageSummary[] summary = new EngineRequests.ImageSummary[1];
        @Nullable TestSummary[] tests = new TestSummary[1];
        // The success tail renders from the structured fields the terminal plan-finish carries,
        // populated before the console listener's own planFinish fires.
        ConsoleSpec spec = new ConsoleSpec(
                WORDS.name(),
                r -> summary[0] != null
                        ? imageSuccessTail(
                                summary[0].tarball(),
                                summary[0].name(),
                                summary[0].version(),
                                summary[0].daemonExe(),
                                summary[0].ref())
                        : "",
                r -> "Image build failed",
                true);
        String module = ProjectInfos.buildTarget(ManifestPaths.manifestIn(dir), dir);
        var request = request(run, dir);
        return run.settleSingle(spec, module, tests, listeners -> {
            var result = EngineClient.runImage(EnginePaths.current(), request, listeners, summary);
            tests[0] = summary[0] != null ? summary[0].testResult() : null;
            return result;
        });
    }

    private EngineRequests.ImageRequest request(BuildRun run, Path dir) {
        var session = SessionContext.current();
        BuildFlags flags = run.flags();
        return new EngineRequests.ImageRequest(
                dir,
                flags.cache(),
                flags.jdksDir(),
                mainClass,
                registry,
                tag,
                tarball,
                dockerExecutable,
                flags.skipTests(),
                session.offline(),
                session.force(),
                session.config().rebuildOr(false),
                run.global().verbose);
    }

    /**
     * The image wedge from the structured mode fields: {@code Wrote OCI tarball <path>}, {@code
     * Loaded OCI image <name>:<version> into <docker|podman>}, or {@code Pushed <ref>}. Theming
     * happens here, client-side; the engine supplies the plain values.
     */
    static String imageSuccessTail(
            @Nullable String tarball,
            @Nullable String name,
            @Nullable String version,
            @Nullable String daemonExe,
            @Nullable String ref) {
        if (tarball != null) {
            return "Wrote OCI tarball " + Theme.colorize(tarball, Theme.active().path());
        }
        if (daemonExe != null) {
            return "Loaded OCI image "
                    + Theme.colorize(name != null ? name : "", Coords.artifactStyle())
                    + ":"
                    + Theme.colorize(version != null ? version : "", Coords.versionStyle())
                    + " into "
                    + daemonExe;
        }
        return "Pushed " + Theme.colorize(ref != null ? ref : "", Theme.active().path());
    }
}
