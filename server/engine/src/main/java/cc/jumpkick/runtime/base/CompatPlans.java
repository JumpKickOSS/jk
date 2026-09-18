// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.BuildToolDistributions;
import cc.jumpkick.compat.ProjectImport;
import cc.jumpkick.compat.ToolDistribution;
import cc.jumpkick.compat.ToolProvisioning;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.gradle.GradleBuildImport;
import cc.jumpkick.gradle.GradleResolver;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.mvn.MavenResolver;
import cc.jumpkick.mvn.PomImporter;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

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
    public static final BuildPlanKey<Integer> EXIT = BuildPlanKey.scalar("import-exit", Integer.class);

    /** The importer's reported issue count (rendered as "Import notes: N issue(s)"). */
    public static final BuildPlanKey<Integer> WARNINGS = BuildPlanKey.scalar("import-warnings", Integer.class);

    /** The importer's terminal error text, kept only when it exited non-zero. */
    public static final BuildPlanKey<String> ERROR = BuildPlanKey.scalar("import-error", String.class);

    /**
     * Build the import plan. All paths arrive absolute (the command pre-flighted source detection
     * and overwrite checks); {@code report} may be {@code null}; {@code poms} resolves the parents a
     * POM inherits and {@code gradle} reads a Gradle build, its stages streamed as {@code note}
     * lines. Conversion runs in-process so {@code [[import.gradle-plugin]]} rules come from the
     * engine registry, not a worker catalog; only Gradle's own evaluation runs in a fork.
     */
    public static BuildPlan importBuildPlan(
            PomImporter poms,
            GradleBuildImport gradle,
            Path source,
            Path out,
            Path baseDir,
            Path tmpDir,
            boolean force,
            @Nullable Path report,
            NoteObserver observer) {
        Task convert = Task.builder("import")
                .kind(TaskKind.IO)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("convert " + source.getFileName());
                    ProjectImport.Outcome outcome = ProjectImport.run(
                            poms,
                            gradle,
                            source.toAbsolutePath(),
                            out.toAbsolutePath(),
                            baseDir == null ? null : baseDir.toAbsolutePath(),
                            tmpDir == null ? null : tmpDir.toAbsolutePath(),
                            force,
                            report == null ? null : report.toAbsolutePath(),
                            note -> observer.onNote("note", note));
                    for (Path wrote : outcome.wrote()) observer.onNote("wrote", wrote.toString());
                    if (outcome.error() != null && outcome.exit() != 0) ctx.put(ERROR, outcome.error());
                    ctx.put(WARNINGS, outcome.warnings());
                    ctx.put(EXIT, outcome.exit());
                    ctx.progress(1);
                })
                .build();

        return BuildPlan.builder("import")
                .stateKeys(EXIT, WARNINGS, ERROR)
                .addTask(convert)
                .build();
    }

    /**
     * A provisioning call's outcome — the flat fields {@code jk mvn}/{@code jk gradle} render from;
     * {@code verification} says what vouched for a downloaded archive.
     */
    public record Provision(
            @Nullable String bin,
            @Nullable String version,
            @Nullable String source,
            @Nullable String verification,
            @Nullable String error,
            int exit) {}

    /**
     * Provision a Maven/Gradle distribution: link a discovered install or download one, and return
     * its launcher path. Runs in the engine JVM, the same way {@link CompileToolchain#resolveKotlinHome}
     * provisions Kotlin — there is no worker to fork and no worker repo to materialize.
     * Non-interactive by construction: the exec of the provisioned tool is the caller's business,
     * and {@code acceptUnverified} is the one consent it carries.
     */
    public static Provision provision(
            Path projectDir, Path toolsRoot, boolean noDiscover, boolean acceptUnverified, boolean isGradle) {
        ToolDistribution dist = null;
        try {
            dist = isGradle
                    ? new GradleResolver().resolve(projectDir.toAbsolutePath())
                    : new MavenResolver().resolve(projectDir.toAbsolutePath());
            ToolProvisioning.Result result = ToolProvisioning.provision(
                    dist,
                    new ToolRegistry(toolsRoot.toAbsolutePath()),
                    new Http(),
                    new ToolProvisioning.Policy(noDiscover, false, acceptUnverified));
            return new Provision(
                    result.tool().binary().toString(),
                    dist.version(),
                    result.source().name(),
                    result.verification(),
                    null,
                    Exit.SUCCESS);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Provision(null, dist == null ? null : dist.version(), null, null, message(e), Exit.FAILURE);
        }
    }

    /**
     * Provision a named build tool at a named version — {@code jk tool install kotlin:latest} —
     * rather than the one a project's wrapper asks for.
     *
     * <p>Through the same {@link ToolProvisioning} door {@link #provision} and {@code
     * CompileToolchain.resolveKotlinHome} use, so an ahead-of-time install is a cache hit for the
     * build that later needs it rather than a second copy under a second layout.
     */
    public static Provision provisionTool(
            String toolSlug, @Nullable String version, Path toolsRoot, boolean noDiscover) {
        ToolDistribution dist = null;
        try {
            BuildTool tool = BuildTool.bySlug(toolSlug)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown build tool '" + toolSlug + "' — known: " + BuildTool.slugs()));
            dist = BuildToolDistributions.of(tool, version);
            ToolProvisioning.Result result = ToolProvisioning.provision(
                    dist,
                    new ToolRegistry(toolsRoot.toAbsolutePath()),
                    new Http(),
                    new ToolProvisioning.Policy(noDiscover, false, false));
            return new Provision(
                    result.tool().home().toString(),
                    dist.version(),
                    result.source().name(),
                    result.verification(),
                    null,
                    Exit.SUCCESS);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Provision(null, dist == null ? null : dist.version(), null, null, message(e), Exit.FAILURE);
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }
}
