// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.JsonlShape;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.GlobalCancel;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.HostTemp;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.MachineOutput;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk verify} — rebuild into a scratch copy and SHA-256-diff artifacts against {@code
 * target/}. Session is pinned to {@code rebuild} so the action cache cannot fake a hit; workspace
 * roots verify every module.
 */
public final class VerifyBuildCommand implements CliCommand {

    @Override
    public String name() {
        return "verify";
    }

    @Override
    public String description() {
        return "Rebuild from scratch and diff the artifacts vs target/";
    }

    @Override
    public List<Opt> options() {
        return List.of(CommonOpts.cacheDir());
    }

    private @Nullable Path cacheDir;

    /**
     * What parse-build decided to verify: each module dir whose artifacts get compared, with the
     * artifact paths of the user's build ({@link #artifactPaths}).
     */
    private record VerifyPlan(Map<Path, Path[]> existing) {}

    /** One artifact's hash comparison; a {@code null} hash means the file does not exist. */
    private record Comparison(
            String artifact,
            @Nullable String existingHash,
            @Nullable String rebuiltHash) {
        boolean match() {
            return existingHash != null && existingHash.equals(rebuiltHash);
        }
    }

    /** All per-artifact comparisons, in module order. */
    private record Report(List<Comparison> comparisons) {}

    private static final BuildPlanKey<VerifyPlan> PLAN = BuildPlanKey.scalar("verify-plan", VerifyPlan.class);
    private static final BuildPlanKey<Report> REPORT = BuildPlanKey.scalar("report", Report.class);

    @Override
    public MachineOutput machineOutput() {
        return MachineOutput.JSONL;
    }

    @Override
    public int run(Invocation in) throws IOException {
        this.cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        Path buildFile = ManifestPaths.manifestIn(dir);
        Path lockFile = LockPaths.lockFile(dir);
        if (!Files.exists(buildFile) || !Files.exists(lockFile)) {
            CommandWedge.printFail("Verify", "jk.toml and jk-lock.toml required in " + PathDisplay.styledRaw(dir));
            return Exit.CONFIG;
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        // The scratch copy is a whole project tree plus its build: gone on every way out, Ctrl-C
        // included, which halts without running this method's finally.
        Path scratch = Files.createTempDirectory(HostTemp.dir(), "jk-verify-");
        try (GlobalCancel.Registration onInterrupt =
                GlobalCancel.onInterrupt(() -> PathUtil.deleteRecursively(scratch))) {
            return verify(global, dir, cache, scratch);
        } finally {
            PathUtil.deleteRecursively(scratch);
        }
    }

    private int verify(GlobalOptions global, Path dir, Path cache, Path scratch) {
        Task parseBuild = Task.builder(TaskNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml + jk-lock.toml");
                    ProjectInfo root = projectInfo(dir);
                    Map<Path, Path[]> existing = new LinkedHashMap<>();
                    for (Path moduleDir : moduleDirs(dir, root)) {
                        existing.put(moduleDir, artifactPaths(moduleDir.equals(dir) ? root : projectInfo(moduleDir)));
                    }
                    // A build that never ran fails here, before the rebuild. Which modules package
                    // no jar at all (a sourceless root, a node module nothing depends on) only the
                    // rebuild tells, so a module missing its jar alone is judged after it.
                    List<Path> jars = new ArrayList<>();
                    for (Map.Entry<Path, Path[]> module : existing.entrySet()) {
                        if (root.workspaceRoot() && module.getKey().equals(dir)) continue;
                        jars.add(module.getValue()[0]);
                    }
                    if (!jars.isEmpty() && jars.stream().noneMatch(Files::exists)) {
                        missingJar(ctx::error, jars.getFirst());
                    }
                    ctx.put(PLAN, new VerifyPlan(existing));
                    ctx.progress(1);
                })
                .build();

        Task rebuild = Task.builder(TaskNames.REBUILD_SCRATCH)
                .kind(TaskKind.CPU)
                .requires(TaskNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("rebuild into scratch");
                    try {
                        copyProjectTree(dir, scratch);
                        touchLockfiles(scratch);
                        buildScratch(scratch, cache, ctx::error);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        ctx.error("build", String.valueOf(e.getMessage()));
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();

        Task compare = Task.builder(TaskNames.COMPARE_HASHES)
                .requires(TaskNames.REBUILD_SCRATCH)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("sha256 artifacts");
                    VerifyPlan plan = ctx.require(PLAN);
                    ctx.put(REPORT, new Report(compareArtifacts(dir, scratch, plan, ctx::error)));
                    ctx.progress(1);
                })
                .build();

        BuildPlan plan = BuildPlan.builder("verify-build")
                .stateKeys(PLAN, REPORT)
                .addTask(parseBuild)
                .addTask(rebuild)
                .addTask(compare)
                .build();
        BuildPlanResult result = BuildPlanConsole.run(plan, BuildPlanConsole.modeFor(global), cache);

        if (!result.success()) {
            for (BuildPlanResult.Diagnostic d : result.errors()) {
                if ("missing-jar".equals(d.code())) return Exit.NO_INPUT;
            }
            return 1;
        }

        Report report = plan.get(REPORT).orElseThrow();
        long mismatches = report.comparisons().stream().filter(c -> !c.match()).count();
        if (global.outputIsJson()) {
            for (Comparison c : report.comparisons()) {
                JsonlShape.row("verify-artifact", f -> f.string("artifact", c.artifact())
                        .bool("match", c.match())
                        .string("existing", c.existingHash())
                        .string("rebuilt", c.rebuiltHash()));
            }
        } else {
            for (Comparison c : report.comparisons()) {
                if (c.match()) {
                    CliOutput.out("ok        " + c.artifact() + "  " + c.existingHash());
                } else {
                    CliOutput.out("MISMATCH  " + c.artifact());
                    CliOutput.out("  existing: " + hashOrMissing(c.existingHash()));
                    CliOutput.out("  rebuilt : " + hashOrMissing(c.rebuiltHash()));
                }
            }
        }
        if (mismatches == 0) {
            if (!global.outputIsJson()) CommandWedge.printOk("Verify", "Reproducible");
            return 0;
        }
        CliOutput.err("not reproducible: " + mismatches + " artifact(s) differ");
        return 1;
    }

