// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TestSummary;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Request/outcome records for engine-hosted verbs. */
public final class EngineRequests {
    private EngineRequests() {}

    /** Everything an engine-hosted {@code jk test} run needs — mirrors {@code TestCommand}'s own local fields. */
    public record TestRequest(
            Path entryDir,
            Path cache,
            Path jdksDir,
            int workers,
            String profile,
            boolean verbose,
            boolean offline,
            boolean force,
            boolean parallelTests,
            TestSelection testSelection) {
        /** Backward-compatible ctor: serial cross-module gate, default suite. */
        public TestRequest(
                Path entryDir,
                Path cache,
                Path jdksDir,
                int workers,
                String profile,
                boolean verbose,
                boolean offline,
                boolean force) {
            this(entryDir, cache, jdksDir, workers, profile, verbose, offline, force, false, TestSelection.DEFAULT);
        }

        public TestRequest(
                Path entryDir,
                Path cache,
                Path jdksDir,
                int workers,
                String profile,
                boolean verbose,
                boolean offline,
                boolean force,
                boolean parallelTests) {
            this(
                    entryDir,
                    cache,
                    jdksDir,
                    workers,
                    profile,
                    verbose,
                    offline,
                    force,
                    parallelTests,
                    TestSelection.DEFAULT);
        }
    }

    /** Everything an engine-hosted single-project {@code jk build} needs — mirrors {@code BuildCommand}'s local fields. */
    public record SingleBuildRequest(
            Path entryDir,
            Path cache,
            Path jdksDir,
            int workers,
            String profile,
            boolean skipTests,
            boolean verbose,
            boolean offline,
            boolean force,
            String variant,
            Map<String, String> clientEnv) {

        /** Back-compat: default variant, no client env. */
        public SingleBuildRequest(
                Path entryDir,
                Path cache,
                Path jdksDir,
                int workers,
                String profile,
                boolean skipTests,
                boolean verbose,
                boolean offline,
                boolean force) {
            this(entryDir, cache, jdksDir, workers, profile, skipTests, verbose, offline, force, "", Map.of());
        }
    }

    public record ExplainRequest(
            Path entryDir,
            Path cache,
            int workers,
            boolean skipTests,
            String profile,
            Path jdksDir,
            boolean serial,
            boolean parallelTests,
            boolean verbose,
            boolean rebuild,
            int maxModuleConcurrency) {
        /** Backward-compatible ctor (rebuild=false, maxModuleConcurrency from serial). */
        public ExplainRequest(
                Path entryDir,
                Path cache,
                int workers,
                boolean skipTests,
                String profile,
                Path jdksDir,
                boolean serial,
                boolean parallelTests,
                boolean verbose) {
            this(
                    entryDir,
                    cache,
                    workers,
                    skipTests,
                    profile,
                    jdksDir,
                    serial,
                    parallelTests,
                    verbose,
                    false,
                    serial ? 1 : 0);
        }

        /** Backward-compatible ctor with rebuild, no jobs clamp beyond serial. */
        public ExplainRequest(
                Path entryDir,
                Path cache,
                int workers,
                boolean skipTests,
                String profile,
                Path jdksDir,
                boolean serial,
                boolean parallelTests,
                boolean verbose,
                boolean rebuild) {
            this(
                    entryDir,
                    cache,
                    workers,
                    skipTests,
                    profile,
                    jdksDir,
                    serial,
                    parallelTests,
                    verbose,
                    rebuild,
                    serial ? 1 : 0);
        }
    }

    // ---- resolver family (jk lock / update / sync) --------------------------------------------

    /**
     * Everything an engine-hosted {@code jk lock} needs — mirrors {@code LockCommand}'s local fields.
     * {@code conservative} marks an invisible freshen ({@link cc.jumpkick.cli.EnsureFreshLock}):
     * existing pins are kept as solver preferences; only explicit {@code jk lock} floats to latest.
     */
    public record LockRequest(
            Path entryDir,
            Path cache,
            List<String> features,
            boolean noDefaultFeatures,
            boolean sources,
            URI repoUrl,
            boolean offline,
            boolean force,
            boolean verbose,
            boolean conservative) {
        /** Back-compat: explicit lock semantics (latest versions). */
        public LockRequest(
                Path entryDir,
                Path cache,
                List<String> features,
                boolean noDefaultFeatures,
                boolean sources,
                URI repoUrl,
                boolean offline,
                boolean force,
                boolean verbose) {
            this(entryDir, cache, features, noDefaultFeatures, sources, repoUrl, offline, force, verbose, false);
        }
    }

