// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.ArrayList;
import java.util.List;

/**
 * Client → server request builders for artifact-producing verbs. Session attachment
 * ({@link ProtoSession#withSession}) is separate; this file is the request bodies
 * (scoreboard 800–1,200).
 */
public final class ProtoJobs {

    private ProtoJobs() {}

    public static String buildRequest(
            String dir,
            String cache,
            String jdksDir,
            int workers,
            String profile,
            boolean skipTests,
            boolean verbose,
            int maxModuleConcurrency,
            boolean parallelTests,
            boolean offline,
            boolean force,
            boolean freshenLock) {
        return buildRequest(
                dir,
                cache,
                jdksDir,
                workers,
                profile,
                skipTests,
                verbose,
                maxModuleConcurrency,
                parallelTests,
                offline,
                force,
                freshenLock,
                false);
    }

    /**
     * As above with {@code ephemeralActions} ({@code jk verify} scratch rebuild: tasks must not
     * persist action-cache records or incremental state). Emitted only when true so older engines
     * see an unchanged request.
     */
    public static String buildRequest(
            String dir,
            String cache,
            String jdksDir,
            int workers,
            String profile,
            boolean skipTests,
            boolean verbose,
            int maxModuleConcurrency,
            boolean parallelTests,
            boolean offline,
            boolean force,
            boolean freshenLock,
            boolean ephemeralActions) {
        return buildRequest(
                dir,
                cache,
                jdksDir,
                workers,
                profile,
                skipTests,
                verbose,
                maxModuleConcurrency,
                parallelTests,
                offline,
                force,
                freshenLock,
                ephemeralActions,
                false,
                null);
    }

    /**
     * As above with the workspace test/selection controls. {@code testOnly} makes every module
     * plan stop at {@code run-tests} (workspace {@code jk test}); {@code dirtyHint} is the
     * client's module selection ({@code -m} / {@code --affected-since}) — module dirs the engine
     * must schedule instead of forecasting dirtiness itself. Both are omitted from the wire when
     * unset (false / null / empty).
     */
    public static String buildRequest(
            String dir,
            String cache,
            String jdksDir,
            int workers,
            String profile,
            boolean skipTests,
            boolean verbose,
            int maxModuleConcurrency,
            boolean parallelTests,
            boolean offline,
            boolean force,
            boolean freshenLock,
            boolean ephemeralActions,
            boolean testOnly,
            List<String> dirtyHint) {
        return buildRequest(
                dir,
                cache,
                jdksDir,
                workers,
                profile,
                skipTests,
                verbose,
                maxModuleConcurrency,
                parallelTests,
                offline,
                force,
                freshenLock,
                ephemeralActions,
                testOnly,
                dirtyHint,
                null);
    }

    /**
     * As above with an optional suite/tag {@code selection} ({@code null} or DEFAULT emits
     * nothing) — how a workspace test job carries the same selection fields as
     * {@link #testRequest}.
     */
    public static String buildRequest(
            String dir,
            String cache,
            String jdksDir,
            int workers,
            String profile,
            boolean skipTests,
            boolean verbose,
            int maxModuleConcurrency,
            boolean parallelTests,
            boolean offline,
            boolean force,
            boolean freshenLock,
            boolean ephemeralActions,
            boolean testOnly,
            List<String> dirtyHint,
            cc.jumpkick.config.TestSelection selection) {
        // noTimeline rides the session envelope ({@link #withSession}) only when true — never emit
        // a false default here (Jsonl.bool takes the first key match).
        return "{\"type\":\""
                + EngineProtocol.BUILD_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"jdksDir\":"
                + Jsonl.quote(jdksDir)
                + ",\"workers\":"
                + workers
                + ",\"profile\":"
                + Jsonl.quote(profile)
                + ",\"skipTests\":"
                + skipTests
                + ",\"verbose\":"
                + verbose
                + ",\"maxModuleConcurrency\":"
                + maxModuleConcurrency
                + ",\"parallelTests\":"
                + parallelTests
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"freshenLock\":"
                + freshenLock
                + (ephemeralActions ? ",\"ephemeralActions\":true" : "")
                + (testOnly ? ",\"testOnly\":true" : "")
                + (dirtyHint != null && !dirtyHint.isEmpty() ? ",\"dirtyHint\":" + jsonStringArray(dirtyHint) : "")
                + (selection != null && !selection.equals(cc.jumpkick.config.TestSelection.DEFAULT)
                        ? testSelectionFields(selection)
                        : "")
                + triggerJsonSuffix()
                + progressModeJsonSuffix()
                + "}";
    }