    // ---- scratch rebuild --------------------------------------------------

    /** A sink for build-failure diagnostics — matches {@code TaskContext.error}'s shape. */
    private interface ErrorSink {
        void error(String code, String message);
    }

    /**
     * Rebuild {@code scratch} via the resident engine with {@code rerun} pinned (never an
     * action-cache hit of the jars under comparison). Tests are skipped; production is always
     * engine-hosted over the wire.
     */
    private static void buildScratch(Path scratch, Path cache, ErrorSink errors) throws Exception {
        // The parse feeds only the in-process test path; the hosted engine re-parses entryDir
        // itself (the build request serializes entryDir, not the model — thin client).
        var request = new WorkspaceRequest(
                        scratch, cache, null, // jdksDir: default install root
                        1, // workers
                        null, // profile
                        true, // skipTests
                        false, // verbose
                        0, // module concurrency: auto
                        null, // dirtyHint: rerun marks everything dirty anyway
                        true, // only read by the in-process test path; the engine plans its own memory
                        false) // verify must rebuild against the pinned lock verbatim — never freshen it
                // Scratch-salted action keys can never recur: tasks must not persist
                // action-cache records or incremental state for this build.
                .withEphemeralActions(true);
        Session session = SessionContext.current()
                .withConfig(SessionContext.current().config().mergedWith(withRerun()))
                .withWorkingDir(scratch)
                .withCacheDir(cache);
        List<String> buildErrors = Collections.synchronizedList(new ArrayList<>());
        WorkspaceBuildListener listener = new WorkspaceBuildListener() {
            @Override
            public BuildPlanListener onModuleStart(ModulePlan m) {
                return new BuildPlanListener() {
                    @Override
                    public void error(String step, String code, String message) {
                        buildErrors.add(step + ": " + message);
                    }
                };
            }
        };
        WorkspaceResult result = SessionContext.where(
                session, () -> EngineClient.buildWorkspace(EnginePaths.current(), request, listener));
        if (!result.errors().isEmpty()) {
            errors.error("build", String.join("; ", result.errors()));
            throw new RuntimeException("scratch rebuild failed");
        }
        if (!result.success()) {
            String detail = buildErrors.isEmpty() ? "see build diagnostics" : String.join("; ", buildErrors);
            errors.error("build", "scratch rebuild failed: " + detail);
            throw new RuntimeException("scratch rebuild failed");
        }
    }

    /** A config layer that sets only {@code rerun} — laid over the invocation's config. */
    private static JkConfig withRerun() {
        // rebuild: bypass jk's caches, but never re-fetch (offline-safe)
        return JkConfig.empty().withRebuild(true);
    }

    // ---- module + artifact enumeration -------------------------------------

    /**
     * The directories whose artifacts get compared: the project itself for a plain project; for a
     * workspace root, the root (in case it carries its own sources) plus every declared module.
     */
    private static List<Path> moduleDirs(Path dir, ProjectInfo root) {
        if (!root.workspaceRoot()) return List.of(dir);
        List<Path> dirs = new ArrayList<>();
        dirs.add(dir);
        for (String module : root.moduleDirs()) {
            dirs.add(dir.resolve(module).normalize());
        }
        return dirs;
    }

    /** One module's jar-family artifact paths (main/assembly/sources/javadoc), engine-computed. */
    private static Path[] artifactPaths(ProjectInfo info) {
        return new Path[] {
            Path.of(info.mainJarPath()),
            Path.of(info.assemblyJarPath()),
            Path.of(info.sourcesJarPath()),
            Path.of(info.javadocJarPath()),
        };
    }

