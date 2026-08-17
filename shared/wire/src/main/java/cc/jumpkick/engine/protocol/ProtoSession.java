// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.plugin.protocol.Jsonl;
import java.util.List;
import java.util.Map;

/** Session envelope, tool/script/cache/history request builders. */
public final class ProtoSession {

    private ProtoSession() {}

    // ---- hosted long-tail commands -----------------------------------------------------------------

    /**
     * Resolve a Maven-published CLI tool (see {@link EngineProtocol#TOOL_RESOLVE_REQUEST}). {@code coord} is a
     * {@code ToolCoordSpec} string — pinned {@code g:a:v} or floating {@code g:a[@selector]},
     * pinned engine-side against maven-metadata. {@code with} carries {@code --with} extras (same
     * grammar, may be empty). {@code mainClass} is the {@code --main} override ({@code null} =
     * read the primary jar's manifest engine-side); {@code repoUrl} overrides Maven Central
     * ({@code null} = Central).
     */
    public static String toolResolveRequest(
            String coord, List<String> with, String bin, String mainClass, String repoUrl, String cache) {
        return "{\"type\":\""
                + EngineProtocol.TOOL_RESOLVE_REQUEST
                + "\",\"coord\":"
                + Jsonl.quote(coord)
                + ",\"with\":"
                + quoteArray(with)
                + ",\"bin\":"
                + Jsonl.quote(bin)
                + ",\"mainClass\":"
                + Jsonl.quote(mainClass)
                + ",\"repoUrl\":"
                + Jsonl.quote(repoUrl)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + "}";
    }

    /**
     * As {@link #planFinish(String, boolean)}, additionally carrying a {@link EngineProtocol#TOOL_RESOLVE_REQUEST}
     * result: the pinned {@code g:a:v} the resolve landed on (a floating spec's concrete version is
     * decided engine-side against maven-metadata), the resolved {@code Main-Class}, and the
     * transitive classpath in resolution order (absolute CAS paths — a flat string array, per the
     * codec's no-nested-objects rule). All {@code null}/empty when the resolve failed.
     */
    public static String planFinishTool(
            String dir, boolean success, String coord, String mainClass, List<String> classpath) {
        return "{\"type\":\""
                + EngineProtocol.BUILDPLAN_FINISH
                + "\",\"kind\":\"tool\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"success\":"
                + success
                + ",\"toolCoord\":"
                + Jsonl.quote(coord)
                + ",\"toolMainClass\":"
                + Jsonl.quote(mainClass)
                + ",\"toolClasspath\":"
                + quoteArray(classpath)
                + "}";
    }

    /**
     * Prepare a loose script/jar for execution (see {@link EngineProtocol#SCRIPT_PREPARE_REQUEST}). {@code
     * stateDir}/{@code repoUrl} may be {@code null} (defaults).
     */
    public static String scriptPrepareRequest(
            String mode,
            String script,
            String cache,
            String stateDir,
            String repoUrl,
            boolean forceRecompile,
            List<String> with) {
        return "{\"type\":\""
                + EngineProtocol.SCRIPT_PREPARE_REQUEST
                + "\",\"mode\":"
                + Jsonl.quote(mode)
                + ",\"with\":"
                + quoteArray(with)
                + ",\"script\":"
                + Jsonl.quote(script)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"stateDir\":"
                + Jsonl.quote(stateDir)
                + ",\"repoUrl\":"
                + Jsonl.quote(repoUrl)
                + ",\"forceRecompile\":"
                + forceRecompile
                + "}";
    }

