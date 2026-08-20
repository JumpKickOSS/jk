// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compat.ProjectImport;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compat-bridge drivers: {@code jk import} conversion and {@code jk mvn}/{@code jk gradle}
 * distribution provisioning. Import streams notes via {@link NoteObserver}; exit code is a result
 * on {@link #EXIT}, not a plan failure. Tool exec stays client-side.
 */
public final class CompatPlans {

    private CompatPlans() {}

    /** Receives each import progress note ({@code kind} = {@code wrote}/{@code note}) as the plugin streams it. */
    public interface NoteObserver {
        void onNote(String kind, String text);
    }

    /** The import plugin's exit code (0 = converted cleanly). */
    public static final BuildPlanKey<Integer> EXIT = BuildPlanKey.of("import-exit", Integer.class);

    /** The import plugin's reported issue count (rendered as "Import notes: N issue(s)"). */
    public static final BuildPlanKey<Integer> WARNINGS = BuildPlanKey.of("import-warnings", Integer.class);

    /** The import plugin's terminal error text, if any. */
    public static final BuildPlanKey<String> ERROR = BuildPlanKey.of("import-error", String.class);

    /** The plugin's passthrough chatter, kept only when it exited non-zero. */
    public static final BuildPlanKey<String> DIAG = BuildPlanKey.of("import-diag", String.class);

    /**
     * Build the import plan. All paths arrive absolute (the command pre-flighted source detection
     * and overwrite checks); {@code report} may be {@code null}. Conversion runs in-process so
     * {@code [[import.gradle-plugin]]} rules come from the engine registry, not a worker catalog.
     */
    public static BuildPlan importBuildPlan(
            Path source,
            Path out,
            Path baseDir,
            Path tmpDir,
            boolean force,
            Path report,
            Path cache,
            NoteObserver observer) {
        Task convert = Task.builder("import")
                .kind(TaskKind.IO)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("convert " + source.getFileName());
                    ProjectImport.Outcome outcome = ProjectImport.run(
                            source.toAbsolutePath(),
                            out.toAbsolutePath(),
                            baseDir == null ? null : baseDir.toAbsolutePath(),
                            tmpDir == null ? null : tmpDir.toAbsolutePath(),
                            force,
                            report == null ? null : report.toAbsolutePath());
                    for (Path wrote : outcome.wrote()) observer.onNote("wrote", wrote.toString());
                    if (outcome.error() != null && outcome.exit() != 0) ctx.put(ERROR, outcome.error());
                    ctx.put(WARNINGS, outcome.warnings());
                    ctx.put(EXIT, outcome.exit());
                    if (outcome.exit() != 0 && outcome.error() != null) {
                        ctx.put(DIAG, outcome.error());
                    }
                    ctx.progress(1);
                })
                .build();

        return BuildPlan.builder("import").addTask(convert).build();
    }

    /** A provisioning call's outcome — the flat fields {@code jk mvn}/{@code jk gradle} render from. */
    public record Provision(String bin, String version, String source, String error, int exit, String diag) {}

    /**
     * Provision a Maven/Gradle distribution via the compat-bridge plugin: link a discovered install
     * or download one, and return its launcher path. Non-interactive by construction — the exec of
     * the provisioned tool is the caller's business.
     */
    public static Provision provision(Path cache, Path projectDir, Path toolsRoot, boolean noDiscover, boolean isGradle)
            throws IOException, InterruptedException {
        Path workerJar = PluginJar.COMPAT_BRIDGE.locate(JkStores.cas(cache));
        Path spec = Files.createTempFile("jk-compat-", ".spec");
        try {
            Files.write(
                    spec,
                    new SpecWriter()
                            .op(
                                    PluginProtocol.OP_COMMAND,
                                    isGradle ? "provision_gradle" : "provision_mvn",
                                    "jk-compat-bridge")
                            .configString(
                                    "projectDir", projectDir.toAbsolutePath().toString())
                            .configString(
                                    "toolsRoot", toolsRoot.toAbsolutePath().toString())
                            .configBool("noDiscover", noDiscover)
                            .lines(),
                    StandardCharsets.UTF_8);

            String[] bin = {null};
            String[] version = {null};
            String[] source = {null};
            String[] error = {null};
            StringBuilder diag = new StringBuilder();
            int exit = new PluginClient("##JKCMP:")
                    .on(PluginProtocol.RESULT, json -> {
                        bin[0] = Jsonl.str(json, "bin");
                        version[0] = Jsonl.str(json, "version");
                        source[0] = Jsonl.str(json, "source");
                    })
                    .on(PluginProtocol.ERROR, json -> error[0] = Jsonl.str(json, PluginProtocol.MESSAGE))
                    .passthrough(ln -> diag.append(ln).append('\n'))
                    .run(PluginLaunch.javaCommand(workerJar, spec));
            return new Provision(
                    bin[0],
                    version[0],
                    source[0],
                    error[0],
                    exit,
                    exit != 0 && diag.length() > 0 ? diag.toString().trim() : null);
        } finally {
            Files.deleteIfExists(spec);
        }
    }
}
