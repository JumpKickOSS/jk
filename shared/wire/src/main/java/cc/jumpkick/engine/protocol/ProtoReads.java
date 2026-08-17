// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import java.util.Map;

/** Explain/forecast/tree/why/generate and other sync-read builders. */
public final class ProtoReads {

    private ProtoReads() {}

    /**
     * Forecast a build (see {@link EngineProtocol#EXPLAIN_REQUEST}). Beyond the plan itself, the fields carry the
     * plan-affecting {@code jk build} options the engine-side EngineProtocol.ETA estimate needs ({@code jdksDir}/
     * {@code profile} may be {@code null}); the computed estimate rides back as an {@link EngineProtocol#ETA}
     * event inside the explain burst.
     */
    public static String moduleGraphRequest(String dir, String format, String modules, String affectedSince) {
        String extra = "";
        if (modules != null && !modules.isBlank()) extra += ",\"modules\":" + Jsonl.quote(modules);
        if (affectedSince != null && !affectedSince.isBlank()) {
            extra += ",\"affectedSince\":" + Jsonl.quote(affectedSince);
        }
        return "{\"type\":\"" + EngineProtocol.MODULE_GRAPH_REQUEST + "\",\"dir\":" + Jsonl.quote(dir)
                + ",\"format\":" + Jsonl.quote(format)
                + extra
                + "}";
    }

    public static String explainRequest(
            String dir,
            String cache,
            int workers,
            boolean skipTests,
            String profile,
            String jdksDir,
            boolean serial,
            boolean parallelTests,
            boolean verbose) {
        return explainRequest(dir, cache, workers, skipTests, profile, jdksDir, serial, parallelTests, verbose, false);
    }