    /** The engine's project summary for {@code dir}; throws with the engine's message on error. */
    private static ProjectInfo projectInfo(Path dir) throws IOException {
        try {
            ProjectInfo info = EngineClient.projectInfo(EnginePaths.current(), dir);
            if (info.error() != null) throw new IOException(info.error());
            return info;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(String.valueOf(e.getMessage()));
        }
    }

    /**
     * Hash every jar-family artifact ({@code mainJar}/{@code assemblyJar}/{@code sourcesJar}/{@code
     * javadocJar}) that exists on either side, per module. Native binaries and OCI tars are out of
     * scope — they are not byte-comparable across builds the way the jar path guarantees.
     */
    private static List<Comparison> compareArtifacts(Path dir, Path scratch, VerifyPlan plan, ErrorSink errors)
            throws IOException {
        List<Comparison> comparisons = new ArrayList<>();
        for (Map.Entry<Path, Path[]> module : plan.existing().entrySet()) {
            Path[] existing = module.getValue();
            Path[] rebuilt = artifactPaths(projectInfo(scratch.resolve(dir.relativize(module.getKey()))));
            if (unbuilt(existing[0], rebuilt[0])) missingJar(errors, existing[0]);
            comparisons.addAll(compareModule(dir, existing, rebuilt));
        }
        return comparisons;
    }

    /**
     * A module the rebuild packages a jar for that the user's build has not: built since the last
     * {@code jk build}, or never. A module neither side packages a jar for has none to compare.
     */
    static boolean unbuilt(Path existingJar, Path rebuiltJar) {
        return !Files.exists(existingJar) && Files.exists(rebuiltJar);
    }

    private static void missingJar(ErrorSink errors, Path jar) {
        errors.error("missing-jar", "no existing jar at " + jar + " — run `jk build` first.");
        throw new RuntimeException("missing existing jar");
    }

    /** Pair up one module's artifacts by kind; include a pair when either side produced the file. */
    private static List<Comparison> compareModule(Path displayRoot, Path[] existing, Path[] rebuilt)
            throws IOException {
        List<Comparison> out = new ArrayList<>();
        Path[][] pairs = {
            {existing[0], rebuilt[0]},
            {existing[1], rebuilt[1]},
            {existing[2], rebuilt[2]},
            {existing[3], rebuilt[3]},
        };
        for (Path[] pair : pairs) {
            String existingHash = hashIfPresent(pair[0]);
            String rebuiltHash = hashIfPresent(pair[1]);
            if (existingHash == null && rebuiltHash == null) continue;
            out.add(new Comparison(displayPath(displayRoot, pair[0]), existingHash, rebuiltHash));
        }
        return out;
    }

    private static @Nullable String hashIfPresent(Path file) throws IOException {
        // Streamed (64 KiB buffer) — a large artifact never lands in the CLI's small heap.
        return Files.isRegularFile(file) ? Hashing.sha256Hex(file) : null;
    }

    private static String hashOrMissing(@Nullable String hash) {
        return hash == null ? "(missing)" : hash;
    }

    private static String displayPath(Path root, Path artifact) {
        try {
            return root.relativize(artifact).toString().replace(File.separatorChar, '/');
        } catch (RuntimeException e) {
            return artifact.getFileName().toString();
        }
    }

    // ---- scratch checkout ---------------------------------------------------

    /**
     * Copy the project tree into the scratch root, excluding {@code .git} and every module's {@code
     * target/} output tree (a directory named {@code target} whose parent holds a {@code jk.toml}).
     * Attributes (mtimes) are preserved so the copied
     * {@code jk.toml}↔{@code jk-lock.toml} freshness relationship survives; {@link #touchLockfiles}
     * then bumps the locks regardless.
     */
    static void copyProjectTree(Path srcRoot, Path destRoot) throws IOException {
        PathUtil.copyTree(srcRoot, destRoot, VerifyBuildCommand::skip, PathUtil.Copy.PRESERVE_ATTRIBUTES);
    }

    /**
     * True for directories the scratch copy must not carry: VCS metadata and build outputs, the code
     * graph's under {@code target/graph/} among them. A scratch tree that carried a graph would start
     * a refresh after its build (and the compile job a refresh may submit) in a directory verify
     * deletes when it is done.
     */
    private static boolean skip(Path d) {
        String name = d.getFileName() == null ? "" : d.getFileName().toString();
        if (name.equals(".git")) return true;
        return name.equals(BuildLayout.TARGET)
                && d.getParent() != null
                && Files.exists(ManifestPaths.manifestIn(d.getParent()));
    }

    /**
     * Bump every copied {@code jk-lock.toml} to now (later than any copied manifest's preserved mtime) so
     * the scratch build can never consider a lock stale and re-resolve — verify reuses the existing
     * lock verbatim.
     */
    private static void touchLockfiles(Path root) throws IOException {
        FileTime now = FileTime.fromMillis(Clock.SYSTEM.millis());
        PathUtil.forEachRegularFile(root, (p, attrs) -> {
            if (ManifestPaths.LOCK.equals(p.getFileName().toString())) Files.setLastModifiedTime(p, now);
        });
    }
}
