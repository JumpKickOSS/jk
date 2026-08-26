// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.CacheInventoryAck;
import cc.jumpkick.engine.protocol.CatalogReadAck;
import cc.jumpkick.engine.protocol.DenyReport;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.EngineWireException;
import cc.jumpkick.engine.protocol.ExecPlan;
import cc.jumpkick.engine.protocol.GeneratedFiles;
import cc.jumpkick.engine.protocol.IdeWireModel;
import cc.jumpkick.engine.protocol.ModuleGraphAck;
import cc.jumpkick.engine.protocol.NewProjectAck;
import cc.jumpkick.engine.protocol.PluginCommandReport;
import cc.jumpkick.engine.protocol.ProjectInfo;
import cc.jumpkick.engine.protocol.ProtoReads;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.engine.protocol.WhyReport;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.BuildForecast;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The engine's read-only verbs: one request line out, one {@code *-ack} line back, decoded into a
 * protocol record. No job is admitted for these — the engine answers them inline on the connection
 * thread — so there is nothing to cancel and no plan stream to replay.
 *
 * <p>{@link #request} is the whole shape, and every verb below is one call to it. The discriminator
 * is therefore matched in exactly one place: a verb cannot quietly decide that some other line type
 * is close enough to its ack, and cannot forget that an {@code error} line is a legitimate answer
 * (JK-2158 — reading to EOF instead reports a generic disconnect and loses the engine's message).
 */
final class EngineReads {

    private EngineReads() {}

    /** Decodes one matched ack line into its reply value. */
    interface AckDecoder<T> {
        T decode(String line) throws IOException;
    }

    /**
     * One synchronous request/ack exchange: write {@code requestLine}, skip stream noise until the
     * {@code ackType} line, return its decoded value. Every read-only engine verb goes through here
     * so the discriminator is matched in exactly one place.
     */
    static <T> T request(
            EnginePaths.Paths paths, String requestLine, String ackType, String what, AckDecoder<T> decoder)
            throws IOException {
        return EngineWire.stream(paths, requestLine, (reader, ch) -> {
            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (EngineProtocol.ERROR.equals(type)) {
                    // A verb that failed before producing its ack answers with an error line;
                    // surface the engine's message instead of reading to EOF and reporting a
                    // generic disconnect (JK-2158).
                    throw EngineWireException.fromJsonLine(line);
                }
                if (!ackType.equals(type)) continue;
                return decoder.decode(line);
            }
            throw new IOException("jk engine: disconnected before answering the " + what);
        });
    }

    /** One engine-hosted jk.toml edit: returns changed; throws with the engine's message. */
    static boolean edit(EnginePaths.Paths paths, Path file, String op, List<String> args) throws IOException {
        boolean changed = request(
                paths,
                ProtoReads.editRequest(file.toString(), op, args),
                EngineProtocol.EDIT_ACK,
                "edit request",
                line -> {
                    String error = Jsonl.str(line, "error");
                    if (error != null) throw new IOException(error);
                    return Jsonl.bool(line, "changed", false);
                });
        // Manifest just changed — drop memoized project summaries for this invocation (JK-2162).
        if (changed) ProjectInfos.forget();
        return changed;
    }

    static String editDetail(EnginePaths.Paths paths, Path file, String op, List<String> args) throws IOException {
        return request(
                paths,
                ProtoReads.editRequest(file.toString(), op, args),
                EngineProtocol.EDIT_ACK,
                "edit request",
                line -> {
                    String error = Jsonl.str(line, "error");
                    if (error != null) throw new IOException(error);
                    String detail = Jsonl.str(line, "detail");
                    return detail == null ? "" : detail;
                });
    }

    /** The module DAG behind {@code jk explain --graph}. */
    static ModuleGraphAck moduleGraph(
            EnginePaths.Paths paths, Path dir, String format, String modules, String affectedSince) throws IOException {
        return request(
                paths,
                ProtoReads.moduleGraphRequest(dir.toString(), format, modules, affectedSince),
                EngineProtocol.MODULE_GRAPH_ACK,
                "module-graph request",
                ModuleGraphAck::decode);
    }

    static CacheInventoryAck cacheInventory(
            EnginePaths.Paths paths,
            String query,
            Path cache,
            Path store,
            List<String> terms,
            List<String> coords,
            boolean dryRun)
            throws IOException {
        return request(
                paths,
                ProtoReads.cacheInventoryRequest(
                        query,
                        cache == null ? "" : cache.toString(),
                        store == null ? "" : store.toString(),
                        terms,
                        coords,
                        dryRun),
                EngineProtocol.CACHE_INVENTORY_ACK,
                "cache-inventory request",
                CacheInventoryAck::decode);
    }

    static CatalogReadAck catalogRead(
            EnginePaths.Paths paths,
            Path dir,
            Path cache,
            String query,
            List<String> terms,
            boolean offline,
            boolean includeCached,
            boolean bundledOnly)
            throws IOException {
        return request(
                paths,
                ProtoReads.catalogReadRequest(
                        dir == null ? "" : dir.toString(),
                        cache == null ? "" : cache.toString(),
                        query,
                        terms,
                        offline,
                        includeCached,
                        bundledOnly),
                EngineProtocol.CATALOG_READ_ACK,
                "catalog-read request",
                CatalogReadAck::decode);
    }

    /**
     * On-demand engine-hosted catalog freshen ({@code templates}/{@code libraries}/{@code jdks}) —
     * the CLI never touches these catalogs' networks itself once an engine is available. Callers
     * decide separately whether it's fine to start an engine for this ({@link
     * EngineClient#freshenCatalog}) or whether an already-running one is required ({@link
     * EngineClient#freshenCatalogIfRunning}, for {@code jk jdk install}/{@code update}'s bootstrap
     * case) — this method itself just sends the request. Best-effort: swallows the engine's error
     * rather than throwing, since the caller falls back to whatever the local cache already holds.
     */
    static void freshenCatalog(EnginePaths.Paths paths, String catalog, boolean offline, String url, String cacheFile) {
        freshenCatalog(paths, catalog, offline, url, cacheFile, false);
    }

    static void freshenCatalog(
            EnginePaths.Paths paths, String catalog, boolean offline, String url, String cacheFile, boolean force) {
        try {
            request(
                    paths,
                    ProtoReads.freshenCatalogRequest(catalog, offline, url, cacheFile, force),
                    EngineProtocol.FRESHEN_CATALOG_ACK,
                    catalog + " freshen request",
                    line -> Jsonl.bool(line, "ok", false));
        } catch (IOException ignored) {
            // Best-effort — local resolution proceeds against whatever the cache already holds.
        }
    }

    static String freshenCatalogNow(EnginePaths.Paths paths, String catalog, String url, String cacheFile)
            throws IOException {
        return request(
                paths,
                ProtoReads.freshenCatalogRequest(catalog, false, url, cacheFile, true),
                EngineProtocol.FRESHEN_CATALOG_ACK,
                catalog + " freshen request",
                line -> {
                    if (!Jsonl.bool(line, "ok", false)) {
                        String error = Jsonl.str(line, "error");
                        return error == null || error.isBlank() ? "catalog refresh failed" : error;
                    }
                    return null;
                });
    }

    /** One engine-hosted tree render: the marker-tagged tree; throws with the engine's message. */
    static String treeRender(
            EnginePaths.Paths paths, Path dir, int maxDepth, boolean flatten, boolean stack, List<String> scopes)
            throws IOException {
        return request(
                paths,
                ProtoReads.treeRequest(dir.toString(), maxDepth, flatten, stack, scopes),
                EngineProtocol.TREE_ACK,
                "tree request",
                line -> {
                    String error = Jsonl.str(line, "error");
                    if (error != null) throw new IOException(error);
                    return Jsonl.str(line, "rendered");
                });
    }

    /** One engine-hosted why lookup. */
    static WhyReport why(EnginePaths.Paths paths, Path dir, String query) throws IOException {
        return request(
                paths,
                ProtoReads.whyRequest(dir.toString(), query),
                EngineProtocol.WHY_ACK,
                "why request",
                WhyReport::decode);
    }

    /** One engine-hosted IDE model computation: the wire model back, generation stays client-side. */
    static IdeWireModel ideModel(EnginePaths.Paths paths, Path dir, Path cache, Path jdksDir) throws IOException {
        return request(
                paths,
                ProtoReads.ideModelRequest(
                        dir.toString(), cache.toString(), jdksDir == null ? null : jdksDir.toString()),
                EngineProtocol.IDE_MODEL_ACK,
                "ide-model request",
                IdeWireModel::decode);
    }

    static NewProjectAck newProject(EnginePaths.Paths paths, EngineRequests.NewProjectRequest req) throws IOException {
        return request(
                paths,
                ProtoReads.newProjectRequest(
                        req.name(),
                        req.parentDir(),
                        req.group(),
                        req.lang(),
                        req.layout(),
                        req.template(),
                        req.executable(),
                        req.jdk(),
                        req.javaRelease(),
                        req.assembly(),
                        req.nativeImage(),
                        req.plugin(),
                        req.kotlinModule(),
                        req.deps(),
                        req.sample(),
                        req.standalone(),
                        req.templateParams(),
                        req.relaxParent(),
                        req.targetDir()),
                EngineProtocol.NEW_PROJECT_ACK,
                "new-project request",
                NewProjectAck::decode);
    }

    /** One engine-hosted generator run: file payloads back, guards/writes stay client-side. */
    static GeneratedFiles generate(EnginePaths.Paths paths, Path dir, String kind, Map<String, String> params)
            throws IOException {
        return request(
                paths,
                ProtoReads.generateRequest(dir.toString(), kind, params),
                EngineProtocol.GENERATE_ACK,
                "generate request",
                GeneratedFiles::decode);
    }

    /** One engine-hosted plugin command run. */
    static PluginCommandReport pluginCommand(
            EnginePaths.Paths paths, Path dir, Path cache, String command, List<String> args) throws IOException {
        return request(
                paths,
                ProtoSession.withToolchain(
                        ProtoSession.withSession(
                                ProtoReads.pluginCommandRequest(dir.toString(), cache.toString(), command, args),
                                SessionContext.current().variant(),
                                SessionContext.current().clientEnv(),
                                SessionContext.current().jvm(),
                                SessionContext.current().config().rebuildOr(false),
                            TimelineOpts.noTimeline()),
                        SessionContext.current().jdkSpec(),
                        SessionContext.current().graalSpec()),
                EngineProtocol.PLUGIN_VERB_ACK,
                "plugin command",
                PluginCommandReport::decode);
    }

    /** One engine-hosted deny check: policy parse + lock read + violations, engine-side. */
    static DenyReport denyCheck(EnginePaths.Paths paths, Path dir) throws IOException {
        return request(
                paths,
                ProtoReads.denyCheckRequest(dir.toString()),
                EngineProtocol.DENY_CHECK_ACK,
                "deny check",
                DenyReport::decode);
    }

    static ProjectInfo projectInfo(EnginePaths.Paths paths, Path dir) throws IOException {
        return projectInfo(paths, dir, null, null, false);
    }

    static ProjectInfo projectInfo(
            EnginePaths.Paths paths, Path dir, String modules, String affectedSince, boolean counts)
            throws IOException {
        return request(
                paths,
                ProtoReads.projectInfoRequest(dir.toString(), modules, affectedSince, counts),
                EngineProtocol.PROJECT_INFO_ACK,
                "project-info request",
                ProjectInfo::decode);
    }

    static ExecPlan execPlan(
            EnginePaths.Paths paths,
            Path dir,
            Path cache,
            String kind,
            String mainOverride,
            String binName,
            Path binDir,
            Path libDir)
            throws IOException {
        return request(
                paths,
                ProtoSession.withToolchain(
                        ProtoSession.withSession(
                                ProtoReads.execPlanRequest(
                                dir.toString(),
                                cache.toString(),
                                kind,
                                mainOverride,
                                binName,
                                binDir == null ? null : binDir.toString(),
                                libDir == null ? null : libDir.toString()),
                                SessionContext.current().variant(),
                                SessionContext.current().clientEnv(),
                                SessionContext.current().jvm(),
                                SessionContext.current().config().rebuildOr(false),
                            TimelineOpts.noTimeline()),
                        SessionContext.current().jdkSpec(),
                        SessionContext.current().graalSpec()),
                EngineProtocol.EXEC_PLAN_ACK,
                "exec-plan request",
                ExecPlan::decode);
    }

    /**
     * Pre-flight a build's dirty forecast against the engine ({@code jk build}'s fully-cached
     * shortcut + dirty hint — see {@link EngineProtocol#FORECAST_REQUEST}). Synchronous: one
     * request line, one {@code forecast-ack} back. The session's offline/force/rerun flags ride
     * the request so the engine's forecast honors them exactly as the in-process one did.
     */
    static BuildForecast forecast(EnginePaths.Paths paths, Path entryDir, Path cache, boolean skipTests)
            throws IOException {
        Session session = SessionContext.current();
        return request(
                paths,
                ProtoReads.forecastRequest(
                        entryDir.toString(),
                        cache.toString(),
                        skipTests,
                        session.offline(),
                        session.force(),
                        session.config().rebuildOr(false)),
                EngineProtocol.FORECAST_ACK,
                "forecast request",
                line -> {
                    Set<Path> dirty = new LinkedHashSet<>();
                    for (String d : Jsonl.strArray(line, "dirtyDirs")) dirty.add(Path.of(d));
                    return new BuildForecast(
                            dirty,
                            Jsonl.bool(line, "lockStale", false),
                            Jsonl.bool(line, "empty", false),
                            Jsonl.strArray(line, "errors"));
                });
    }
}
