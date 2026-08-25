// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compat.ProjectImport;
import cc.jumpkick.compat.ToolDistribution;
import cc.jumpkick.compat.ToolProvisioning;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.gradle.GradleResolver;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.mvn.MavenResolver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Foreign-ecosystem drivers: {@code jk import} conversion and {@code jk mvn}/{@code jk gradle}
 * distribution provisioning, both in the engine JVM. Import streams notes via {@link NoteObserver};
 * exit code is a result on {@link #EXIT}, not a plan failure. Tool exec stays client-side.
 */
public final class CompatPlans {

    private CompatPlans() {}

    /** Receives each import progress note ({@code kind} = {@code wrote}/{@code note}) as the importer emits it. */
    public interface NoteObserver {
        void onNote(String kind, String text);
    }

    /** The importer's exit code (0 = converted cleanly). */
    public static final BuildPlanKey<Integer> EXIT = BuildPlanKey.of("import-exit", Integer.class);

    /** The importer's reported issue count (rendered as "Import notes: N issue(s)"). */
    public static final BuildPlanKey<Integer> WARNINGS = BuildPlanKey.of("import-warnings", Integer.class);

    /** The importer's terminal error text, if any. */
    public static final BuildPlanKey<String> ERROR = BuildPlanKey.of("import-error", String.class);

    /** The importer's diagnostic detail, kept only when it exited non-zero. */
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
    public record Provision(String bin, String version, String source, String error, int exit) {}

    /**
     * Provision a Maven/Gradle distribution: link a discovered install or download one, and return
     * its launcher path. Runs in the engine JVM, the same way {@link CompileToolchain#resolveKotlinHome}
     * provisions Kotlin — there is no worker to fork and no worker repo to materialize.
     * Non-interactive by construction: the exec of the provisioned tool is the caller's business.
     */
    public static Provision provision(Path projectDir, Path toolsRoot, boolean noDiscover, boolean isGradle) {
        ToolDistribution dist = null;
        try {
            dist = isGradle
                    ? new GradleResolver().resolve(projectDir.toAbsolutePath())
                    : new MavenResolver().resolve(projectDir.toAbsolutePath());
            ToolProvisioning.Result result = ToolProvisioning.provision(
                    dist, new ToolRegistry(toolsRoot.toAbsolutePath()), new Http(), noDiscover);
            return new Provision(
                    result.tool().binary().toString(),
                    dist.version(),
                    result.source().name(),
                    null,
                    Exit.SUCCESS);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Provision(null, dist == null ? null : dist.version(), null, message(e), Exit.FAILURE);
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }
}
