// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.compat;

import cc.jumpkick.compat.InstalledTool;
import cc.jumpkick.compat.ProjectImport;
import cc.jumpkick.compat.ToolDistribution;
import cc.jumpkick.compat.ToolProvisioning;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.gradle.GradleResolver;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.mvn.MavenResolver;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The {@code jk-compat-bridge} plugin (op {@code command}, name {@code import}/{@code provision_mvn}/
 * {@code provision_gradle}): Maven/Gradle import + tool provisioning, isolated in a forked plugin JVM
 * so provisioning never loads in the engine JVM. Import conversion runs in the engine. Speaks JSONL
 * config spec, {@code wrote}/{@code result} replies + {@code error} on failure. Exit codes are
 * {@link Exit}: 0 success, 1 operation error, {@link Exit#USAGE} bad command line or unrecognised
 * source, {@link Exit#NO_INPUT} unreadable spec, {@link Exit#DATA_ERR} spec missing a key,
 * {@link Exit#CANT_CREATE} overwrite-without-force.
 */
public final class CompatBridge implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-compat-bridge", "##JKCMP:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) {
        if (args.isEmpty()) {
            System.err.println("jk-compat-bridge: expected spec file path");
            return Exit.USAGE;
        }
        Path specFile = Path.of(args.get(0));
        if (!Files.isRegularFile(specFile)) {
            System.err.println("jk-compat-bridge: spec file not found: " + specFile);
            return Exit.NO_INPUT;
        }
        PluginSpec spec;
        try {
            spec = PluginSpec.read(specFile);
        } catch (IOException e) {
            System.err.println("jk-compat-bridge: could not read spec: " + e.getMessage());
            return Exit.NO_INPUT;
        }
        String command = spec.name().orElse("");
        PluginConfig config = spec.config();
        return switch (command) {
            case "import" -> runImport(out, config);
            case "provision_mvn" -> runProvision(out, config, false);
            case "provision_gradle" -> runProvision(out, config, true);
            default -> {
                System.err.println("jk-compat-bridge: unknown command: " + command);
                yield Exit.USAGE;
            }
        };
    }

    private static int runImport(ProtocolWriter out, PluginConfig c) {
        Path source = c.stringOpt("source").map(Path::of).orElse(null);
        Path outPath = c.stringOpt("out").map(Path::of).orElse(null);
        Path report = c.stringOpt("report").map(Path::of).orElse(null);
        Path tmpDir = c.stringOpt("tmpDir").map(Path::of).orElse(null);
        Path baseDir = c.stringOpt("baseDir").map(Path::of).orElse(null);
        boolean force = c.bool("force", false);
        var outcome = ProjectImport.run(source, outPath, baseDir, tmpDir, force, report);
        for (Path wrote : outcome.wrote()) out.emit(PluginReply.wrote(wrote.toString()));
        if (outcome.exit() != 0) {
            if (outcome.error() != null) {
                out.emit(PluginReply.error("import", outcome.error()));
            }
            return outcome.exit();
        }
        out.emit(PluginReply.result(Map.of("warnings", outcome.warnings())));
        return 0;
    }

    private static int runProvision(ProtocolWriter out, PluginConfig c, boolean isGradle) {
        Path projectDir = c.stringOpt("projectDir").map(Path::of).orElse(null);
        Path toolsRoot = c.stringOpt("toolsRoot").map(Path::of).orElse(null);
        boolean noDiscover = c.bool("noDiscover", false);
        if (projectDir == null || toolsRoot == null) {
            System.err.println("jk-compat-bridge: provision requires projectDir and toolsRoot");
            return Exit.DATA_ERR;
        }
        try {
            ToolRegistry registry = new ToolRegistry(toolsRoot);
            ToolDistribution dist =
                    isGradle ? new GradleResolver().resolve(projectDir) : new MavenResolver().resolve(projectDir);
            ToolProvisioning.Result result = ToolProvisioning.provision(dist, registry, new Http(), noDiscover);
            InstalledTool tool = result.tool();
            out.emit(PluginReply.result(Map.of(
                    "bin", tool.binary().toString(),
                    "version", dist.version(),
                    "source", result.source().name())));
            return 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            out.emit(PluginReply.error("provision", e.getMessage()));
            return 1;
        }
    }
}