    /**
     * The client's {@code JK_PROGRESS_MODE} rides each request so the resident engine paints the
     * requesting shell's mode, not whatever env the daemon happened to start with.
     * Emitted only when non-AUTO so older engines see an unchanged request.
     */
    static String progressModeJsonSuffix() {
        var mode = cc.jumpkick.runtime.progress.ProgressBarMode.fromEnvironment();
        if (mode == cc.jumpkick.runtime.progress.ProgressBarMode.AUTO) return "";
        return ",\"progressMode\":" + Jsonl.quote(mode.wireName());
    }

    /** Per-request progress mode; engine-env fallback when the client sent none. */
    public static cc.jumpkick.runtime.progress.ProgressBarMode progressModeOf(String json) {
        String raw = Jsonl.str(json, "progressMode");
        if (raw == null || raw.isBlank()) return cc.jumpkick.runtime.progress.ProgressBarMode.fromEnvironment();
        return cc.jumpkick.runtime.progress.ProgressBarMode.parse(raw);
    }

    /**
     * The build request's module-selection hint, or {@code null} when the client sent none (the
     * engine forecasts dirty modules itself). Never an empty list — an empty selection is not a
     * selection.
     */
    public static List<String> dirtyHintOf(String json) {
        List<String> dirs = stringArrayField(json, "dirtyHint");
        return dirs.isEmpty() ? null : dirs;
    }

    /**
     * Optional {@code trigger} for journal classification ({@code cli}/{@code web}/
     * {@code optimize}/{@code calibrate}). Taken from {@code -Djk.build.trigger} or env
     * {@code JK_BUILD_TRIGGER} so install optimize can mark synthetic runs without a new
     * overload on every call site.
     */
    static String triggerJsonSuffix() {
        String t = System.getProperty("jk.build.trigger");
        if (t == null || t.isBlank()) t = System.getenv("JK_BUILD_TRIGGER");
        if (t == null || t.isBlank()) return "";
        return ",\"trigger\":" + Jsonl.quote(t.trim());
    }

    public static String buildCancel() {
        return "{\"type\":\"" + EngineProtocol.BUILD_CANCEL + "\"}";
    }

    /** Start a single-project test run (see {@link EngineProtocol#TEST_REQUEST}). {@code jdksDir}/{@code profile} may be {@code null}. */
    public static String testRequest(
            String dir,
            String cache,
            String jdksDir,
            int workers,
            String profile,
            boolean verbose,
            boolean offline,
            boolean force) {
        return testRequest(dir, cache, jdksDir, workers, profile, verbose, offline, force, false);
    }

    /**
     * Start a single-project test run. {@code parallelTests} lifts the engine's cross-module test
     * gate when several test-requests overlap (workspace {@code jk test --parallel-tests}).
     */
    public static String testRequest(
            String dir,
            String cache,
            String jdksDir,
            int workers,
            String profile,
            boolean verbose,
            boolean offline,
            boolean force,
            boolean parallelTests) {
        return testRequest(dir, cache, jdksDir, workers, profile, verbose, offline, force, parallelTests, null);
    }