    /** Everything an engine-hosted {@code jk update} needs — mirrors {@code UpdateCommand}'s local fields. */
    public record UpdateRequest(
            Path entryDir,
            Path cache,
            List<String> features,
            boolean noDefaultFeatures,
            URI repoUrl,
            boolean offline,
            boolean force,
            boolean verbose,
            String platform) {
        /** Back-compat without platform override. */
        public UpdateRequest(
                Path entryDir,
                Path cache,
                List<String> features,
                boolean noDefaultFeatures,
                URI repoUrl,
                boolean offline,
                boolean force,
                boolean verbose) {
            this(entryDir, cache, features, noDefaultFeatures, repoUrl, offline, force, verbose, null);
        }
    }

    /** Everything an engine-hosted {@code jk sync} needs — mirrors {@code SyncCommand}'s local fields. */
    public record SyncRequest(
            Path entryDir,
            Path cache,
            Path jdksDir,
            URI repoUrl,
            boolean sources,
            boolean offline,
            boolean force,
            boolean refresh,
            boolean verbose) {}

    /**
     * A lock/update cascade's client-side renderer contract — see {@link EngineResolveAdapter} for
     * the wire mechanics. {@code onModuleStart} is invoked once per module (entry project first,
     * then workspace modules in declaration order), after its step list has arrived, and returns
     * the {@link cc.jumpkick.run.BuildPlanListener} the module's wire events should drive — the same
     * listener the in-process path would attach to the live plan. {@code onPackage} fires per
     * resolved package (plain, unthemed — the renderer colorizes); {@code onModuleFinish} fires
     * after that listener's own {@code planFinish} has been dispatched.
     */
    public interface LockHandler {
        BuildPlanListener onModuleStart(String dir, String coord, List<Task> steps);

        default void onPackage(String dir, String name, String version) {}

        /**
         * @param totalSeen cumulative packages at this sample ({@code ≥ 0}), or {@code -1} when the
         * event is a single unbatched package (legacy). Defaults to {@link #onPackage(String,
         * String, String)}.
         */
        default void onPackage(String dir, String name, String version, int totalSeen) {
            onPackage(dir, name, version);
        }

        default void onModuleFinish(String dir, BuildPlanResult result, LockCounts counts) {}
    }

    /** A finished lock/update module's written-lockfile counts ({@code -1} when the plan failed before writing). */
    public record LockCounts(long packages, long sources, long plugins) {}

    /**
     * A lock/update request's terminal outcome. {@code errors} carries pre-plan failures (manifest
     * parse, workspace module load) as plain text; {@code refreshed} is {@code jk update --git}'s
     * refreshed count ({@code -1} otherwise). {@code exitCode} is authoritative — computed
     * engine-side from the step statuses (resolve failure exits 6, config problems 2).
     */
    public record LockOutcome(boolean success, int exitCode, List<String> errors, int refreshed) {}

    /** Everything an engine-hosted {@code jk outdated} needs — mirrors {@code OutdatedCommand}'s local fields. */
    public record OutdatedRequest(Path entryDir, Path cache, URI repoUrl, boolean offline, boolean force) {}

    // ---- hosted worker commands -------------------------------------------------------------------

    /** Everything an engine-hosted {@code jk audit} needs — mirrors {@code AuditCommand}'s local fields. */
    public record AuditRequest(
            Path entryDir, Path cache, String severity, URI osvBatchUrl, URI osvVulnsUrl, boolean offline) {}

    /** Everything an engine-hosted {@code jk format} needs — resolved styles, not raw flags. */
    public record FormatRequest(
            Path entryDir,
            Path cache,
            boolean check,
            String javaStyle,
            String kotlinStyle,
            boolean optimizeImports,
            boolean importOrder,
            boolean removeUnusedImports,
            boolean offline,
            boolean verbose) {}

    /** A hosted {@code jk format} run's summary, decoded from the terminal plan-finish. */
    public record FormatOutcome(BuildPlanResult result, int total, int workerExit) {}

