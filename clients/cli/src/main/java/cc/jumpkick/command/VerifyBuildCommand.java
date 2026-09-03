// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CliPaths;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.util.JkDirs;
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
import java.util.List;

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

    private Path cacheDir;
    private GlobalOptions global;

    /** What parse-build decided to verify: the module dirs whose artifacts get compared. */
    private record VerifyPlan(List<Path> moduleDirs) {}

    /** One artifact's hash comparison; a {@code null} hash means the file does not exist. */
    private record Comparison(String artifact, String existingHash, String rebuiltHash) {
        boolean match() {
            return existingHash != null && existingHash.equals(rebuiltHash);
        }
    }

    /** All per-artifact comparisons, in module order. */
    private record Report(List<Comparison> comparisons) {}

    private static final BuildPlanKey<VerifyPlan> PLAN = BuildPlanKey.scalar("verify-plan", VerifyPlan.class);
    private static final BuildPlanKey<Path> SCRATCH = BuildPlanKey.scalar("scratch", Path.class);
    private static final BuildPlanKey<Report> REPORT = BuildPlanKey.scalar("report", Report.class);

    @Override
    public int run(Invocation in) throws IOException {
        this.cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        this.global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        Path buildFile = dir.resolve(ManifestPaths.MANIFEST);
        Path lockFile = LockPaths.lockFile(dir);
        if (!Files.exists(buildFile) || !Files.exists(lockFile)) {
            CommandWedge.printFail("Verify", "jk.toml and jk-lock.toml required in " + PathDisplay.styledRaw(dir));
            return Exit.CONFIG;
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        Task parseBuild = Task.builder(TaskNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml + jk-lock.toml");
                    ProjectInfo root = projectInfo(dir);
                    List<Path> moduleDirs = moduleDirs(dir, root);
                    // Every module the user built must have its jar in place before we rebuild
                    // same "run `jk build` first" gate the single-jar verify always had. The
                    // workspace root itself is exempt (a pure aggregator produces no jar).
                    for (Path moduleDir : moduleDirs) {
                        if (root.workspaceRoot() && moduleDir.equals(dir)) continue;
                        Path jar = Path.of(projectInfo(moduleDir).mainJarPath());
                        if (!Files.exists(jar)) {
                            ctx.error("missing-jar", "no existing jar at " + jar + " — run `jk build` first.");
                            throw new RuntimeException("missing existing jar");
                        }
                    }
                    ctx.put(PLAN, new VerifyPlan(moduleDirs));
                    ctx.put(SCRATCH, Files.createTempDirectory("jk-verify-"));
                    ctx.progress(1);
                })
                .build();

        Task rebuild = Task.builder(TaskNames.REBUILD_SCRATCH)
                .kind(TaskKind.CPU)
                .requires(TaskNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("rebuild into scratch");
                    Path scratch = ctx.require(SCRATCH);
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
                    Path scratch = ctx.require(SCRATCH);
                    ctx.put(REPORT, new Report(compareArtifacts(dir, scratch, plan)));
                    ctx.progress(1);
                })
                .build();

        BuildPlan plan = BuildPlan.builder("verify-build")
                .stateKeys(PLAN, SCRATCH, REPORT)
                .addTask(parseBuild)
                .addTask(rebuild)
                .addTask(compare)
                .build();
        BuildPlanResult result = BuildPlanConsole.run(plan, BuildPlanConsole.modeFor(global), cache);
        plan.get(SCRATCH).ifPresent(PathUtil::deleteRecursively);

        if (!result.success()) {
            for (BuildPlanResult.Diagnostic d : result.errors()) {
                if ("missing-jar".equals(d.code())) return Exit.NO_INPUT;
            }
            return 1;
        }

        Report report = plan.get(REPORT).orElseThrow();
        long mismatches = report.comparisons().stream().filter(c -> !c.match()).count();
        if (!global.outputIsJson()) {
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
        CliOutput.err("Not reproducible — " + mismatches + " artifact(s) differ.");
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
    private static Path[] artifactPaths(Path moduleDir) throws IOException {
        ProjectInfo info = projectInfo(moduleDir);
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
    private static List<Comparison> compareArtifacts(Path dir, Path scratch, VerifyPlan plan) throws IOException {
        List<Comparison> comparisons = new ArrayList<>();
        for (Path moduleDir : plan.moduleDirs()) {
            Path[] existing = artifactPaths(moduleDir);
            Path scratchModule = scratch.resolve(dir.relativize(moduleDir));
            Path[] rebuilt = artifactPaths(scratchModule);
            comparisons.addAll(compareModule(dir, existing, rebuilt));
        }
        return comparisons;
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

    private static String hashIfPresent(Path file) throws IOException {
        // Streamed (64 KiB buffer) — a large artifact never lands in the CLI's small heap.
        return Files.isRegularFile(file) ? Hashing.sha256Hex(file) : null;
    }

    private static String hashOrMissing(String hash) {
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
     * Attributes (mtimes) are preserved so the copied {@code jk.toml}↔{@code jk-lock.toml} freshness
     * relationship survives; {@link #touchLockfiles} then bumps the locks regardless.
     */
    private static void copyProjectTree(Path srcRoot, Path destRoot) throws IOException {
        PathUtil.copyTree(srcRoot, destRoot, VerifyBuildCommand::skip, PathUtil.Copy.PRESERVE_ATTRIBUTES);
    }

    /** True for directories the scratch copy must not carry: VCS metadata and build outputs. */
    private static boolean skip(Path d) {
        String name = d.getFileName() == null ? "" : d.getFileName().toString();
        if (name.equals(".git")) return true;
        return name.equals(BuildLayout.TARGET)
                && d.getParent() != null
                && Files.exists(d.getParent().resolve(ManifestPaths.MANIFEST));
    }

    /**
     * Bump every copied {@code jk-lock.toml} to now (later than any copied manifest's preserved mtime) so
     * the scratch build can never consider a lock stale and re-resolve — verify reuses the existing
     * lock verbatim.
     */
    private static void touchLockfiles(Path root) throws IOException {
        FileTime now = FileTime.fromMillis(System.currentTimeMillis());
        try (var stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.getFileName() != null
                        && ManifestPaths.LOCK.equals(p.getFileName().toString())) {
                    Files.setLastModifiedTime(p, now);
                }
            }
        }
    }
}
