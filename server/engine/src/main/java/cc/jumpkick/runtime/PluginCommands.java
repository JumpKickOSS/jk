// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.plugin.PluginClient;
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
import cc.jumpkick.runtime.base.PluginDescriptorOps;
import cc.jumpkick.runtime.base.PluginLaunch;
import cc.jumpkick.wire.protocol.PluginCommandReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Engine-hosted plugin commands: fork the active code plugin over JSONL, stream {@code command-out},
 * and return its exit code. {@code found=false} leaves unknown-command handling to the client.
 */
public final class PluginCommands {

    private PluginCommands() {}

    public static PluginCommandReport run(Path dir, Path cache, @Nullable String command, List<String> args) {
        return run(dir, cache, command, args, "", Map.of());
    }

    /**
     * As above with the request's variant selection — a deploy command after {@code jk run --release}
     * must resolve the release packaging (the AAB) and its config, not the debug default's.
     */
    public static PluginCommandReport run(
            Path dir,
            Path cache,
            @Nullable String command,
            List<String> args,
            String variant,
            Map<String, String> clientEnv) {
        try {
            Path buildFile = dir.resolve(ManifestPaths.MANIFEST);
            if (!Files.isRegularFile(buildFile)) return PluginCommandReport.notFound();
            JkBuild project = JkBuildParser.parse(buildFile);
            if (!project.plugins().isEmpty() && PluginDescriptorOps.ensureMaterialized(dir, cache)) {
                project = JkBuildParser.reparse(buildFile);
            }
            project = VariantApply.applyLenient(project, dir, Variants.Selection.parse(variant), clientEnv)
                    .build();
            BuildLayout layout = BuildLayout.of(dir, project);
            ActivePlugins.Declared plugins = ActivePlugins.declared(project, dir, cache, layout.moduleTargetDir());
            if (plugins == null) return PluginCommandReport.notFound();
            PluginBuild.CommandDecl declared = plugins.decls().command(command);
            if (declared == null) return PluginCommandReport.notFound();
            PluginBuild.Active active = plugins.commandOwners().get(declared.name());
            if (active == null) return PluginCommandReport.notFound();

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
            Path lockFile = LockPaths.lockFile(dir);
            Map<String, Path> tools =
                    new LinkedHashMap<>(PluginBuild.fetchStepDependencies(project, dir, cas, lockFile, true));
            tools.putAll(PluginBuild.fetchCommandDependencies(project, dir, cas, lockFile, true));
            for (var tool : tools.entrySet()) {
                specWriter.extra(tool.getKey(), tool.getValue());
            }
            Path spec = specWriter.writeTempSpec();
            try {
                Path jar = PluginBuild.workerJarFor(active, cache);
                List<String> output = new ArrayList<>();
                @Nullable String[] error = new String[1];
                PluginClient client = new PluginClient(PluginBuild.code(active).protocolPrefix())
                        .on(PluginProtocol.COMMAND_OUT, line -> output.add(Jsonl.str(line, "line")))
                        .on("error", line -> error[0] = Jsonl.str(line, "message"))
                        .onOther(line -> {
                            // labels/done — not part of the command's user-facing output
                        });
                int exit = client.run(PluginLaunch.javaCommand(
                        jar, spec, PluginBuild.code(active).protocolPrefix()));
                String failure = error[0];
                if (failure != null) return PluginCommandReport.error(failure);
                return new PluginCommandReport(null, true, exit, output);
            } finally {
                Files.deleteIfExists(spec);
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            return PluginCommandReport.error(Errors.text(e));
        }
    }
}