    /**
     * As {@link #planFinish(String, boolean)}, additionally carrying a {@link
     * #EngineProtocol.SCRIPT_PREPARE_REQUEST} result: the exec ingredients the client-side launch needs. Fields
     * not applicable to the prepared mode (and everything on failure) are {@code null}/empty.
     */
    public static String planFinishScript(
            String dir,
            boolean success,
            String mainClass,
            List<String> classpath,
            String classesDir,
            String kotlincBin,
            String stdlib) {
        return "{\"type\":\""
                + EngineProtocol.BUILDPLAN_FINISH
                + "\",\"kind\":\"script\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"success\":"
                + success
                + ",\"scriptMainClass\":"
                + Jsonl.quote(mainClass)
                + ",\"scriptClasspath\":"
                + quoteArray(classpath)
                + ",\"scriptClassesDir\":"
                + Jsonl.quote(classesDir)
                + ",\"scriptKotlincBin\":"
                + Jsonl.quote(kotlincBin)
                + ",\"scriptStdlib\":"
                + Jsonl.quote(stdlib)
                + "}";
    }

    /**
     * Run a cache maintenance operation (see {@link EngineProtocol#CACHE_PRUNE_REQUEST}). {@code op} is {@code
     * prune}/{@code purge}/{@code gc}/{@code sweep}; {@code olderThanDays}/{@code sweep}/
     * {@code dropAllClassC} apply to {@code prune} only; {@code includeJkTmp} asks the prune to also
     * sweep {@code state/tmp} (only when the default cache dir is in use).
     */
    public static String cachePruneRequest(
            String op, String cache, int olderThanDays, boolean dryRun, boolean sweep, boolean includeJkTmp) {
        return cachePruneRequest(op, cache, olderThanDays, dryRun, sweep, includeJkTmp, false);
    }

    /** @param dropAllClassC when true with {@code op=prune}, delete every Class-C action key */
    public static String cachePruneRequest(
            String op,
            String cache,
            int olderThanDays,
            boolean dryRun,
            boolean sweep,
            boolean includeJkTmp,
            boolean dropAllClassC) {
        return "{\"type\":\""
                + EngineProtocol.CACHE_PRUNE_REQUEST
                + "\",\"op\":"
                + Jsonl.quote(op)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"olderThanDays\":"
                + olderThanDays
                + ",\"dryRun\":"
                + dryRun
                + ",\"sweep\":"
                + sweep
                + ",\"includeJkTmp\":"
                + includeJkTmp
                + ",\"dropAllClassC\":"
                + dropAllClassC
                + "}";
    }

    /**
     * A project-scoped cache-clear request (see {@link EngineProtocol#CACHE_PRUNE_REQUEST}, {@code op="clear"}):
     * invalidate the action-cache entries for the project at {@code projectRoot} and its workspace.
     * Reuses the maintenance channel; {@code dir} is the project root (the one spelling every
     * request uses for its location), and {@code dryRun} reports what would be removed without
     * deleting.
     */
    public static String cacheClearRequest(String cache, String projectRoot, boolean dryRun) {
        return "{\"type\":\""
                + EngineProtocol.CACHE_PRUNE_REQUEST
                + "\",\"op\":\"clear\",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"dir\":"
                + Jsonl.quote(projectRoot)
                + ",\"dryRun\":"
                + dryRun
                + "}";
    }

    /** The maintenance job is waiting for the cache to quiesce (see {@link EngineProtocol#PRUNE_WAIT}). */
    public static String pruneWait(int plans, boolean external) {
        return "{\"type\":\"" + EngineProtocol.PRUNE_WAIT + "\",\"plans\":" + plans + ",\"external\":" + external + "}";
    }

    /**
     * As {@link #planFinish(String, boolean)}, additionally carrying a {@link EngineProtocol#CACHE_PRUNE_REQUEST}
     * summary: files removed + bytes freed (what would be removed, on a dry run), the LRU evictor's
     * reachable-eviction count ({@code prune --max-size} only), and the repo-mirror links removed
     * ({@code gc} only). {@code -1} = not applicable to the op.
     */
    public static String planFinishCache(
            String dir, boolean success, long files, long bytes, long reachableEvicted, long repoLinks) {
        return "{\"type\":\""
                + EngineProtocol.BUILDPLAN_FINISH
                + "\",\"kind\":\"cache\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"success\":"
                + success
                + ",\"cacheFiles\":"
                + files
                + ",\"cacheBytes\":"
                + bytes
                + ",\"cacheReachableEvicted\":"
                + reachableEvicted
                + ",\"cacheRepoLinks\":"
                + repoLinks
                + "}";
    }