    /**
     * Everything an engine-hosted {@code jk publish} needs. The credential and GPG passphrase were
     * resolved client-side (env/keychain live here, not in the engine's inherited environment); they
     * cross the user-owned socket and are never logged.
     */
    public record PublishRequest(
            Path entryDir,
            Path cache,
            URI repoUrl,
            String region,
            String endpoint,
            Path jarPath,
            boolean allowSnapshot,
            boolean dryRun,
            Path keyFile,
            String gpgPassphrase,
            boolean sigstore,
            boolean slsa,
            boolean sbom,
            RepoCredential credential,
            boolean offline,
            boolean verbose) {}

    /** A hosted {@code jk publish} run's summary, decoded from the terminal plan-finish. */
    public record PublishOutcome(BuildPlanResult result, int files) {}

    /** Everything an engine-hosted {@code jk image} needs — mirrors {@code ImageCommand}'s local fields. */
    public record ImageRequest(
            Path entryDir,
            Path cache,
            Path jdksDir,
            String mainClass,
            String registry,
            String tag,
            String tarballArg,
            String dockerExecutable,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean rerun,
            boolean verbose) {}

    /**
     * A hosted {@code jk image} run's structured summary. Exactly one of {@code tarball} (tarball
     * mode) or {@code daemonExe} (daemon-load mode) is non-null, or neither (registry push — render
     * {@code ref}); {@code testResult} is non-null when the plan's run-tests step reported
     * counts.
     */
    public record ImageSummary(
            TestSummary testResult, String ref, String tarball, String name, String version, String daemonExe) {}

    /** Everything an engine-hosted {@code jk import} needs — pre-flighted absolute paths. */
    public record ImportRequest(
            Path source, Path out, Path baseDir, Path tmpDir, boolean force, Path report, Path cache) {}

    /** A hosted {@code jk import} run's summary, decoded from the terminal plan-finish. */
    public record ImportOutcome(BuildPlanResult result, int exitCode, int warnings, String error, String diag) {}

    // ---- hosted plan commands ----------------------------------------------------------------

    /** Everything an engine-hosted {@code jk compile} needs — mirrors {@code CompileCommand}'s local fields. */
    public record CompileRequest(
            Path entryDir,
            Path cache,
            String profile,
            boolean offline,
            boolean force,
            boolean verbose,
            List<String> modules) {

        /** Back-compat: no module selection (entry dir / whole graph). */
        public CompileRequest(
                Path entryDir, Path cache, String profile, boolean offline, boolean force, boolean verbose) {
            this(entryDir, cache, profile, offline, force, verbose, List.of());
        }
    }

    /** Everything an engine-hosted {@code jk train} needs. */
    public record TrainRequest(
            Path entryDir,
            Path cache,
            Path jdksDir,
            Path graalHome,
            String profile,
            boolean force,
            boolean skipTests,
            boolean offline,
            boolean verbose) {}

    /**
     * Everything an engine-hosted {@code jk native} needs. {@code graalByDir} maps each
     * native-eligible module dir to the GraalVM home the client resolved for it — resolution (and
     * any consent prompt / install) happens client-side <em>before</em> this request, because it
     * owns the terminal.
     */
    public record NativeRequest(
            Path entryDir,
            Path cache,
            Path jdksDir,
            String mainClass,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean verbose,
            List<String> extraArgs,
            Map<Path, Path> graalByDir,
            /** When non-null/non-empty: only these module dirs (+ their build prereqs) run. */
            List<Path> selectedModuleDirs) {}

    /**
     * Everything an engine-hosted {@code jk install} (project mode) needs. {@code m2Dir} is the
     * resolved local Maven repo root; {@code graalHome} is non-null only for a native application
     * (resolved client-side, same pre-flight as {@link NativeRequest}).
     */
    public record InstallRequest(
            Path entryDir,
            Path cache,
            Path m2Dir,
            Path graalHome,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean verbose) {}

