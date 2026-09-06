// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.ide;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.engine.EngineCancel;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.IdeWireModel;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Engine-backed session for IDE hosts (IntelliJ / VS Code plugins and agents). Prefer this over
 * shelling out to the {@code jk} CLI when you need structured progress and project model data.
 *
 * <p><b>Integration sequence</b> (see also {@code docs/architecture.md}):
 *
 * <ol>
 * <li>{@link #open(Path)} — project root containing {@code jk.toml}
 * <li>{@link #connect} — ensure a live, version-matched engine (no-op under test in-process)
 * <li>{@link #projectInfo} — group/name/modules without parsing TOML in the IDE process
 * <li>{@link #sync(ProgressListener)} — dependency materialization with progress callbacks
 * <li>{@link #ideModel} — classpath / source roots for generators or in-IDE classpaths
 * <li>{@link #build(BuildListener)} — optional full build with per-module/step events
 * </ol>
 *
 * <p>File generation ({@code jk ide}) remains a separate offline/export path; this class does not
 * write {@code .iml} / {@code .vscode} files. The engine stays out-of-process.
 */
public class IdeEngineClient {

    private final Path projectDir;
    private final Path cacheDir;
    private final @Nullable Path jdksDir;

    /** Package-visible for BSP/IDE tests that stub engine calls. */
    IdeEngineClient(Path projectDir, Path cacheDir, @Nullable Path jdksDir) {
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.cacheDir = cacheDir.toAbsolutePath().normalize();
        this.jdksDir = jdksDir == null ? null : jdksDir.toAbsolutePath().normalize();
    }

    /** Open a session on {@code projectDir} using the default jk cache. */
    public static IdeEngineClient open(Path projectDir) throws IOException {
        return open(projectDir, JkDirs.cache(), null);
    }

    /** Open a session with an explicit cache (and optional JDK install root for tests). */
    public static IdeEngineClient open(Path projectDir, Path cacheDir, @Nullable Path jdksDir) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir");
        Objects.requireNonNull(cacheDir, "cacheDir");
        if (!Files.isRegularFile(projectDir.resolve(ManifestPaths.MANIFEST))) {
            throw new IOException("no jk.toml in " + projectDir);
        }
        return new IdeEngineClient(projectDir, cacheDir, jdksDir);
    }

    public Path projectDir() {
        return projectDir;
    }

    public Path cacheDir() {
        return cacheDir;
    }

    /** Ensure a live engine (spawn/replace on version skew). */
    public EngineProbe.Handshake connect() throws IOException {
        return EngineClient.ensureRunning(EnginePaths.current(), Jk.VERSION);
    }

    /** Engine status (heap, active requests, …). */
    public EngineProbe.Status status() throws IOException {

        return EngineProbe.status(EnginePaths.activeSocket(EnginePaths.current()))
                .orElseThrow(() -> new IOException("engine not reachable — call connect() first"));
    }

    /**
     * Project summary for the open directory (workspace root and module list when applicable).
     * Prefer this over parsing {@code jk.toml} in the IDE process.
     */
    public ProjectInfo projectInfo() throws IOException {

        return EngineClient.projectInfo(EnginePaths.current(), projectDir);
    }

    /**
     * IDE-agnostic workspace model (classpath jars, source/classes roots, modules). Used by
     * generators and by IDE plugins that want classpath truth from the engine.
     */
    public IdeWireModel ideModel() throws IOException {

        return EngineClient.ideModel(EnginePaths.current(), projectDir, cacheDir, jdksDir);
    }

    /**
     * Run dependency sync against the workspace root (or this project if not in a workspace).
     * Progress is reported through {@code listener} — no CLI stdout parsing required.
     */
    public SyncOutcome sync(ProgressListener listener) throws IOException {
        ProgressListener progress = listener == null ? ProgressListener.NOOP : listener;
        Path syncRoot = resolveSyncRoot();
        long[] fetched = new long[1];
        long[] upToDate = new long[1];
        var session = SessionContext.current();
        List<String> errors = new ArrayList<>();
        boolean success;
        BuildPlanResult result = EngineClient.runSync(
                EnginePaths.current(),
                new EngineRequests.SyncRequest(
                        syncRoot,
                        cacheDir,
                        jdksDir,
                        null,
                        false,
                        session.offline(),
                        session.force(),
                        false,
                        session.config().verboseOr(false)),
                steps -> progressListener(progress, steps),
                fetched,
                upToDate);
        success = result.success();
        for (var d : result.errors()) errors.add(d.message());

        return new SyncOutcome(success, fetched[0], upToDate[0], List.copyOf(errors));
    }

    /**
     * Run a build with structured module/step events for the IDE progress UI. Non-workspace
     * projects use a single-module build; workspace roots use the workspace cascade.
     */
    public BuildOutcome build(@Nullable BuildListener listener) throws IOException {
        BuildListener progress = listener == null ? BuildListener.NOOP : listener;

        ProjectInfo info = projectInfo();
        if (info.error() != null && !info.error().isBlank()) {
            return new BuildOutcome(false, 0, 0, List.of(info.error()));
        }
        List<String> errors = new ArrayList<>();
        int[] modules = {0};
        int[] failed = {0};
        if (info.workspaceRoot()) {
            WorkspaceRequest req =
                    new WorkspaceRequest(projectDir, cacheDir, jdksDir, 1, null, false, false, 0, null, true, true);
            WorkspaceBuildListener wbl = new WorkspaceBuildListener() {
                @Override
                public void onPlan(List<ModulePlan> plan) {
                    modules[0] = plan.size();
                    progress.onPlan(plan.size());
                }

                @Override
                public BuildPlanListener onModuleStart(ModulePlan module) {
                    progress.onModuleStart(module.coord(), module.dir());
                    List<Task> steps =
                            module.plan() == null ? List.of() : module.plan().steps();
                    return progressListener(progress, steps);
                }

                @Override
                public void onModuleFinish(ModuleOutcome outcome) {
                    if (!outcome.success()) failed[0]++;
                    progress.onModuleFinish(outcome.coord(), outcome.success());
                }

                @Override
                public void onWorkspaceFinish(WorkspaceResult result) {
                    if (result.errors() != null) errors.addAll(result.errors());
                }
            };
            WorkspaceResult ws = EngineClient.buildWorkspace(EnginePaths.current(), req, wbl);
            return new BuildOutcome(ws.success(), modules[0], failed[0], List.copyOf(errors));
        }
        // Single-module projects: surface the same module boundary callbacks workspaces get.
        String coord =
                info.coord() != null && !info.coord().isBlank() ? info.coord() : info.group() + ":" + info.name();
        progress.onModuleStart(coord, projectDir);
        BuildPlanResult r = EngineClient.runSingleBuild(
                EnginePaths.current(),
                new EngineRequests.SingleBuildRequest(
                        projectDir, cacheDir, jdksDir, 1, null, false, false, false, false),
                steps -> progressListener(progress, steps),
                null,
                null);
        for (var d : r.errors()) errors.add(d.message());
        progress.onModuleFinish(coord, r.success());
        return new BuildOutcome(r.success(), 1, r.success() ? 0 : 1, List.copyOf(errors), warningMessages(r));
    }

    /**
     * Build a single module directory. When {@code moduleDir} is null, same as
     * {@link #build(BuildListener)}. Workspace roots still cascade when {@code moduleDir} is null.
     */
    public BuildOutcome buildModule(@Nullable Path moduleDir, @Nullable BuildListener listener) throws IOException {
        if (moduleDir == null) return build(listener);
        BuildListener progress = listener == null ? BuildListener.NOOP : listener;
        Path mod = moduleDir.toAbsolutePath().normalize();
        String coord = mod.getFileName() != null ? mod.getFileName().toString() : mod.toString();
        progress.onModuleStart(coord, mod);
        List<String> errors = new ArrayList<>();
        BuildPlanResult r = EngineClient.runSingleBuild(
                EnginePaths.current(),
                new EngineRequests.SingleBuildRequest(mod, cacheDir, jdksDir, 1, null, false, false, false, false),
                steps -> progressListener(progress, steps),
                null,
                null);
        for (var d : r.errors()) errors.add(d.message());
        progress.onModuleFinish(coord, r.success());
        return new BuildOutcome(r.success(), 1, r.success() ? 0 : 1, List.copyOf(errors), warningMessages(r));
    }

    /**
     * Run the test plan for a module BSP {@code buildTarget/test}). When
     * {@code moduleDir} is null on a workspace root, cascades every module (mirrors {@link
     * #build(BuildListener)}). When null on a single project, tests that project. Uses the same
     * engine path as {@code jk test}.
     */
    public BuildOutcome testModule(@Nullable Path moduleDir, @Nullable BuildListener listener) throws IOException {
        return testModule(moduleDir, listener, null);
    }

    /**
     * Run tests with optional suite/tag selection BSP {@code data} / CLI TestSelection).
     * {@code selection} null → session default (usually suite {@code test} only).
     */
    public BuildOutcome testModule(
            @Nullable Path moduleDir, @Nullable BuildListener listener, @Nullable TestSelection selection)
            throws IOException {
        BuildListener progress = listener == null ? BuildListener.NOOP : listener;
        if (moduleDir == null) {
            ProjectInfo info = projectInfo();
            if (info.error() != null && !info.error().isBlank()) {
                return new BuildOutcome(false, 0, 0, List.of(info.error()));
            }
            if (info.workspaceRoot()) {
                return testWorkspace(progress, selection);
            }
        }
        Path mod = moduleDir == null ? projectDir : moduleDir.toAbsolutePath().normalize();
        return testOneModule(mod, progress, selection);
    }

    /** Sequential per-module {@code jk test} for a workspace root. */
    private BuildOutcome testWorkspace(BuildListener progress, @Nullable TestSelection selection) throws IOException {
        IdeWireModel model = ideModel();
        List<String> dirs = model != null && model.moduleDirs() != null ? model.moduleDirs() : List.of();
        if (dirs.isEmpty()) {
            // No module list — fall back to testing the workspace root directory alone.
            return testOneModule(projectDir, progress, selection);
        }
        int modules = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String d : dirs) {
            if (d == null || d.isBlank()) continue;
            Path mod = Path.of(d);
            modules++;
            BuildOutcome o = testOneModule(mod, progress, selection);
            if (!o.success()) {
                failed++;
                if (o.errors() != null) errors.addAll(o.errors());
            }
            if (o.warnings() != null) warnings.addAll(o.warnings());
        }
        return new BuildOutcome(failed == 0, modules, failed, List.copyOf(errors), List.copyOf(warnings));
    }

    private BuildOutcome testOneModule(Path mod, BuildListener progress, @Nullable TestSelection selection)
            throws IOException {
        String coord = mod.getFileName() != null ? mod.getFileName().toString() : mod.toString();
        progress.onModuleStart(coord, mod);
        List<String> errors = new ArrayList<>();
        TestSummary[] testOut = new TestSummary[1];
        var session = SessionContext.current();
        var sel = selection != null ? selection : session.testSelection();
        BuildPlanResult r = EngineClient.runTest(
                EnginePaths.current(),
                new EngineRequests.TestRequest(
                        mod,
                        cacheDir,
                        jdksDir,
                        1,
                        null,
                        false,
                        session.offline(),
                        session.force(),
                        session.parallelTests(),
                        sel),
                steps -> progressListener(progress, steps),
                testOut);
        for (var d : r.errors()) errors.add(d.message());
        if (!r.success() && testOut[0] != null && !testOut[0].allPassed() && errors.isEmpty()) {
            errors.add("tests failed: " + testOut[0].failed() + " failed / " + testOut[0].total() + " total");
        }
        progress.onModuleFinish(coord, r.success());
        return new BuildOutcome(r.success(), 1, r.success() ? 0 : 1, List.copyOf(errors), warningMessages(r));
    }

    /**
     * BSP {@code buildTarget/run}: build the module, then execute the engine exec plan (same path as
     * {@code jk run}). Blocks until the process exits. {@code moduleDir} null → project root.
     */
    public BuildOutcome runModule(@Nullable Path moduleDir, @Nullable BuildListener listener, Consumer<String> onOutput)
            throws IOException {
        BuildListener progress = listener == null ? BuildListener.NOOP : listener;
        Path mod = moduleDir == null ? projectDir : moduleDir.toAbsolutePath().normalize();
        BuildOutcome built = buildModule(mod, progress);
        if (!built.success()) return built;

        String coord = mod.getFileName() != null ? mod.getFileName().toString() : mod.toString();
        progress.onModuleStart(coord + " (run)", mod);
        try {
            var plan = EngineClient.execPlan(EnginePaths.current(), mod, cacheDir, "run", null, null);
            if (plan.error() != null && !plan.error().isBlank()) {
                progress.onModuleFinish(coord + " (run)", false);
                return new BuildOutcome(false, 1, 1, List.of(plan.error()));
            }
            List<String> argv = plan.argv();
            if (argv == null || argv.isEmpty()) {
                progress.onModuleFinish(coord + " (run)", false);
                return new BuildOutcome(false, 1, 1, List.of("exec plan has empty argv"));
            }
            Path cwd = plan.workingDir() != null && !plan.workingDir().isBlank() ? Path.of(plan.workingDir()) : mod;
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(cwd.toFile());
            // Never inheritIO: under `jk bsp serve` the parent's stdout IS the JSON-RPC frame
            // stream and its stdin carries pending requests — an inherited child would interleave
            // raw program output into the protocol and could eat frames. Merge the app's stderr
            // into stdout and hand each line to the caller's sink; close the child's stdin so a
            // read sees EOF instead of stealing ours.
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close();
            try (var reader = p.inputReader()) {
                for (String line; (line = reader.readLine()) != null; ) {
                    onOutput.accept(line);
                }
            }
            int code = p.waitFor();
            boolean ok = code == 0;
            progress.onModuleFinish(coord + " (run)", ok);
            return new BuildOutcome(ok, 1, ok ? 0 : 1, ok ? List.of() : List.of("run exited with code " + code));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            progress.onModuleFinish(coord + " (run)", false);
            return new BuildOutcome(false, 1, 1, List.of("run interrupted"));
        } catch (IOException e) {
            progress.onModuleFinish(coord + " (run)", false);
            throw e;
        }
    }

    /**
     * Best-effort cancel of engine jobs under this project (and optional module dir). Used by BSP
     * {@code build/cancel}. Does not spawn an engine solely to cancel.
     */
    public void cancel(@Nullable Path moduleDir) {
        Path dir = moduleDir != null ? moduleDir.toAbsolutePath().normalize() : projectDir;
        try {
            EngineCancel.cancelForDir(EnginePaths.current(), dir.toString());
        } catch (IOException ignored) {
            // best-effort
        }
        try {
            if (!dir.equals(projectDir)) {
                EngineCancel.cancelForDir(EnginePaths.current(), projectDir.toString());
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private Path resolveSyncRoot() {
        try {
            ProjectInfo info = projectInfo();
            if (info.error() == null
                    && info.workspaceRootDir() != null
                    && !info.workspaceRootDir().isEmpty()) {
                return Path.of(info.workspaceRootDir());
            }
        } catch (Exception ignored) {
            // fall through
        }
        return projectDir;
    }

    private static BuildPlanListener progressListener(ProgressListener progress, List<Task> steps) {
        return new BuildPlanListener() {
            @Override
            public void planStart(BuildPlanView view) {
                int n = steps == null ? 0 : steps.size();
                progress.onPlan(n);
            }

            @Override
            public void stepStart(String step, @Nullable String group, int ticks) {
                progress.onStepStart(step, group == null ? "" : group);
            }

            @Override
            public void progress(String step, int delta, BuildPlanView view) {
                long done = view == null ? delta : view.numerator();
                long total = view == null ? 0 : view.denominator();
                progress.onStepProgress(step, done, total);
            }

            @Override
            public void output(String step, String line) {
                progress.onOutput(step, line);
            }

            @Override
            public void stepFinish(
                    String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                boolean ok = status == TaskStatus.SUCCESS || status == TaskStatus.SKIPPED;
                progress.onStepFinish(step, ok, status == null ? "" : status.name());
            }
        };
    }

    // --- callbacks / outcomes -----------------------------------------------------------------

    /** Sync / step progress for IDE progress bars. */
    public interface ProgressListener {
        ProgressListener NOOP = new ProgressListener() {};

        default void onPlan(int stepCount) {}

        default void onStepStart(String step, String phase) {}

        default void onStepProgress(String step, long done, long total) {}

        default void onOutput(String step, String line) {}

        default void onStepFinish(String step, boolean success, String status) {}
    }

    /** Build progress including module boundaries. */
    public interface BuildListener extends ProgressListener {
        BuildListener NOOP = new BuildListener() {};

        default void onModuleStart(String coord, Path dir) {}

        default void onModuleFinish(String coord, boolean success) {}
    }

    /** {@code BuildPlanResult} keeps warnings as a second list; dropping it hid every compiler warning from the IDE. */
    private static List<String> warningMessages(BuildPlanResult r) {
        List<String> warnings = new ArrayList<>();
        for (var d : r.warnings()) warnings.add(d.message());
        return List.copyOf(warnings);
    }

    public record SyncOutcome(boolean success, long fetched, long upToDate, List<String> errors) {}

    public record BuildOutcome(
            boolean success, int modules, int failedModules, List<String> errors, List<String> warnings) {

        /** Error-only outcome (setup failures, runs): no compiler warnings to carry. */
        public BuildOutcome(boolean success, int modules, int failedModules, List<String> errors) {
            this(success, modules, failedModules, errors, List.of());
        }
    }
}