    /**
     * Append the client's flag/env JVM-tuning layer to an already-encoded request line (thin-client
     * contract: the {@code jk.toml [jvm]} table never resolves client-side — the engine overlays it
     * at worker-fork time; only {@code --ram-percent}/{@code --jvm-arg} and {@code JK_JVM_*}
     * cross the wire). A NONE tuning returns the line unchanged, so absent fields stay absent.
     */
    /**
     * Attach the session envelope — variant selection, client-resolved env values, and worker-JVM
     * tuning — to an encoded request line. The ONE attachment point for session state: every
     * hosted request rides it (so {@code --jvm-arg}/{@code JK_JVM_*} apply to every command that
     * forks workers, not an arbitrary subset), and an empty envelope leaves the line byte-
     * identical. The splice is validated: {@code request} must be a one-line encoded object.
     */
    public static String withSession(
            String request, String variant, Map<String, String> clientEnv, cc.jumpkick.config.PluginTuning t) {
        return withSession(request, variant, clientEnv, t, false, false);
    }

    /** As above, additionally carrying the session's {@code rebuild} distrust flag when set. */
    public static String withSession(
            String request,
            String variant,
            Map<String, String> clientEnv,
            cc.jumpkick.config.PluginTuning t,
            boolean rebuild) {
        return withSession(request, variant, clientEnv, t, rebuild, false);
    }

    /**
     * As above, with {@code noTimeline} (skip chrome profile write). {@code rebuild} distrusts action
     * cache; {@code noTimeline} is independent.
     */
    public static String withSession(
            String request,
            String variant,
            Map<String, String> clientEnv,
            cc.jumpkick.config.PluginTuning t,
            boolean rebuild,
            boolean noTimeline) {
        return withSession(request, variant, clientEnv, t, rebuild, noTimeline, null);
    }

    /**
     * Session envelope including optional {@code assemblyOverride} ({@code fat} / {@code minified}) for
     * {@code jk assemble --minified} one-offs.
     */
    public static String withSession(
            String request,
            String variant,
            Map<String, String> clientEnv,
            cc.jumpkick.config.PluginTuning t,
            boolean rebuild,
            boolean noTimeline,
            String assemblyOverride) {
        if (request == null
                || request.length() < 2
                || request.charAt(0) != '{'
                || request.charAt(request.length() - 1) != '}') {
            throw new IllegalArgumentException("withSession needs an encoded single-line request object");
        }
        boolean hasVariant = variant != null && !variant.isBlank();
        boolean hasEnv = clientEnv != null && !clientEnv.isEmpty();
        boolean hasJvm = t != null
                && (t.maxRamPercent() != null
                        || t.gc() != null
                        || t.stringDedup() != null
                        || !t.extraArgs().isEmpty());
        boolean hasAssembly = assemblyOverride != null && !assemblyOverride.isBlank();
        if (!hasVariant && !hasEnv && !hasJvm && !rebuild && !noTimeline && !hasAssembly) return request;
        StringBuilder b = new StringBuilder(request.substring(0, request.length() - 1));
        if (rebuild) b.append(",\"rebuild\":true");
        if (noTimeline) b.append(",\"noTimeline\":true");
        if (hasVariant) b.append(",\"variant\":").append(Jsonl.quote(variant));
        if (hasEnv) b.append(",\"env\":").append(Jsonl.map(clientEnv));
        if (hasAssembly) b.append(",\"assemblyOverride\":").append(Jsonl.quote(assemblyOverride));
        if (hasJvm) {
            if (t.maxRamPercent() != null)
                b.append(",\"jvmMaxRam\":\"").append(t.maxRamPercent()).append('\"');
            if (t.gc() != null) b.append(",\"jvmGc\":").append(Jsonl.quote(t.gc()));
            if (t.stringDedup() != null)
                b.append(",\"jvmStringDedup\":\"").append(t.stringDedup()).append('\"');
            if (!t.extraArgs().isEmpty()) b.append(",\"jvmArgs\":").append(quoteArray(t.extraArgs()));
        }
        return b.append('}').toString();
    }