    /**
     * Start a single-project test run with suite/tag selection1136). {@code selection}
     * may be {@code null} (default suite only).
     */
    public static String testRequest(
            String dir,
            String cache,
            String jdksDir,
            int workers,
            String profile,
            boolean verbose,
            boolean offline,
            boolean force,
            boolean parallelTests,
            cc.jumpkick.config.TestSelection selection) {
        // noTimeline: session envelope only (see {@link #withSession}).
        return "{\"type\":\""
                + EngineProtocol.TEST_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"jdksDir\":"
                + Jsonl.quote(jdksDir)
                + ",\"workers\":"
                + workers
                + ",\"profile\":"
                + Jsonl.quote(profile)
                + ",\"verbose\":"
                + verbose
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"parallelTests\":"
                + parallelTests
                + testSelectionFields(selection)
                + triggerJsonSuffix()
                + progressModeJsonSuffix()
                + "}";
    }

    /** Encode suite/tag fields for {@link EngineProtocol#TEST_REQUEST} (and siblings that carry the same shape). */
    public static String testSelectionFields(cc.jumpkick.config.TestSelection selection) {
        cc.jumpkick.config.TestSelection s = selection == null ? cc.jumpkick.config.TestSelection.DEFAULT : selection;
        StringBuilder sb = new StringBuilder();
        sb.append(",\"allSuites\":").append(s.allSuites());
        sb.append(",\"suites\":").append(jsonStringArray(s.suites()));
        sb.append(",\"includeTags\":").append(jsonStringArray(s.includeTags()));
        sb.append(",\"excludeTags\":").append(jsonStringArray(s.excludeTags()));
        sb.append(",\"tagsResolved\":").append(s.tagsResolved());
        return sb.toString();
    }

    /** Parse suite/tag selection from a test/build request line. */
    public static cc.jumpkick.config.TestSelection testSelectionOf(String json) {
        boolean all = Jsonl.bool(json, "allSuites", false);
        List<String> suites = stringArrayField(json, "suites");
        List<String> include = stringArrayField(json, "includeTags");
        List<String> exclude = stringArrayField(json, "excludeTags");
        boolean tagsResolved = Jsonl.bool(json, "tagsResolved", false);
        return cc.jumpkick.config.TestSelection.of(suites, all, include, exclude, tagsResolved);
    }

