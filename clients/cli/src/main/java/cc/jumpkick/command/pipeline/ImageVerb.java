// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Coords;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk image}: the build, then an OCI image of exactly one module, loaded into a daemon or
 * written as a tarball. It never pushes: {@code jk publish} does. A workspace member's
 * prerequisites package first.
 */
final class ImageVerb implements BuildVerb {

    private static final Words WORDS = new Words("Image", "building", "build", "Building");

    private @Nullable String mainClass;
    private @Nullable String tag;
    private @Nullable String tarball;
    private @Nullable String dockerExecutable;
    private @Nullable WorkspaceResult result;
    private NativeGraal.Graal graal = NativeGraal.Graal.NONE;

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
    public WorkspaceTarget target() {
        return WorkspaceTarget.IMAGE;
    }

    @Override
    public List<Opt> ownOptions() {
        return List.of(
                Opt.value("<class>", "Main class to set as the image entrypoint.", "-T", "--main"),
                Opt.value("<tag>", "Override image.tag from jk.toml.", "-W", "--tag"),
                Opt.value("<path>", "Write an OCI tarball instead of loading the daemon.", "-Z", "--tarball")
                        .withFallback(""),
                Opt.value("<exe>", "Docker/Podman binary (default: auto)", "-X", "--docker-executable"));
    }

    @Override
    public void parse(Invocation in, BuildRun run) {
        this.mainClass = in.value("main").orElse(null);
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
            run.fail("an image is built for one module; the selector matched " + moduleDirs.size());
            return Exit.USAGE;
        }
        this.moduleDir = Path.of(moduleDirs.getFirst());
        return -1;
    }

    @Override
    public int prepareWorkspace(BuildRun run, Path entryDir, BuildRun.Selection selection) {
        if (run.standalone()) moduleDir = entryDir;
        if (moduleDir == null) {
            run.fail("an image is built for one module; pass -m <module> or run from its directory");
            return Exit.USAGE;
        }
        // A native image links the module's executable; its GraalVM is the terminal's to pick.
        Optional<NativeGraal.Graal> resolved = NativeGraal.reached(run, entryDir, selection, false, true);
        if (resolved.isEmpty()) return Exit.FAILURE; // the resolver printed why
        this.graal = resolved.get();
        return -1;
    }

    @Override
    public WorkspaceResult workspace(BuildRun run, Path entryDir, List<String> modules, WorkspaceBuildListener listener)
            throws IOException {
        WorkspaceResult r = EngineClient.buildWorkspace(
                EnginePaths.current(),
                run.request(
                        entryDir,
                        modules,
                        graal.onto(WorkspaceSpec.image(Set.of(), mainClass, tag, tarball, dockerExecutable))),
                listener);
        this.result = r;
        return r;
    }

    /** With an {@code [image] registry} set, one line naming what {@code jk publish} pushes. */
    @Override
    public int after(BuildRun run, int code) {
        WorkspaceResult r = result;
        if (r == null || code != 0) return code;
        for (ModuleOutcome o : r.modules()) {
            String line = o.success() && o.image() != null ? publishLine(o.image()) : null;
            if (line != null) CliOutput.out(line);
        }
        return code;
    }

    /** {@code jk publish pushes <ref>}, or {@code null} when no registry is configured. */
    static @Nullable String publishLine(ModuleOutcome.Image image) {
        return image.publishRef() == null ? null : "jk publish pushes " + image.publishRef();
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
                            ? imageSuccessTail(img.tarball(), img.name(), img.version(), img.daemonExe())
                            : "image built";
                    return tail + " " + BuildTails.elapsedSince(startNanos);
                },
                r -> "image failed " + BuildTails.elapsedSince(startNanos));
    }

    /**
     * The image wedge from the structured mode fields: {@code Wrote OCI tarball <path>} or {@code
     * Loaded OCI image <name>:<version> into <docker|podman>}. Theming happens here, client-side;
     * the engine supplies the plain values.
     */
    static String imageSuccessTail(
            @Nullable String tarball, @Nullable String name, @Nullable String version, @Nullable String daemonExe) {
        if (tarball != null) {
            return "Wrote OCI tarball " + Theme.colorize(tarball, Theme.active().path());
        }
        return "Loaded OCI image "
                + Theme.colorize(name != null ? name : "", Coords.artifactStyle())
                + ":"
                + Theme.colorize(version != null ? version : "", Coords.versionStyle())
                + " into "
                + (daemonExe != null ? daemonExe : "docker");
    }
}
