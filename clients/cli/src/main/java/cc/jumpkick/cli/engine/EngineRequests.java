// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Request/outcome records for engine-hosted verbs. */
public final class EngineRequests {
    private EngineRequests() {}

    public record ExplainRequest(
            Path entryDir,
            Path cache,
            int workers,
            boolean skipTests,
            @Nullable String profile,
            @Nullable Path jdksDir,
            boolean serial,
            boolean parallelTests,
            boolean verbose,
            boolean rebuild,
            int maxModuleConcurrency,
            /** {@code -m}/{@code --affected-since} selector tokens, as {@code jk build} sends them. */
            List<String> modules,
            /** Plan compiles only on a compiler worker already running: {@code jk status}'s forecast. */
            boolean quick) {

        /** As the canonical form, not quick. */
        public ExplainRequest(
                Path entryDir,
                Path cache,
                int workers,
                boolean skipTests,
                @Nullable String profile,
                @Nullable Path jdksDir,
                boolean serial,
                boolean parallelTests,
                boolean verbose,
                boolean rebuild,
                int maxModuleConcurrency,
                List<String> modules) {
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
                    maxModuleConcurrency,
                    modules,
                    false);
        }

        /** No rebuild; {@code maxModuleConcurrency} follows {@code serial}; the whole workspace. */
        public ExplainRequest(
                Path entryDir,
                Path cache,
                int workers,
                boolean skipTests,
                @Nullable String profile,
                @Nullable Path jdksDir,
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
                    serial ? 1 : 0,
                    List.of());
        }

        /** Rebuild allowed; no jobs clamp beyond {@code serial}; the whole workspace. */
        public ExplainRequest(
                Path entryDir,
                Path cache,
                int workers,
                boolean skipTests,
                @Nullable String profile,
                @Nullable Path jdksDir,
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
                    serial ? 1 : 0,
                    List.of());
        }
    }

    // ---- resolver family (jk lock / update / sync) --------------------------------------------

    /**
     * Everything an engine-hosted {@code jk lock} needs — mirrors {@code LockCommand}'s local fields.
     * {@code freshen} marks an invisible freshen ({@link cc.jumpkick.cli.api.EnsureFreshLock}): existing
     * pins are always kept and an already-current lock is a no-op. Without it {@code force} decides
     * — bare {@code jk lock} keeps pins, {@code jk lock -F} floats within the declared ranges.
     */
    public record LockRequest(
            Path entryDir,
            Path cache,
            List<String> features,
            boolean noDefaultFeatures,
            boolean sources,
            @Nullable URI repoUrl,
            boolean offline,
            boolean force,
            boolean verbose,
            boolean freshen) {
        /** A lock the user asked for by name — not an invisible freshen. */
        public LockRequest(
                Path entryDir,
                Path cache,
                List<String> features,
                boolean noDefaultFeatures,
                boolean sources,
                @Nullable URI repoUrl,
                boolean offline,
                boolean force,
                boolean verbose) {
            this(entryDir, cache, features, noDefaultFeatures, sources, repoUrl, offline, force, verbose, false);
        }
    }

    /**
     * Everything an engine-hosted {@code jk update} needs — mirrors {@code UpdateCommand}'s local
     * fields. {@code deps} limits the pin rewrite to those handles or {@code group:artifact}
     * coordinates (empty = all); {@code major} lets a pin cross its Maven major.
     */
    public record UpdateRequest(
            Path entryDir,
            Path cache,
            List<String> features,
            boolean noDefaultFeatures,
            @Nullable URI repoUrl,
            boolean offline,
            boolean force,
            boolean verbose,
            @Nullable String platform,
            List<String> deps,
            boolean major) {
        /** No platform override, every pin, same major. */
        public UpdateRequest(
                Path entryDir,
                Path cache,
                List<String> features,
                boolean noDefaultFeatures,
                @Nullable URI repoUrl,
                boolean offline,
                boolean force,
                boolean verbose) {
            this(
                    entryDir,
                    cache,
                    features,
                    noDefaultFeatures,
                    repoUrl,
                    offline,
                    force,
                    verbose,
                    null,
                    List.of(),
                    false);
        }
    }

    /** Everything an engine-hosted {@code jk sync} needs — mirrors {@code SyncCommand}'s local fields. */
    public record SyncRequest(
            Path entryDir,
            Path cache,
            @Nullable Path jdksDir,
            @Nullable URI repoUrl,
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

        default void onPackage(@Nullable String dir, String name, @Nullable String version) {}

        /**
         * @param totalSeen cumulative packages at this sample ({@code ≥ 0}), or {@code -1} when the
         * event is a single unbatched package. Defaults to {@link #onPackage(String,
         * String, String)}.
         */
        default void onPackage(@Nullable String dir, String name, @Nullable String version, int totalSeen) {
            onPackage(dir, name, version);
        }

        default void onModuleFinish(String dir, BuildPlanResult result, LockCounts counts) {}

        /**
         * The lock's current phase line for the module under {@code dir} ({@code Resolving dependency
         * graph… N packages so far, Ts}, {@code Downloading N artifacts…}): what a client with no live
         * region prints so a long solve is seen moving.
         */
        default void onPhase(@Nullable String dir, String label) {}

        /**
         * One declared pin {@code jk update} moved in the manifest under {@code dir} ({@code handle}
         * in {@code table}, {@code from} → {@code to}); fires before that module's plan starts.
         */
        default void onRewrite(String dir, String table, String handle, String module, String from, String to) {}

        /**
         * One package {@code jk update}'s relock changed in the lockfile under {@code dir}: {@code
         * from} null when added, {@code to} null when removed, {@code members} the workspace members
         * a member-override row serves. Fires before that module's {@link #onModuleFinish}.
         */
        default void onChange(
                String dir, String coordinate, @Nullable String from, @Nullable String to, List<String> members) {}
    }

    /**
     * A finished lock/update module's written-lockfile counts ({@code -1} when the plan failed
     * before writing) — {@code changed} is how many packages the write added, removed or moved —
     * the artifacts it pinned without a published checksum under
     * {@code allow-unverified}, and the plaintext {@code http://} repositories it asked.
     */
    public record LockCounts(
            long packages, long changed, long sources, long plugins, long unverified, List<String> insecureRepos) {}

    /**
     * A lock/update request's terminal outcome. {@code errors} carries pre-plan failures (manifest
     * parse, workspace module load) as plain text; {@code refreshed} is {@code jk update -g}'s
     * refreshed count ({@code -1} otherwise). {@code exitCode} is authoritative — computed
     * engine-side from the step statuses (resolve failure exits 6, config problems 2).
     */
    public record LockOutcome(boolean success, int exitCode, List<String> errors, int refreshed) {}

    /** Everything an engine-hosted {@code jk outdated} needs — mirrors {@code OutdatedCommand}'s local fields. */
    public record OutdatedRequest(
            Path entryDir, Path cache, @Nullable URI repoUrl, boolean offline, boolean force) {}

    /** The beats of an outdated read: {@code checked} of {@code total} rows done, {@code coordinate} in flight. */
    @FunctionalInterface
    public interface OutdatedHandler {
        void onChecking(int checked, int total, String coordinate);

        /** Discard the beats: JSON output and tests. */
        OutdatedHandler NONE = (checked, total, coordinate) -> {};
    }

    // ---- hosted worker commands -------------------------------------------------------------------

    /** Everything an engine-hosted {@code jk audit} needs — mirrors {@code AuditCommand}'s local fields. */
    public record AuditRequest(
            Path entryDir,
            Path cache,
            String severity,
            @Nullable URI osvBatchUrl,
            @Nullable URI osvVulnsUrl,
            boolean offline) {}

    /** Everything an engine-hosted {@code jk format} needs — resolved styles, not raw flags. */
    public record FormatRequest(
            Path entryDir,
            Path cache,
            boolean check,
            @Nullable String javaStyle,
            @Nullable String kotlinStyle,
            boolean optimizeImports,
            boolean importOrder,
            boolean removeUnusedImports,
            boolean offline,
            boolean verbose) {}

    /** A hosted {@code jk format} run's summary, decoded from the terminal plan-finish. */
    public record FormatOutcome(BuildPlanResult result, int total, int workerExit) {}

    /** Everything an engine-hosted {@code jk import} needs — pre-flighted absolute paths. */
    public record ImportRequest(
            Path source,
            Path out,
            @Nullable Path baseDir,
            Path tmpDir,
            boolean overwrite,
            boolean dryRun,
            @Nullable Path report,
            Path cache,
            List<String> profiles) {}

    /** A hosted {@code jk import} run's summary, decoded from the terminal plan-finish. */
    public record ImportOutcome(
            BuildPlanResult result,
            int exitCode,
            int warnings,
            @Nullable String error) {}

    // ---- hosted plan commands ----------------------------------------------------------------

    /** Everything an engine-hosted {@code jk install <git-url>} fetch needs — pre-split/expanded client-side. */
    /** Engine-hosted {@code jk new}. {@code relaxParent} skips the dashboard HOME/tmp allowlist. */
    public record NewProjectRequest(
            String name,
            String parentDir,
            @Nullable String group,
            @Nullable String lang,
            String layout,
            @Nullable String template,
            boolean executable,
            @Nullable String jdk,
            int javaRelease,
            boolean assembly,
            boolean nativeImage,
            boolean plugin,
            @Nullable String kotlinModule,
            List<String> deps,
            boolean sample,
            boolean standalone,
            Map<String, String> templateParams,
            boolean relaxParent,
            @Nullable String targetDir) {
        public NewProjectRequest(
                String name,
                String parentDir,
                @Nullable String group,
                @Nullable String lang,
                String layout,
                @Nullable String template,
                boolean executable,
                @Nullable String jdk,
                int javaRelease,
                boolean assembly,
                boolean nativeImage,
                boolean plugin,
                @Nullable String kotlinModule,
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
    public record GitFetchOutcome(
            BuildPlanResult result,
            @Nullable Path checkout,
            @Nullable String sha) {}

    // ---- hosted long-tail commands ----------------------------------------------------------------

    /**
     * Everything an engine-hosted tool resolution needs ({@code jk tool install}/{@code jk tool
     * run}/{@code jk install <g:a:v>}). {@code mainClass} is the {@code --main} override (may be
     * {@code null}); {@code repoUrl} overrides Maven Central (may be {@code null}).
     */
    public record ToolResolveRequest(
            String coord,
            List<String> with,
            String bin,
            @Nullable String mainClass,
            @Nullable URI repoUrl,
            Path cache) {}

    /**
     * A hosted tool resolution's outcome: the plan result plus the pinned {@code g:a:v} the engine
     * landed on, the resolved main class, and the classpath (null/empty on failure) — the
     * ingredients of a client-side {@code ToolEnv}.
     */
    public record ToolResolveOutcome(
            BuildPlanResult result,
            @Nullable String coord,
            @Nullable String mainClass,
            List<Path> classpath) {}

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
            @Nullable URI repoUrl,
            boolean forceRecompile,
            List<String> with) {
        public ScriptPrepareRequest(
                String mode, Path script, Path cache, Path stateDir, @Nullable URI repoUrl, boolean forceRecompile) {
            this(mode, script, cache, stateDir, repoUrl, forceRecompile, List.of());
        }
    }

    /**
     * A hosted script preparation's outcome: the plan result plus the exec ingredients — fields not
     * applicable to the mode (and everything on failure) are {@code null}/empty.
     */
    public record ScriptPrepareOutcome(
            BuildPlanResult result,
            @Nullable String mainClass,
            List<Path> classpath,
            @Nullable Path classesDir,
            @Nullable Path kotlincBin,
            @Nullable Path stdlib) {}

    /**
     * Everything an engine-hosted cache maintenance op needs ({@code op} = {@code prune}/{@code
     * purge}/{@code sweep}/{@code clear}/{@code m2} — {@code jk cache clean}/{@code nuke}, {@code jk storage
     * clean}, {@code jk clean -F}). Ops ignore the fields they don't use; {@code projectRoot}
     * is {@code null} for everything but {@code clear} and {@code m2}; {@code olderThanDays} is only {@code m2}'s.
     */
    public record CacheMaintRequest(
            String op,
            Path cache,
            boolean dryRun,
            boolean includeJkTmp,
            @Nullable Path projectRoot,
            int olderThanDays) {}

    /** A hosted cache maintenance op's summary, decoded from the terminal plan-finish ({@code -1} = n/a). */
    public record CacheMaintSummary(long files, long bytes) {}
}