    private static String jsonStringArray(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Jsonl.quote(values.get(i)));
        }
        return sb.append(']').toString();
    }

    /** Best-effort parse of a JSON string array field (flat list of quoted strings). */
    private static List<String> stringArrayField(String json, String key) {
        if (json == null) return List.of();
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return List.of();
        start += needle.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length() || json.charAt(start) != '[') return List.of();
        int end = json.indexOf(']', start);
        if (end < 0) return List.of();
        String body = json.substring(start + 1, end).trim();
        if (body.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < body.length()) {
            while (i < body.length() && (body.charAt(i) == ' ' || body.charAt(i) == ',')) i++;
            if (i >= body.length()) break;
            if (body.charAt(i) != '"') break;
            int j = i + 1;
            StringBuilder s = new StringBuilder();
            while (j < body.length()) {
                char c = body.charAt(j);
                if (c == '\\' && j + 1 < body.length()) {
                    s.append(body.charAt(j + 1));
                    j += 2;
                    continue;
                }
                if (c == '"') break;
                s.append(c);
                j++;
            }
            out.add(s.toString());
            i = j + 1;
        }
        return List.copyOf(out);
    }

    /** Start a single-project build (see {@link EngineProtocol#SINGLE_BUILD_REQUEST}). {@code jdksDir}/{@code profile} may be {@code null}. */
    public static String singleBuildRequest(
            String dir,
            String cache,
            String jdksDir,
            int workers,
            String profile,
            boolean skipTests,
            boolean verbose,
            boolean offline,
            boolean force) {
        // noTimeline: session envelope only (see {@link #withSession}).
        return "{\"type\":\""
                + EngineProtocol.SINGLE_BUILD_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"jdksDir\":"
                + Jsonl.quote(jdksDir)
                + ",\"workers\":"
                + workers
                + ",\"profile\":"
                + Jsonl.quote(profile)
                + ",\"skipTests\":"
                + skipTests
                + ",\"verbose\":"
                + verbose
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + "}";
    }

    /** Notify client that a chrome timeline file was written (absolute path). */
    public static String timeline(String absolutePath) {
        return "{\"type\":\"" + EngineProtocol.TIMELINE + "\",\"path\":" + Jsonl.quote(absolutePath) + "}";
    }

    /**
     * Resolve + write {@code jk-lock.toml} (see {@link EngineProtocol#LOCK_REQUEST}). {@code repoUrl} may be {@code
     * null}. {@code offline}/{@code force}/{@code verbose} reconstruct the session config engine-side
     * (the same fields {@link #buildRequest} carries). {@code conservative} marks an invisible
     * freshen: existing lock pins are kept as solver preferences instead of floating to latest.
     */
    public static String lockRequest(
            String dir,
            String cache,
            List<String> features,
            boolean noDefaultFeatures,
            boolean sources,
            String repoUrl,
            boolean offline,
            boolean force,
            boolean verbose,
            boolean conservative) {
        return "{\"type\":\""
                + EngineProtocol.LOCK_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"features\":"
                + EngineProtocol.quoteArray(features)
                + ",\"noDefaultFeatures\":"
                + noDefaultFeatures
                + ",\"sources\":"
                + sources
                + ",\"repoUrl\":"
                + Jsonl.quote(repoUrl)
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + ",\"conservative\":"
                + conservative
                + "}";
    }

    /**
     * Re-resolve fresh and overwrite {@code jk-lock.toml} (see {@link EngineProtocol#UPDATE_REQUEST}). {@code gitTarget}
     * is the {@code --git <name>} argument ({@code null} = every git dep) and is only read when
     * {@code gitOnly} is set.
     */
    public static String updateRequest(
            String dir,
            String cache,
            List<String> features,
            boolean noDefaultFeatures,
            String repoUrl,
            boolean gitOnly,
            String gitTarget,
            boolean offline,
            boolean force,
            boolean verbose) {
        return updateRequest(
                dir, cache, features, noDefaultFeatures, repoUrl, gitOnly, gitTarget, offline, force, verbose, null);
    }

    /**
     * As {@link #updateRequest(String, String, List, boolean, String, boolean, String, boolean, boolean, boolean)}
     * with optional {@code platform} ({@code enforced}|{@code floor},. Null/blank =
     * project default.
     */
    public static String updateRequest(
            String dir,
            String cache,
            List<String> features,
            boolean noDefaultFeatures,
            String repoUrl,
            boolean gitOnly,
            String gitTarget,
            boolean offline,
            boolean force,
            boolean verbose,
            String platform) {
        return "{\"type\":\""
                + EngineProtocol.UPDATE_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"features\":"
                + EngineProtocol.quoteArray(features)
                + ",\"noDefaultFeatures\":"
                + noDefaultFeatures
                + ",\"repoUrl\":"
                + Jsonl.quote(repoUrl)
                + ",\"gitOnly\":"
                + gitOnly
                + ",\"gitTarget\":"
                + Jsonl.quote(gitTarget)
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + ",\"platform\":"
                + Jsonl.quote(platform == null ? "" : platform)
                + "}";
    }

    /**
     * Sync the CAS + toolchain with {@code jk-lock.toml} (see {@link EngineProtocol#SYNC_REQUEST}). {@code jdksDir}/
     * {@code repoUrl} may be {@code null}. {@code refresh} rides separately from {@code force} for
     * the same reason {@code rerun} does on {@link #buildRequest}: it re-downloads locked artifacts
     * without implying the rest of {@code force}'s cache bypasses.
     */
    public static String syncRequest(
            String dir,
            String cache,
            String jdksDir,
            String repoUrl,
            boolean sources,
            boolean offline,
            boolean force,
            boolean refresh,
            boolean verbose) {
        return "{\"type\":\""
                + EngineProtocol.SYNC_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"jdksDir\":"
                + Jsonl.quote(jdksDir)
                + ",\"repoUrl\":"
                + Jsonl.quote(repoUrl)
                + ",\"sources\":"
                + sources
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"refresh\":"
                + refresh
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Scan the lockfile against OSV (see {@link EngineProtocol#AUDIT_REQUEST}). {@code severity} is the client's
     * threshold, carried only for the evaluate step's label (the client applies the threshold
     * itself); {@code osvBatchUrl}/{@code osvVulnsUrl} are the hidden test overrides and may be
     * {@code null}.
     */
    public static String auditRequest(
            String dir, String cache, String severity, String osvBatchUrl, String osvVulnsUrl) {
        return "{\"type\":\""
                + EngineProtocol.AUDIT_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"severity\":"
                + Jsonl.quote(severity)
                + ",\"osvBatchUrl\":"
                + Jsonl.quote(osvBatchUrl)
                + ",\"osvVulnsUrl\":"
                + Jsonl.quote(osvVulnsUrl)
                + "}";
    }

    /**
     * Format sources (see {@link EngineProtocol#FORMAT_REQUEST}). Style names and hygiene toggles arrive already
     * resolved (flags + env + the {@code [format]} block are client-side concerns); {@code
     * rewriteConfig} may be {@code null}.
     */
    public static String formatRequest(
            String dir,
            String cache,
            boolean check,
            String javaStyle,
            String kotlinStyle,
            boolean optimizeImports,
            boolean importOrder,
            boolean removeUnusedImports,
            String rewriteConfig,
            boolean offline,
            boolean verbose) {
        return "{\"type\":\""
                + EngineProtocol.FORMAT_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"check\":"
                + check
                + ",\"javaStyle\":"
                + Jsonl.quote(javaStyle)
                + ",\"kotlinStyle\":"
                + Jsonl.quote(kotlinStyle)
                + ",\"optimizeImports\":"
                + optimizeImports
                + ",\"importOrder\":"
                + importOrder
                + ",\"removeUnusedImports\":"
                + removeUnusedImports
                + ",\"rewriteConfig\":"
                + Jsonl.quote(rewriteConfig)
                + ",\"offline\":"
                + offline
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Publish artifacts (see {@link EngineProtocol#PUBLISH_REQUEST}). The credential fields ({@code authType} =
     * {@code basic}/{@code bearer}/{@code anonymous} + {@code user}/{@code pass}/{@code token}) and
     * {@code gpgPassphrase} were resolved client-side; nullable string fields may be {@code null}.
     */
    public static String publishRequest(
            String dir,
            String cache,
            String repoUrl,
            String region,
            String endpoint,
            String jar,
            boolean allowSnapshot,
            boolean dryRun,
            String keyFile,
            String gpgPassphrase,
            boolean sigstore,
            boolean slsa,
            boolean sbom,
            String authType,
            String user,
            String pass,
            String token,
            boolean verbose) {
        return "{\"type\":\""
                + EngineProtocol.PUBLISH_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"repoUrl\":"
                + Jsonl.quote(repoUrl)
                + ",\"region\":"
                + Jsonl.quote(region)
                + ",\"endpoint\":"
                + Jsonl.quote(endpoint)
                + ",\"jar\":"
                + Jsonl.quote(jar)
                + ",\"allowSnapshot\":"
                + allowSnapshot
                + ",\"dryRun\":"
                + dryRun
                + ",\"keyFile\":"
                + Jsonl.quote(keyFile)
                + ",\"gpgPassphrase\":"
                + Jsonl.quote(gpgPassphrase)
                + ",\"sigstore\":"
                + sigstore
                + ",\"slsa\":"
                + slsa
                + ",\"sbom\":"
                + sbom
                + ",\"authType\":"
                + Jsonl.quote(authType)
                + ",\"user\":"
                + Jsonl.quote(user)
                + ",\"pass\":"
                + Jsonl.quote(pass)
                + ",\"token\":"
                + Jsonl.quote(token)
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Build an OCI image (see {@link EngineProtocol#IMAGE_REQUEST}). {@code tarball} is tri-state: {@code null}
     * (no tarball — daemon/push mode), {@code ""} (default layout path), or an explicit path — the
     * same tri-state {@code --tarball}'s optional value has. {@code offline}/{@code force}/{@code
     * rerun}/{@code verbose} reconstruct the session config engine-side, as on {@link
     * #buildRequest}.
     */
    public static String imageRequest(
            String dir,
            String cache,
            String jdksDir,
            String mainClass,
            String registry,
            String tag,
            String tarball,
            String dockerExecutable,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean verbose) {
        return "{\"type\":\""
                + EngineProtocol.IMAGE_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"jdksDir\":"
                + Jsonl.quote(jdksDir)
                + ",\"mainClass\":"
                + Jsonl.quote(mainClass)
                + ",\"registry\":"
                + Jsonl.quote(registry)
                + ",\"tag\":"
                + Jsonl.quote(tag)
                + ",\"tarball\":"
                + Jsonl.quote(tarball)
                + ",\"dockerExecutable\":"
                + Jsonl.quote(dockerExecutable)
                + ",\"skipTests\":"
                + skipTests
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Convert a foreign build to {@code jk.toml} (see {@link EngineProtocol#IMPORT_REQUEST}). All paths are
     * absolute (the client pre-flighted detection/overwrite checks); {@code report} may be {@code
     * null}.
     */
    public static String importRequest(
            String source, String out, String baseDir, String tmpDir, boolean force, String report, String cache) {
        return "{\"type\":\""
                + EngineProtocol.IMPORT_REQUEST
                + "\",\"source\":"
                + Jsonl.quote(source)
                + ",\"out\":"
                + Jsonl.quote(out)
                + ",\"baseDir\":"
                + Jsonl.quote(baseDir)
                + ",\"tmpDir\":"
                + Jsonl.quote(tmpDir)
                + ",\"force\":"
                + force
                + ",\"report\":"
                + Jsonl.quote(report)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + "}";
    }

    /**
     * Provision a Maven/Gradle distribution (see {@link EngineProtocol#PROVISION_REQUEST}). Project directory
     * field is {@code dir} — same spelling as every other hosted request.
     */
    public static String provisionRequest(
            String cache, String dir, String toolsRoot, boolean noDiscover, boolean gradle) {
        return "{\"type\":\""
                + EngineProtocol.PROVISION_REQUEST
                + "\",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"toolsRoot\":"
                + Jsonl.quote(toolsRoot)
                + ",\"noDiscover\":"
                + noDiscover
                + ",\"gradle\":"
                + gradle
                + "}";
    }

    /**
     * Type-check the project (see {@link EngineProtocol#COMPILE_REQUEST}). {@code profile} may be {@code null};
     * {@code offline}/{@code force}/{@code verbose} reconstruct the session config engine-side.
     */
    public static String compileRequest(
            String dir, String cache, String profile, boolean offline, boolean force, boolean verbose) {
        return compileRequest(dir, cache, profile, offline, force, verbose, List.of());
    }

    /**
     * As above with {@code moduleDirs}: the {@code -m}/{@code --affected-since} selection for the
     * workspace COMPILE path (JK-2103). Empty = the entry dir itself (member) or the whole graph
     * (workspace root).
     */
    public static String compileRequest(
            String dir,
            String cache,
            String profile,
            boolean offline,
            boolean force,
            boolean verbose,
            List<String> moduleDirs) {
        return "{\"type\":\""
                + EngineProtocol.COMPILE_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"profile\":"
                + Jsonl.quote(profile)
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + ",\"moduleDirs\":"
                + EngineProtocol.quoteArray(moduleDirs == null ? List.of() : moduleDirs)
                + "}";
    }

    /**
     * Observe dynamic surface / optional AOT cache (see {@link EngineProtocol#TRAIN_REQUEST}). {@code profile}
     * selects one {@code [[train.profile]]} or null for all; {@code graalHome} is the client-resolved
     * GraalVM home that provides the tracing agent (may be null — engine tries JAVA_HOME).
     */
    public static String trainRequest(
            String dir,
            String cache,
            String jdksDir,
            String graalHome,
            String profile,
            boolean force,
            boolean skipTests,
            boolean offline,
            boolean verbose) {
        return "{\"type\":\""
                + EngineProtocol.TRAIN_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"jdksDir\":"
                + Jsonl.quote(jdksDir)
                + ",\"graalHome\":"
                + Jsonl.quote(graalHome)
                + ",\"profile\":"
                + Jsonl.quote(profile)
                + ",\"force\":"
                + force
                + ",\"skipTests\":"
                + skipTests
                + ",\"offline\":"
                + offline
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Build native artifacts (see {@link EngineProtocol#NATIVE_REQUEST}). {@code mainClass} is the {@code --main}
     * override (may be {@code null} — the engine resolves {@code [native].main-class}/{@code
     * [image].main}/{@code [application].main} itself); {@code extraArgs} are forwarded to {@code
     * native-image}; {@code graalHomes} maps each native-eligible module dir to the GraalVM home
     * the client resolved for it (the one flat-map wire encoding — see {@code Jsonl.map}).
     */
    public static String nativeRequest(
            String dir,
            String cache,
            String jdksDir,
            String mainClass,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean verbose,
            List<String> extraArgs,
            java.util.Map<String, String> graalHomes) {
        return nativeRequest(
                dir, cache, jdksDir, mainClass, skipTests, offline, force, verbose, extraArgs, graalHomes, List.of());
    }

    /**
     * As {@link #nativeRequest(String, String, String, String, boolean, boolean, boolean, boolean,
     * List, Map)} with optional {@code moduleDirs}: when non-empty, the engine only cascades those
     * modules plus their build prereqs ({@code -m}/{@code --modules} selection).
     */
    public static String nativeRequest(
            String dir,
            String cache,
            String jdksDir,
            String mainClass,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean verbose,
            List<String> extraArgs,
            java.util.Map<String, String> graalHomes,
            List<String> moduleDirs) {
        return "{\"type\":\""
                + EngineProtocol.NATIVE_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"jdksDir\":"
                + Jsonl.quote(jdksDir)
                + ",\"mainClass\":"
                + Jsonl.quote(mainClass)
                + ",\"skipTests\":"
                + skipTests
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + ",\"extraArgs\":"
                + EngineProtocol.quoteArray(extraArgs)
                + ",\"graalHomes\":"
                + Jsonl.map(graalHomes)
                + ",\"moduleDirs\":"
                + EngineProtocol.quoteArray(moduleDirs == null ? List.of() : moduleDirs)
                + "}";
    }

    /**
     * Build + cache-install the project (see {@link EngineProtocol#INSTALL_REQUEST}). {@code m2Dir} is the
     * resolved local Maven repo root ({@code ~/.m2} or {@code --m2-dir}); {@code graalHome} is
     * non-null only for a native application (resolved client-side).
     */
    public static String installRequest(
            String dir,
            String cache,
            String m2Dir,
            String graalHome,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean verbose) {
        return "{\"type\":\""
                + EngineProtocol.INSTALL_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"m2Dir\":"
                + Jsonl.quote(m2Dir)
                + ",\"graalHome\":"
                + Jsonl.quote(graalHome)
                + ",\"skipTests\":"
                + skipTests
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Materialize a git checkout (see {@link EngineProtocol#GIT_FETCH_REQUEST}). {@code url} is the expanded
     * fetch URL, {@code canonicalUrl} its canonical identity, {@code ref} the tag-or-branch name;
     * {@code refresh} forces a re-fetch of an already-materialized ref.
     */
    public static String gitFetchRequest(
            String url, String canonicalUrl, String ref, String cache, boolean refresh, boolean requireJkToml) {
        return "{\"type\":\""
                + EngineProtocol.GIT_FETCH_REQUEST
                + "\",\"url\":"
                + Jsonl.quote(url)
                + ",\"canonicalUrl\":"
                + Jsonl.quote(canonicalUrl)
                + ",\"ref\":"
                + Jsonl.quote(ref)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"refresh\":"
                + refresh
                + ",\"requireJkToml\":"
                + requireJkToml
                + "}";
    }
}