    /**
     * As {@link #explainRequest(String, String, int, boolean, String, String, boolean, boolean, boolean)}
     * with {@code rebuild} — when true, forecast/EngineProtocol.ETA match {@code jk build --redo}.
     */
    public static String explainRequest(
            String dir,
            String cache,
            int workers,
            boolean skipTests,
            String profile,
            String jdksDir,
            boolean serial,
            boolean parallelTests,
            boolean verbose,
            boolean rebuild) {
        return explainRequest(
                dir,
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

    /**
     * As above with {@code maxModuleConcurrency} ({@code -j} / jobs) so explain and build clamp
     * schedule concurrency the same way.
     */
    public static String explainRequest(
            String dir,
            String cache,
            int workers,
            boolean skipTests,
            String profile,
            String jdksDir,
            boolean serial,
            boolean parallelTests,
            boolean verbose,
            boolean rebuild,
            int maxModuleConcurrency) {
        return "{\"type\":\""
                + EngineProtocol.EXPLAIN_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"workers\":"
                + workers
                + ",\"skipTests\":"
                + skipTests
                + ",\"profile\":"
                + Jsonl.quote(profile)
                + ",\"jdksDir\":"
                + Jsonl.quote(jdksDir)
                + ",\"serial\":"
                + serial
                + ",\"parallelTests\":"
                + parallelTests
                + ",\"verbose\":"
                + verbose
                + ",\"rebuild\":"
                + rebuild
                + ",\"maxModuleConcurrency\":"
                + maxModuleConcurrency
                + "}";
    }

    // ---- explain events (server → client) --------------------------------------------------------

    public static String explainModule(
            String dir, String coord, int sourceCount, int testCount, boolean producesJar, boolean producesImage) {
        return "{\"type\":\""
                + EngineProtocol.EXPLAIN_MODULE
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"coord\":"
                + Jsonl.quote(coord)
                + ",\"sourceCount\":"
                + sourceCount
                + ",\"testCount\":"
                + testCount
                + ",\"producesJar\":"
                + producesJar
                + ",\"producesImage\":"
                + producesImage
                + "}";
    }

    public static String explainStep(String dir, String name, String status, String text, String key) {
        return "{\"type\":\""
                + EngineProtocol.EXPLAIN_TASK
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"name\":"
                + Jsonl.quote(name)
                + ",\"status\":"
                + Jsonl.quote(status)
                + ",\"text\":"
                + Jsonl.quote(text)
                + ",\"key\":"
                + Jsonl.quote(key)
                + "}";
    }

    public static String explainEdge(String dir, String dependsOnDir) {
        return "{\"type\":\""
                + EngineProtocol.EXPLAIN_EDGE
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"dependsOnDir\":"
                + Jsonl.quote(dependsOnDir)
                + "}";
    }

    public static String forecastRequest(
            String dir, String cache, boolean skipTests, boolean offline, boolean force, boolean rerun) {
        return "{\"type\":\""
                + EngineProtocol.FORECAST_REQUEST
                + "\",\"dir\":"
                + Jsonl.quote(dir)
                + ",\"cache\":"
                + Jsonl.quote(cache)
                + ",\"skipTests\":"
                + skipTests
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"rebuild\":"
                + rerun
                + "}";
    }

    public static String treeRequest(String dir, int maxDepth, boolean flatten, boolean stack, List<String> scopes) {
        return "{\"type\":\"" + EngineProtocol.TREE_REQUEST + "\",\"dir\":" + Jsonl.quote(dir)
                + ",\"maxDepth\":" + maxDepth
                + ",\"flatten\":" + flatten
                + ",\"stack\":" + stack
                + ",\"scopes\":" + EngineProtocol.quoteArray(scopes)
                + "}";
    }

    public static String treeAck(String error, String rendered) {
        return "{\"type\":\"" + EngineProtocol.TREE_ACK + "\",\"error\":" + Jsonl.quote(error)
                + ",\"rendered\":" + Jsonl.quote(rendered == null ? "" : rendered)
                + "}";
    }

    public static String whyRequest(String dir, String query) {
        return "{\"type\":\"" + EngineProtocol.WHY_REQUEST + "\",\"dir\":" + Jsonl.quote(dir) + ",\"query\":"
                + Jsonl.quote(query) + "}";
    }

    public static String ideModelRequest(String dir, String cache, String jdksDir) {
        return "{\"type\":\"" + EngineProtocol.IDE_MODEL_REQUEST + "\",\"dir\":" + Jsonl.quote(dir)
                + ",\"cache\":" + Jsonl.quote(cache)
                + ",\"jdksDir\":" + Jsonl.quote(jdksDir)
                + "}";
    }

    public static String generateRequest(String dir, String kind) {
        return generateRequest(dir, kind, Map.of());
    }

    /** As above with generator parameters (scaffold inputs etc.) as a flat map. */
    public static String generateRequest(String dir, String kind, Map<String, String> params) {
        return "{\"type\":\"" + EngineProtocol.GENERATE_REQUEST + "\",\"dir\":" + Jsonl.quote(dir)
                + ",\"kind\":" + Jsonl.quote(kind)
                + ",\"params\":" + Jsonl.map(params)
                + "}";
    }

    /** Decode side of {@link #generateRequest(String, String, java.util.Map)}. */
    public static Map<String, String> generateParams(String requestLine) {
        return Jsonl.strMap(requestLine, "params");
    }

    /** Scaffold a project (CLI {@code jk new}, HTTP, MCP). */
    public static String newProjectRequest(
            String name,
            String parentDir,
            String group,
            String lang,
            String layout,
            String template,
            boolean executable,
            String framework,
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
        return "{\"type\":\"" + EngineProtocol.NEW_PROJECT_REQUEST + "\""
                + ",\"name\":" + Jsonl.quote(name)
                + ",\"parentDir\":" + Jsonl.quote(parentDir)
                + ",\"group\":" + Jsonl.quote(group)
                + ",\"lang\":" + Jsonl.quote(lang)
                + ",\"layout\":" + Jsonl.quote(layout)
                + ",\"template\":" + Jsonl.quote(template)
                + ",\"executable\":" + executable
                + ",\"framework\":" + Jsonl.quote(framework)
                + ",\"jdk\":" + Jsonl.quote(jdk)
                + ",\"javaRelease\":" + javaRelease
                + ",\"assembly\":" + assembly
                + ",\"nativeImage\":" + nativeImage
                + ",\"plugin\":" + plugin
                + ",\"kotlinModule\":" + Jsonl.quote(kotlinModule)
                + ",\"deps\":" + EngineProtocol.quoteArray(deps == null ? List.of() : deps)
                + ",\"sample\":" + sample
                + ",\"standalone\":" + standalone
                + ",\"templateParams\":" + Jsonl.map(templateParams == null ? Map.of() : templateParams)
                + ",\"relaxParent\":" + relaxParent
                + "}";
    }

    public static String pluginInstallLocalRequest(
            String dir, String cache, String installRoot, String modules, boolean dryRun, boolean ambientStore) {
        return "{\"type\":\"" + EngineProtocol.PLUGIN_INSTALL_LOCAL_REQUEST + "\",\"dir\":" + Jsonl.quote(dir)
                + ",\"cache\":" + Jsonl.quote(cache)
                + ",\"installRoot\":" + Jsonl.quote(installRoot)
                + ",\"modules\":" + Jsonl.quote(modules)
                + ",\"dryRun\":" + dryRun
                + ",\"ambientStore\":" + ambientStore
                + "}";
    }

    public static String pluginCommandRequest(String dir, String cache, String command, List<String> args) {
        return "{\"type\":\"" + EngineProtocol.PLUGIN_VERB_REQUEST + "\",\"dir\":" + Jsonl.quote(dir)
                + ",\"cache\":" + Jsonl.quote(cache)
                + ",\"command\":" + Jsonl.quote(command)
                + ",\"args\":" + EngineProtocol.quoteArray(args)
                + "}";
    }

    public static String denyCheckRequest(String dir) {
        return "{\"type\":\"" + EngineProtocol.DENY_CHECK_REQUEST + "\",\"dir\":" + Jsonl.quote(dir) + "}";
    }

    public static String editRequest(String file, String op, List<String> args) {
        return "{\"type\":\"" + EngineProtocol.EDIT_REQUEST + "\",\"file\":" + Jsonl.quote(file)
                + ",\"op\":" + Jsonl.quote(op)
                + ",\"args\":" + EngineProtocol.quoteArray(args) + "}";
    }

    public static String editAck(boolean changed, String error) {
        return editAck(changed, error, "");
    }

    public static String editAck(boolean changed, String error, String detail) {
        return "{\"type\":\"" + EngineProtocol.EDIT_ACK + "\",\"changed\":" + changed + ",\"error\":"
                + Jsonl.quote(error)
                + ((detail == null || detail.isBlank()) ? "" : ",\"detail\":" + Jsonl.quote(detail))
                + "}";
    }

    public static String freshenCatalogRequest(String catalog, boolean offline, String url, String cacheFile) {
        return "{\"type\":\"" + EngineProtocol.FRESHEN_CATALOG_REQUEST + "\",\"catalog\":" + Jsonl.quote(catalog)
                + ",\"offline\":" + offline
                + ",\"url\":" + Jsonl.quote(url)
                + ",\"cacheFile\":" + Jsonl.quote(cacheFile)
                + "}";
    }

    public static String freshenCatalogAck(boolean ok, String error) {
        return "{\"type\":\"" + EngineProtocol.FRESHEN_CATALOG_ACK + "\",\"ok\":" + ok + ",\"error\":"
                + Jsonl.quote(error) + "}";
    }

    /**
     * Catalog list/search. {@code query} is {@code list} or {@code search}; {@code terms} apply to
     * search only. {@code bundledOnly} skips project/global layers (wizard picker).
     */
    public static String cacheInventoryRequest(
            String query, String cache, String store, List<String> terms, List<String> coords, boolean dryRun) {
        return "{\"type\":\"" + EngineProtocol.CACHE_INVENTORY_REQUEST + "\",\"query\":" + Jsonl.quote(query)
                + ",\"cache\":" + Jsonl.quote(cache)
                + ",\"store\":" + Jsonl.quote(store)
                + ",\"terms\":" + EngineProtocol.quoteArray(terms == null ? List.of() : terms)
                + ",\"coords\":" + EngineProtocol.quoteArray(coords == null ? List.of() : coords)
                + ",\"dryRun\":" + dryRun
                + "}";
    }

    public static String catalogReadRequest(
            String dir,
            String cache,
            String query,
            List<String> terms,
            boolean offline,
            boolean includeCached,
            boolean bundledOnly) {
        return "{\"type\":\"" + EngineProtocol.CATALOG_READ_REQUEST + "\",\"dir\":" + Jsonl.quote(dir)
                + ",\"cache\":" + Jsonl.quote(cache)
                + ",\"query\":" + Jsonl.quote(query)
                + ",\"terms\":" + EngineProtocol.quoteArray(terms == null ? List.of() : terms)
                + ",\"offline\":" + offline
                + ",\"includeCached\":" + includeCached
                + ",\"bundledOnly\":" + bundledOnly
                + "}";
    }

    public static String projectInfoRequest(String dir, String cache) {
        return projectInfoRequest(dir, cache, null, null);
    }

    /**
     * As {@link #projectInfoRequest(String, String)} with optional {@code -m}/{@code
     * --affected-since} filters (omitted when blank).
     */
    public static String projectInfoRequest(String dir, String cache, String modules, String affectedSince) {
        String extra = "";
        if (modules != null && !modules.isBlank()) extra += ",\"modules\":" + Jsonl.quote(modules);
        if (affectedSince != null && !affectedSince.isBlank()) {
            extra += ",\"affectedSince\":" + Jsonl.quote(affectedSince);
        }
        return "{\"type\":\"" + EngineProtocol.PROJECT_INFO_REQUEST + "\",\"dir\":" + Jsonl.quote(dir) + ",\"cache\":"
                + Jsonl.quote(cache)
                + extra
                + "}";
    }

    public static String outdatedRequest(String dir, String cache, String repoUrl, boolean offline, boolean force) {
        return "{\"type\":\"" + EngineProtocol.OUTDATED_REQUEST + "\",\"dir\":" + Jsonl.quote(dir)
                + ",\"cache\":" + Jsonl.quote(cache)
                + ",\"repoUrl\":" + Jsonl.quote(repoUrl)
                + ",\"offline\":" + offline
                + ",\"force\":" + force
                + "}";
    }

    public static String execPlanRequest(
            String dir, String cache, String kind, String mainOverride, String binName, String binDir, String libDir) {
        return "{\"type\":\"" + EngineProtocol.EXEC_PLAN_REQUEST + "\",\"dir\":" + Jsonl.quote(dir)
                + ",\"cache\":" + Jsonl.quote(cache)
                + ",\"kind\":" + Jsonl.quote(kind)
                + ",\"mainOverride\":" + Jsonl.quote(mainOverride)
                + ",\"binName\":" + Jsonl.quote(binName)
                + ",\"binDir\":" + Jsonl.quote(binDir)
                + ",\"libDir\":" + Jsonl.quote(libDir)
                + "}";
    }

    public static String forecastAck(List<String> dirtyDirs, boolean lockStale, boolean empty, List<String> errors) {
        return "{\"type\":\""
                + EngineProtocol.FORECAST_ACK
                + "\",\"dirtyDirs\":"
                + EngineProtocol.quoteArray(dirtyDirs)
                + ",\"lockStale\":"
                + lockStale
                + ",\"empty\":"
                + empty
                + ",\"errors\":"
                + EngineProtocol.quoteArray(errors)
                + "}";
    }

    public static String explainDone(int maxReadyWidth, int moduleCount) {
        return "{\"type\":\""
                + EngineProtocol.EXPLAIN_DONE
                + "\",\"maxReadyWidth\":"
                + maxReadyWidth
                + ",\"moduleCount\":"
                + moduleCount
                + "}";
    }
}
