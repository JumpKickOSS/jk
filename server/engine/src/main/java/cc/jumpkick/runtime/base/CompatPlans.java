// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.BuildToolDistributions;
import cc.jumpkick.compat.ImportProgress;
import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.ProjectImport;
import cc.jumpkick.compat.ToolDistribution;
import cc.jumpkick.compat.ToolProgress;
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
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Foreign-ecosystem drivers: {@code jk import} conversion and {@code jk mvn}/{@code jk gradle}
 * distribution provisioning, both in the engine JVM. Import streams notes via {@link NoteObserver};
 * exit code is a result on {@link #EXIT}, not a plan failure. Tool exec stays client-side.
 */
public final class CompatPlans {

    private CompatPlans() {}

    /**
     * Receives each import note as the importer emits it: {@code kind} is {@code note} (a progress
     * line), {@code wrote} (a file written for the user), {@code issue} (one row of the import
     * report) or {@code report} (where the report was written).
     */
    public interface NoteObserver {
        void onNote(String kind, String text);
    }

    /** The importer's exit code (0 = converted cleanly). */
    public static final BuildPlanKey<Integer> EXIT = BuildPlanKey.scalar("import-exit", Integer.class);

    /** The importer's terminal error text, kept only when it exited non-zero. */
    public static final BuildPlanKey<String> ERROR = BuildPlanKey.scalar("import-error", String.class);

    private static final BuildPlanKey<ProjectImport.Converted> CONVERTED =
            BuildPlanKey.scalar("import-converted", ProjectImport.Converted.class);

    private static final BuildPlanKey<ProjectImport.Written> WRITTEN =
            BuildPlanKey.scalar("import-written", ProjectImport.Written.class);

    private static final BuildPlanKey<ImportReport> CHECKED = BuildPlanKey.scalar("import-checked", ImportReport.class);

    /** The import's steps, in the order they run. */
    public static final String READ = "import-read";

    public static final String WRITE = "import-write";
    public static final String CHECK_PINS = "import-check-pins";
    public static final String REPORT = "import-report";

    /**
     * Build the import plan: read the build (Gradle evaluates it in a fork; a POM import reads the
     * reactor and its parents), write the manifests, check the written pins against their
     * dependencies (a Maven or scanned build; Gradle's pins are the versions it resolved), and write
     * the report. Each step reports its own units and detail. All paths arrive absolute (the command
     * pre-flighted source detection and overwrite checks); {@code report} may be {@code null};
     * {@code gradle} reads a Gradle build, drawing its distribution's download on the step it is
     * given; {@code raise} lifts the written pins a dependency needs higher. Conversion runs
     * in-process so {@code [[import.gradle-plugin]]} rules come from the engine registry; only
     * Gradle's own evaluation runs in a fork. A stage that ends the import puts {@link #EXIT} and
     * {@link #ERROR}, and the steps after it do nothing.
     */
    public static BuildPlan importBuildPlan(
            PomImporter poms,
            Function<TaskContext, GradleBuildImport> gradle,
            Path source,
            Path out,
            Path baseDir,
            Path tmpDir,
            boolean force,
            boolean dryRun,
            @Nullable Path report,
            ProjectImport.PinRaise raise,
            NoteObserver observer) {
        Path src = source.toAbsolutePath();
        Path target = out.toAbsolutePath();
        boolean isGradle = GradleBuildImport.isGradleSource(src.getFileName().toString());
        Task read = Task.builder(READ)
                .label(isGradle ? "Evaluate with Gradle" : "Read POMs")
                .stage(BuildStage.OTHER)
                .kind(TaskKind.IO)
                .weight(60)
                .ticks(1)
                .execute(ctx -> {
                    ProjectImport.Staged<ProjectImport.Converted> staged =
                            ProjectImport.read(poms, gradle.apply(ctx), src, target, progress(ctx, observer));
                    if (!stopped(ctx, staged.stop())) ctx.put(CONVERTED, Objects.requireNonNull(staged.value()));
                    ctx.progress(1);
                })
                .build();
        Task write = Task.builder(WRITE)
                .label(dryRun ? "Plan manifests" : "Write manifests")
                .stage(BuildStage.OTHER)
                .kind(TaskKind.IO)
                .requires(READ)
                .weight(5)
                .ticks(1)
                .execute(ctx -> {
                    ProjectImport.Converted converted = ctx.get(CONVERTED).orElse(null);
                    if (converted != null) {
                        ProjectImport.Staged<ProjectImport.Written> staged = ProjectImport.write(
                                converted,
                                src,
                                target,
                                baseDir == null ? null : baseDir.toAbsolutePath(),
                                force,
                                dryRun,
                                progress(ctx, observer));
                        if (!stopped(ctx, staged.stop())) ctx.put(WRITTEN, Objects.requireNonNull(staged.value()));
                    }
                    ctx.progress(1);
                })
                .build();
        Task check = Task.builder(CHECK_PINS)
                .label("Check pins")
                .stage(BuildStage.OTHER)
                .kind(TaskKind.IO)
                .requires(WRITE)
                .weight(35)
                .ticks(1)
                .execute(ctx -> {
                    ProjectImport.Written written = ctx.get(WRITTEN).orElse(null);
                    if (written != null) {
                        if (ProjectImport.needsPinCheck(written, dryRun)) {
                            ctx.put(CHECKED, ProjectImport.checkPins(written, target, raise, progress(ctx, observer)));
                        } else {
                            ctx.label(dryRun ? "dry run: nothing written to check" : "Gradle resolved every pin");
                            ctx.cached();
                            ctx.put(CHECKED, written.report());
                        }
                    }
                    ctx.progress(1);
                })
                .build();
        Task finish = Task.builder(REPORT)
                .label("Report")
                .stage(BuildStage.OTHER)
                .kind(TaskKind.IO)
                .requires(CHECK_PINS)
                .weight(1)
                .ticks(1)
                .execute(ctx -> {
                    ProjectImport.Written written = ctx.get(WRITTEN).orElse(null);
                    if (written != null) {
                        ProjectImport.Outcome outcome = ProjectImport.finish(
                                written,
                                ctx.get(CHECKED).orElse(written.report()),
                                src,
                                tmpDir == null ? null : tmpDir.toAbsolutePath(),
                                report == null ? null : report.toAbsolutePath());
                        for (Path wrote : outcome.wrote()) observer.onNote("wrote", wrote.toString());
                        for (String issue : outcome.issues()) observer.onNote("issue", issue);
                        if (outcome.report() != null)
                            observer.onNote("report", outcome.report().toString());
                        if (!stopped(ctx, outcome.exit() == 0 ? null : outcome)) ctx.put(EXIT, 0);
                    }
                    ctx.progress(1);
                })
                .build();

        return BuildPlan.builder("import")
                .stateKeys(EXIT, ERROR, CONVERTED, WRITTEN, CHECKED)
                .addTask(read)
                .addTask(write)
                .addTask(check)
                .addTask(finish)
                .build();
    }

    /** Record {@code stop} as the import's exit and error; false (and nothing recorded) when it is null. */
    private static boolean stopped(TaskContext ctx, ProjectImport.@Nullable Outcome stop) {
        if (stop == null) return false;
        ctx.put(EXIT, stop.exit());
        if (stop.error() != null && stop.exit() != 0) ctx.put(ERROR, stop.error());
        return true;
    }

    /**
     * An import stage's progress on its plan step: milestones are {@code note} lines for a transcript
     * and the step's label, details the label alone, units the step's ticks.
     */
    static ImportProgress progress(TaskContext ctx, NoteObserver observer) {
        return new ImportProgress() {
            @Override
            public void milestone(String line) {
                observer.onNote("note", line);
                ctx.label(line);
            }

            @Override
            public void detail(String text) {
                ctx.label(text);
            }

            @Override
            public void grow(int units) {
                if (units > 0) ctx.updateTicks(units);
            }

            @Override
            public void advance(int units) {
                if (units > 0) ctx.progress(units);
            }
        };
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
            Path projectDir,
            Path toolsRoot,
            boolean noDiscover,
            boolean acceptUnverified,
            boolean isGradle,
            ToolProgress progress) {
        ToolDistribution dist = null;
        try {
            dist = isGradle
                    ? new GradleResolver().resolve(projectDir.toAbsolutePath())
                    : new MavenResolver().resolve(projectDir.toAbsolutePath());
            ToolProvisioning.Result result = ToolProvisioning.provision(
                    dist,
                    new ToolRegistry(toolsRoot.toAbsolutePath()),
                    new Http(),
                    new ToolProvisioning.Policy(noDiscover, false, acceptUnverified),
                    progress);
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
     * build that later needs it rather than a second copy under a second layout. {@code
     * acceptUnverified} is the same consent {@link #provision} carries: a distribution no checksum
     * vouches for installs and its digest is recorded.
     */
    public static Provision provisionTool(
            String toolSlug,
            @Nullable String version,
            Path toolsRoot,
            boolean noDiscover,
            boolean acceptUnverified,
            ToolProgress progress) {
        ToolDistribution dist = null;
        try {
            BuildTool tool = BuildTool.bySlug(toolSlug)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown build tool '" + toolSlug + "'; known: " + BuildTool.slugs()));
            dist = BuildToolDistributions.of(tool, version);
            ToolProvisioning.Result result = ToolProvisioning.provision(
                    dist,
                    new ToolRegistry(toolsRoot.toAbsolutePath()),
                    new Http(),
                    new ToolProvisioning.Policy(noDiscover, false, acceptUnverified),
                    progress);
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