    /**
     * Attach the journal-classification {@code trigger} ({@code web}, {@code optimize}, …) to an
     * encoded request line. The engine synthesizes wire lines for HTTP/MCP job submissions and
     * marks them here — same validated splice as {@link #withSession}, never call-site string
     * surgery. A null/blank trigger returns the line unchanged.
     */
    public static String withTrigger(String request, String trigger) {
        if (trigger == null || trigger.isBlank()) return request;
        if (request == null
                || request.length() < 2
                || request.charAt(0) != '{'
                || request.charAt(request.length() - 1) != '}') {
            throw new IllegalArgumentException("withTrigger needs an encoded single-line request object");
        }
        return request.substring(0, request.length() - 1) + ",\"trigger\":" + Jsonl.quote(trigger) + "}";
    }

    /** Decode {@code assemblyOverride} from a session envelope ({@code fat}/{@code minified}/empty). */
    public static String assemblyOverrideOf(String request) {
        String v = Jsonl.str(request, "assemblyOverride");
        return v == null ? "" : v;
    }

    /**
     * Append a variant selection + client-resolved env values to an encoded build request (thin
     * client: the engine folds the selection into plugin configs at parse time; env values are the
     * user's shell environment, resolved client-side for env:-indirected plugin config — signing
     * credentials — because the engine's own environment belongs to whichever invocation spawned
     * it). Nothing selected and no env → the line rides unchanged.
     */

    /** Decode side of {@link #withSession}: the selection, or {@code ""}. */
    public static String variantOf(String request) {
        String v = Jsonl.str(request, "variant");
        return v == null ? "" : v;
    }

    /** Decode side of {@link #withSession}: the client-resolved env values, or empty. */
    public static Map<String, String> clientEnvOf(String request) {
        return Jsonl.strMap(request, "env");
    }

    /** Decode side of {@link #withSession}; NONE when the request carries no tuning fields. */
    public static cc.jumpkick.config.PluginTuning jvmTuning(String request) {
        String maxRam = Jsonl.str(request, "jvmMaxRam");
        String gc = Jsonl.str(request, "jvmGc");
        String dedup = Jsonl.str(request, "jvmStringDedup");
        List<String> args = Jsonl.strArray(request, "jvmArgs");
        if (maxRam == null && gc == null && dedup == null && args.isEmpty()) {
            return cc.jumpkick.config.PluginTuning.NONE;
        }
        Double ram = null;
        try {
            if (maxRam != null) ram = Double.valueOf(maxRam);
        } catch (NumberFormatException ignored) {
            // a malformed number degrades to absent, like every tolerant config read
        }
        return new cc.jumpkick.config.PluginTuning(ram, gc, dedup == null ? null : Boolean.valueOf(dedup), args);
    }

    /** {@code Jsonl} only reads string arrays; it has no writer half, so this is the encode side. */
    static String quoteArray(List<String> values) {
        return Jsonl.array(values);
    }

    // ---- build-history request builders (responses are built engine-side with JsonOut) ----------

    public static String historyListRequest(int limit) {
        return "{\"type\":\"" + EngineProtocol.HISTORY_LIST_REQUEST + "\",\"limit\":" + limit + "}";
    }

    public static String historyShowRequest(String id) {
        return "{\"type\":\"" + EngineProtocol.HISTORY_SHOW_REQUEST + "\",\"id\":" + Jsonl.quote(id) + "}";
    }

    public static String historyDeleteRequest(String id) {
        return "{\"type\":\"" + EngineProtocol.HISTORY_DELETE_REQUEST + "\",\"id\":" + Jsonl.quote(id) + "}";
    }

    /** A metrics stream request; null/blank {@code dir} asks for every row. */
    public static String metricsRequest(String dir) {
        return dir == null || dir.isBlank()
                ? "{\"type\":\"" + EngineProtocol.METRICS_REQUEST + "\"}"
                : "{\"type\":\"" + EngineProtocol.METRICS_REQUEST + "\",\"dir\":" + Jsonl.quote(dir) + "}";
    }
}
