// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.protocol.PluginCommandReport;
import cc.jumpkick.host.Errors;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Variants;
import cc.jumpkick.plugin.manifest.VariantApply;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine-hosted plugin commands: fork the active code plugin over JSONL, stream {@code command-out},
 * and return its exit code. {@code found=false} leaves unknown-command handling to the client.
 */
public final class PluginCommands {

    private PluginCommands() {}

    public static PluginCommandReport run(Path dir, Path cache, String command, List<String> args) {
        return run(dir, cache, command, args, "", Map.of());
    }

    /**
     * As above with the request's variant selection — a deploy command after {@code jk run --release}
     * must resolve the release packaging (the AAB) and its config, not the debug default's.
     */
    public static PluginCommandReport run(
            Path dir, Path cache, String command, List<String> args, String variant, Map<String, String> clientEnv) {
        try {
            Path buildFile = dir.resolve(ManifestPaths.MANIFEST);
            if (!Files.isRegularFile(buildFile)) return PluginCommandReport.notFound();
            JkBuild project = JkBuildParser.parse(buildFile);
            if (!project.plugins().isEmpty() && PluginDescriptorOps.ensureMaterialized(dir, cache)) {
                project = JkBuildParser.reparse(buildFile);
            }
            project = VariantApply.applyLenient(project, dir, Variants.Selection.parse(variant), clientEnv)
                    .build();
            var active = PluginBuild.activeCodePlugin(project, dir).orElse(null);
            if (active == null) return PluginCommandReport.notFound();

            BuildLayout layout = BuildLayout.of(dir, project);
            PluginBuild.Declarations decls =
                    PluginBuild.declarations(active, project, dir, cache, layout.moduleTargetDir());
            if (decls.command(command) == null) return PluginCommandReport.notFound();

            Path scratch = layout.moduleTargetDir().resolve("plugin").resolve("command-" + command);
            Files.createDirectories(scratch);
            SpecWriter specWriter = new SpecWriter()
                    .op(PluginProtocol.OP_COMMAND, command, active.manifest().id())
                    .configValues(active.config().values())
                    .project(PluginBuild.facts(project, project.mainClass()))
                    .layout(layout.classesDir(), dir, scratch)
                    .artifact(PluginBuild.mainArtifactPath(layout, active))
                    // The project's pinned JDK, so `PluginCommandExec.tool("keytool")` forks the
                    // same JDK the build compiles with. Non-installing: a command must run before
                    // provisioning (`jk android licenses`), so this locates a pin or falls back to
                    // the running JVM — it never fetches one.
                    .javaHome(JavaHomes.resolveJavaHome(dir))
                    .commandArgs(args);
            // Commands get the step lane's tools (a bundletool the packager also reads) plus the
            // [[contribute.command-dependency]] lane (an adb no step reads) — both best-effort:
            // `jk android licenses` must run BEFORE licenses gate provisioning, so an
            // unprovisionable tool is absent and only a command that needs it complains.
            var cas = JkStores.storeCas();
            Map<String, String> sdkPins = PluginBuild.sdkPins(LockPaths.lockFile(dir));
            Map<String, Path> tools =
                    new LinkedHashMap<>(PluginBuild.fetchStepDependencies(project, dir, cas, sdkPins, true));
            tools.putAll(PluginBuild.fetchCommandDependencies(project, dir, cas, sdkPins, true));
            for (var tool : tools.entrySet()) {
                specWriter.extra(tool.getKey(), tool.getValue());
            }
            Path spec = specWriter.writeTempSpec();
            try {
                Path jar = PluginBuild.workerJarFor(active, cache);
                List<String> output = new ArrayList<>();
                String[] error = new String[1];
                PluginClient client = new PluginClient(active.manifest().code().protocolPrefix())
                        .on(PluginProtocol.COMMAND_OUT, line -> output.add(Jsonl.str(line, "line")))
                        .on("error", line -> error[0] = Jsonl.str(line, "message"))
                        .onOther(line -> {
                            // labels/done — not part of the command's user-facing output
                        });
                int exit = client.run(PluginLaunch.javaCommand(
                        jar, spec, active.manifest().code().protocolPrefix()));
                if (error[0] != null) return PluginCommandReport.error(error[0]);
                return new PluginCommandReport(null, true, exit, output);
            } finally {
                Files.deleteIfExists(spec);
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            return PluginCommandReport.error(Errors.text(e));
        }
    }
}