    /** Everything an engine-hosted {@code jk install <git-url>} fetch needs — pre-split/expanded client-side. */
    /** Engine-hosted {@code jk new}. {@code relaxParent} skips the dashboard HOME/tmp allowlist. */
    public record NewProjectRequest(
            String name,
            String parentDir,
            String group,
            String lang,
            String layout,
            String template,
            boolean executable,
            String jdk,
            int javaRelease,
            boolean assembly,
            boolean nativeImage,
            boolean plugin,
            String kotlinModule,
            List<String> deps,
            boolean sample,
            boolean standalone,
            Map<String, String> templateParams,
            boolean relaxParent,
            String targetDir) {
        public NewProjectRequest(
                String name,
                String parentDir,
                String group,
                String lang,
                String layout,
                String template,
                boolean executable,
                String jdk,
                int javaRelease,
                boolean assembly,
                boolean nativeImage,
                boolean plugin,
                String kotlinModule,
                List<String> deps,
                boolean sample,
                boolean standalone,
                Map<String, String> templateParams,
                boolean relaxParent) {
            this(
                    name,
                    parentDir,
                    group,
                    lang,
                    layout,
                    template,
                    executable,
                    jdk,
                    javaRelease,
                    assembly,
                    nativeImage,
                    plugin,
                    kotlinModule,
                    deps,
                    sample,
                    standalone,
                    templateParams,
                    relaxParent,
                    null);
        }
    }

    public record GitFetchRequest(
            String url, String canonicalUrl, String ref, Path cache, boolean refresh, boolean requireJkToml) {
        public GitFetchRequest(String url, String canonicalUrl, String ref, Path cache, boolean refresh) {
            this(url, canonicalUrl, ref, cache, refresh, true);
        }
    }

    /** A hosted git fetch's outcome: the plan result plus the materialized checkout + sha (null on failure). */
    public record GitFetchOutcome(BuildPlanResult result, Path checkout, String sha) {}

    // ---- hosted long-tail commands ----------------------------------------------------------------

    /**
     * Everything an engine-hosted tool resolution needs ({@code jk tool install}/{@code jk tool
     * run}/{@code jk install <g:a:v>}). {@code mainClass} is the {@code --main} override (may be
     * {@code null}); {@code repoUrl} overrides Maven Central (may be {@code null}).
     */
    public record ToolResolveRequest(
            String coord, List<String> with, String bin, String mainClass, URI repoUrl, Path cache) {}

    /**
     * A hosted tool resolution's outcome: the plan result plus the pinned {@code g:a:v} the engine
     * landed on, the resolved main class, and the classpath (null/empty on failure) — the
     * ingredients of a client-side {@code ToolEnv}.
     */
    public record ToolResolveOutcome(BuildPlanResult result, String coord, String mainClass, List<Path> classpath) {}

    /**
     * Everything an engine-hosted script/jar preparation needs ({@code jk tool run <file>}).
     * {@code mode} = {@code java}/{@code kt}/{@code kts}/{@code jar}; {@code stateDir}/{@code
     * repoUrl} may be {@code null} (defaults).
     */
    public record ScriptPrepareRequest(
            String mode,
            Path script,
            Path cache,
            Path stateDir,
            URI repoUrl,
            boolean forceRecompile,
            List<String> with) {
        public ScriptPrepareRequest(
                String mode, Path script, Path cache, Path stateDir, URI repoUrl, boolean forceRecompile) {
            this(mode, script, cache, stateDir, repoUrl, forceRecompile, List.of());
        }
    }

    /**
     * A hosted script preparation's outcome: the plan result plus the exec ingredients — fields not
     * applicable to the mode (and everything on failure) are {@code null}/empty.
     */
    public record ScriptPrepareOutcome(
            BuildPlanResult result,
            String mainClass,
            List<Path> classpath,
            Path classesDir,
            Path kotlincBin,
            Path stdlib) {}

    /**
     * Everything an engine-hosted cache maintenance op needs ({@code op} = {@code prune}/{@code
     * purge}/{@code sweep}/{@code clear} — {@code jk cache clean}/{@code nuke}, {@code jk storage
     * clean}, {@code jk clean --force}). Ops ignore the fields they don't use; {@code projectRoot}
     * is {@code null} for everything but {@code clear}.
     */
    public record CacheMaintRequest(String op, Path cache, boolean dryRun, boolean includeJkTmp, Path projectRoot) {}

    /** A hosted cache maintenance op's summary, decoded from the terminal plan-finish ({@code -1} = n/a). */
    public record CacheMaintSummary(long files, long bytes) {}
}
