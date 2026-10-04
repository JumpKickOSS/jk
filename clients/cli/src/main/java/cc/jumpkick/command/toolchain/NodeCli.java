// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.run.JsonlShape;
import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.InstalledTool;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.node.NodeCatalog;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.NodeInstalls;
import cc.jumpkick.node.NodeRelease;
import cc.jumpkick.node.NodeSpec;
import cc.jumpkick.node.PackageManagerSpec;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.runtime.HostedEvents;
import cc.jumpkick.wire.transcript.JsonlEnvelope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * What the {@code jk node} verbs share beyond {@link NodeInstalls}: an engine-hosted install of what
 * is missing, running a command under a Node.js home, and the {@code --output json} rows.
 */
final class NodeCli {

    static final String WEDGE = "Node";

    private NodeCli() {}

    static NodeInstalls installs() {
        return new NodeInstalls();
    }

    /** The newest install satisfying {@code spec}, reading the catalog only when the spec needs it. */
    static Optional<NodeInstalls.Install> installed(NodeSpec spec) throws IOException {
        return installs().installed(spec, NodeInstalls.needsReleases(spec) ? releasesOrNone() : List.of());
    }

    /** The project's Node.js home with its package manager, installing what is missing. */
    static NodeHome ensure(NodeInstalls.ProjectNode project) throws IOException {
        NodeSpec lookup = project.lookup();
        if (lookup == null) throw new IOException("no Node.js declared for " + project.root());
        Optional<NodeInstalls.Install> found = installed(lookup);
        NodeInstalls.Install install =
                found.isPresent() ? found.get() : fromProvision(provision(BuildTool.NODE.slug(), project.wanted()));
        NodeHome home = new NodeHome(install.home(), install.version(), install.source(), null);
        String pm = project.packageManager();
        if (pm == null) return home;
        PackageManagerSpec spec = PackageManagerSpec.parse(pm);
        BuildTool tool = spec.manager().tool().orElse(null);
        if (tool == null) return home;
        Optional<InstalledTool> have = installs().managedTool(tool, spec.version());
        Path managerHome = have.isPresent()
                ? have.get().home()
                : Path.of(Objects.requireNonNull(
                        provision(tool.slug(), spec.version()).bin()));
        return home.withManager(new NodeHome.ManagerHome(spec.manager(), spec.version(), managerHome));
    }

    /**
     * The install {@code spec} names when given ({@code --node}), else the project's Node.js,
     * installing what is missing. Empty when neither names one.
     */
    static Optional<NodeHome> home(Path dir, @Nullable String spec) throws IOException {
        if (spec != null && !spec.isBlank()) {
            Optional<NodeInstalls.Install> found = installed(NodeSpec.parse(spec));
            NodeInstalls.Install i =
                    found.isPresent() ? found.get() : fromProvision(provision(BuildTool.NODE.slug(), spec));
            return Optional.of(new NodeHome(i.home(), i.version(), i.source(), null));
        }
        Optional<NodeInstalls.ProjectNode> project = NodeInstalls.project(dir);
        if (project.isEmpty()) return Optional.empty();
        return Optional.of(ensure(project.get()));
    }

    /** Install {@code slug} at {@code version} through the engine, the door {@code jk tool install} uses. */
    static HostedEvents.Provision provision(String slug, @Nullable String version) throws IOException {
        HostedEvents.Provision p = EngineClient.provisionTool(
                EnginePaths.current(),
                slug,
                version == null || version.isBlank() ? BuildTool.LATEST : version,
                JkDirs.tools(),
                false,
                false);
        if (p.error() != null) throw new IOException(p.error());
        if (p.bin() == null) throw new IOException(slug + " " + version + " did not install");
        return p;
    }

    private static NodeInstalls.Install fromProvision(HostedEvents.Provision p) {
        return new NodeInstalls.Install(
                Objects.requireNonNullElse(p.version(), ""), "jk", Path.of(Objects.requireNonNull(p.bin())));
    }

    /** The catalog, its warnings on stderr. */
    static NodeCatalog catalog() {
        return new NodeCatalog().onWarning(CliOutput::err);
    }

    /** The catalog's releases, or none when it cannot be read. */
    static List<NodeRelease> releasesOrNone() {
        try {
            return catalog().releases();
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /**
     * Run {@code argv} in {@code dir} with {@code home}'s shims and binaries first on {@code PATH},
     * the terminal handed over, and return its exit code. A bare program name is looked up there
     * first: the JVM resolves one against its own {@code PATH}, not the child's.
     */
    static int exec(NodeHome home, List<String> argv, Path dir) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(argv);
        command.set(0, resolve(home, argv.get(0)));
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile());
        pb.environment().put("PATH", home.path(System.getenv("PATH")));
        Process p = CliOutput.handOffTerminal(pb);
        CliOutput.skipTrailingBlank();
        return p.waitFor();
    }

    private static String resolve(NodeHome home, String program) {
        if (program.contains("/") || program.contains("\\")) return program;
        for (Path d : home.pathPrefix()) {
            for (String ext : Os.isWindows() ? List.of(".cmd", ".exe", "") : List.of("")) {
                Path candidate = d.resolve(program + ext);
                if (Files.isRegularFile(candidate)) return candidate.toString();
            }
        }
        return program;
    }

    /** One {@code --output json} row of {@code type}: the envelope, then what {@code fields} adds. */
    static void row(String type, Consumer<JsonFields> fields) {
        JsonFields line = JsonlEnvelope.open(Clock.SYSTEM.millis(), type);
        fields.accept(line);
        JsonlShape.emitEvent(line.finish(), true);
    }
}
