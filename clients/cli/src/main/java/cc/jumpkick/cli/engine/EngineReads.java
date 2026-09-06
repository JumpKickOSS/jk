// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.CacheInventoryAck;
import cc.jumpkick.wire.protocol.CacheInventoryRequest;
import cc.jumpkick.wire.protocol.CatalogReadAck;
import cc.jumpkick.wire.protocol.CatalogReadRequest;
import cc.jumpkick.wire.protocol.DenyCheckRequest;
import cc.jumpkick.wire.protocol.DenyReport;
import cc.jumpkick.wire.protocol.EditRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.EngineWireException;
import cc.jumpkick.wire.protocol.ExecPlan;
import cc.jumpkick.wire.protocol.ExecPlanRequest;
import cc.jumpkick.wire.protocol.ForecastRequest;
import cc.jumpkick.wire.protocol.FreshenCatalogRequest;
import cc.jumpkick.wire.protocol.GenerateRequest;
import cc.jumpkick.wire.protocol.GeneratedFiles;
import cc.jumpkick.wire.protocol.GuardFreezeAck;
import cc.jumpkick.wire.protocol.GuardFreezeRequest;
import cc.jumpkick.wire.protocol.IdeModelRequest;
import cc.jumpkick.wire.protocol.IdeWireModel;
import cc.jumpkick.wire.protocol.ModuleGraphAck;
import cc.jumpkick.wire.protocol.ModuleGraphRequest;
import cc.jumpkick.wire.protocol.NewProjectAck;
import cc.jumpkick.wire.protocol.NewProjectRequest;
import cc.jumpkick.wire.protocol.PluginCommandReport;
import cc.jumpkick.wire.protocol.PluginCommandRequest;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.protocol.ProjectInfoRequest;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.protocol.TreeRequest;
import cc.jumpkick.wire.protocol.WhyReport;
import cc.jumpkick.wire.protocol.WhyRequest;
import cc.jumpkick.wire.runtime.BuildForecast;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The engine's read-only verbs: one request line out, one {@code *-ack} line back, decoded into a
 * protocol record. No job is admitted for these — the engine answers them inline on the connection
 * thread — so there is nothing to cancel and no plan stream to replay.
 *
 * <p>{@link #request} is the whole shape, and every verb below is one call to it. The discriminator
 * is therefore matched in exactly one place: a verb cannot quietly decide that some other line type
 * is close enough to its ack, and cannot forget that an {@code error} line is a legitimate answer
 * (— reading to EOF instead reports a generic disconnect and loses the engine's message).
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
    static <T extends @Nullable Object> T request(
            EnginePaths.Paths paths, String requestLine, String ackType, String what, AckDecoder<T> decoder)
            throws IOException {
        return EngineWire.stream(paths, requestLine, (reader, ch) -> {
            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (EngineProtocol.ERROR.equals(type)) {
                    // A verb that failed before producing its ack answers with an error line;
                    // surface the engine's message instead of reading to EOF and reporting a
                    // generic disconnect.
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
                new EditRequest(file.toString(), op, args).encode(),
                EngineProtocol.EDIT_ACK,
                "edit request",
                line -> {
                    String error = Jsonl.str(line, "error");
                    if (error != null) throw new IOException(error);
                    return Jsonl.bool(line, "changed", false);
                });
        // Manifest just changed — drop memoized project summaries for this invocation.
        if (changed) ProjectInfos.forget();
        return changed;
    }

    static String editDetail(EnginePaths.Paths paths, Path file, String op, List<String> args) throws IOException {
        return request(
                paths,
                new EditRequest(file.toString(), op, args).encode(),
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
            EnginePaths.Paths paths,
            Path dir,
            @Nullable String format,
            @Nullable String modules,
            @Nullable String affectedSince)
            throws IOException {
        return request(
                paths,
                new ModuleGraphRequest(dir.toString(), format, modules, affectedSince).encode(),
                EngineProtocol.MODULE_GRAPH_ACK,
                "module-graph request",
                ModuleGraphAck::decode);
    }

    static CacheInventoryAck cacheInventory(
            EnginePaths.Paths paths,
            String query,
            Path cache,
            @Nullable Path store,
            List<String> terms,
            List<String> coords,
            boolean dryRun)
            throws IOException {
        return request(
                paths,
                new CacheInventoryRequest(
                                query,
                                cache == null ? "" : cache.toString(),
                                store == null ? "" : store.toString(),
                                terms,
                                coords,
                                dryRun)
                        .encode(),
                EngineProtocol.CACHE_INVENTORY_ACK,
                "cache-inventory request",
                CacheInventoryAck::decode);
    }

    static CatalogReadAck catalogRead(
            EnginePaths.Paths paths,
            Path dir,
            @Nullable Path cache,
            String query,
            List<String> terms,
            boolean offline,
            boolean includeCached,
            boolean bundledOnly)
            throws IOException {
        return request(
                paths,
                new CatalogReadRequest(
                                dir == null ? "" : dir.toString(),
                                cache == null ? "" : cache.toString(),
                                query,
                                terms,
                                offline,
                                includeCached,
                                bundledOnly)
                        .encode(),
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
    static void freshenCatalog(
            EnginePaths.Paths paths,
            String catalog,
            boolean offline,
            @Nullable String url,
            @Nullable String cacheFile) {
        freshenCatalog(paths, catalog, offline, url, cacheFile, false);
    }

    static void freshenCatalog(
            EnginePaths.Paths paths,
            String catalog,
            boolean offline,
            @Nullable String url,
            @Nullable String cacheFile,
            boolean force) {
        try {
            request(
                    paths,
                    new FreshenCatalogRequest(catalog, offline, url, cacheFile, force).encode(),
                    EngineProtocol.FRESHEN_CATALOG_ACK,
                    catalog + " freshen request",
                    line -> Jsonl.bool(line, "ok", false));
        } catch (IOException ignored) {
            // Best-effort — local resolution proceeds against whatever the cache already holds.
        }
    }

    static @Nullable String freshenCatalogNow(
            EnginePaths.Paths paths, String catalog, String url, @Nullable String cacheFile) throws IOException {
        return request(
                paths,
                new FreshenCatalogRequest(catalog, false, url, cacheFile, true).encode(),
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
                new TreeRequest(dir.toString(), maxDepth, flatten, stack, scopes).encode(),
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
                new WhyRequest(dir.toString(), query).encode(),
                EngineProtocol.WHY_ACK,
                "why request",
                WhyReport::decode);
    }

    /** One engine-hosted IDE model computation: the wire model back, generation stays client-side. */
    static IdeWireModel ideModel(EnginePaths.Paths paths, Path dir, Path cache, @Nullable Path jdksDir)
            throws IOException {
        return request(
                paths,
                new IdeModelRequest(dir.toString(), cache.toString(), jdksDir == null ? null : jdksDir.toString())
                        .encode(),
                EngineProtocol.IDE_MODEL_ACK,
                "ide-model request",
                IdeWireModel::decode);
    }

    static NewProjectAck newProject(EnginePaths.Paths paths, EngineRequests.NewProjectRequest req) throws IOException {
        return request(
                paths,
                new NewProjectRequest(
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
                                req.targetDir())
                        .encode(),
                EngineProtocol.NEW_PROJECT_ACK,
                "new-project request",
                NewProjectAck::decode);
    }

    /** One engine-hosted generator run: file payloads back, guards/writes stay client-side. */
    static GeneratedFiles generate(EnginePaths.Paths paths, Path dir, String kind, Map<String, String> params)
            throws IOException {
        return request(
                paths,
                new GenerateRequest(dir.toString(), kind, params).encode(),
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
                                new PluginCommandRequest(dir.toString(), cache.toString(), command, args).encode(),
                                SessionContext.current().variant(),
                                SessionContext.current().clientEnv(),
                                SessionContext.current().jvm(),
                                SessionContext.current().config().rebuildOr(false),
                                TimelineOpts.noTimeline()),
                        SessionContext.current().jdkSpec(),
                        SessionContext.current().graalSpec(),
                        SessionContext.current().graalHome() == null
                                ? null
                                : SessionContext.current().graalHome().toString()),
                EngineProtocol.PLUGIN_VERB_ACK,
                "plugin command",
                PluginCommandReport::decode);
    }

    /** One engine-hosted deny check: policy parse + lock read + violations, engine-side. */
    static GuardFreezeAck guardFreeze(
            EnginePaths.Paths paths, Path dir, String ruleId, @Nullable String reason, boolean retire)
            throws IOException {
        return request(
                paths,
                new GuardFreezeRequest(dir.toString(), ruleId, reason, retire).encode(),
                EngineProtocol.GUARD_FREEZE_ACK,
                "guard freeze",
                GuardFreezeAck::decode);
    }

    static DenyReport denyCheck(EnginePaths.Paths paths, Path dir) throws IOException {
        return request(
                paths,
                new DenyCheckRequest(dir.toString()).encode(),
                EngineProtocol.DENY_CHECK_ACK,
                "deny check",
                DenyReport::decode);
    }

    static ProjectInfo projectInfo(EnginePaths.Paths paths, Path dir) throws IOException {
        return projectInfo(paths, dir, null, null, false);
    }

    static ProjectInfo projectInfo(
            EnginePaths.Paths paths, Path dir, @Nullable String modules, @Nullable String affectedSince, boolean counts)
            throws IOException {
        return projectInfo(paths, dir, modules, affectedSince, false, counts);
    }

    static ProjectInfo projectInfo(
            EnginePaths.Paths paths,
            Path dir,
            @Nullable String modules,
            @Nullable String affectedSince,
            boolean affectedWip,
            boolean counts)
            throws IOException {
        return request(
                paths,
                // project-info used to ride bare, with no session envelope at all — so the engine
                // resolved this project's layout, test tags and toolchain against the daemon's own
                // state rather than the caller's.
                ProtoSession.withToolchain(
                        ProtoSession.withSession(
                                new ProjectInfoRequest(dir.toString(), modules, affectedSince, affectedWip, counts)
                                        .encode(),
                                SessionContext.current().variant(),
                                SessionContext.current().clientEnv(),
                                SessionContext.current().jvm(),
                                SessionContext.current().config().rebuildOr(false),
                                TimelineOpts.noTimeline()),
                        SessionContext.current().jdkSpec(),
                        SessionContext.current().graalSpec(),
                        SessionContext.current().graalHome() == null
                                ? null
                                : SessionContext.current().graalHome().toString()),
                EngineProtocol.PROJECT_INFO_ACK,
                "project-info request",
                ProjectInfo::decode);
    }

    static ExecPlan execPlan(
            EnginePaths.Paths paths,
            Path dir,
            Path cache,
            String kind,
            @Nullable String mainOverride,
            @Nullable String binName,
            @Nullable Path binDir,
            @Nullable Path libDir)
            throws IOException {
        return request(
                paths,
                ProtoSession.withToolchain(
                        ProtoSession.withSession(
                                new ExecPlanRequest(
                                                dir.toString(),
                                                cache.toString(),
                                                kind,
                                                mainOverride,
                                                binName,
                                                binDir == null ? null : binDir.toString(),
                                                libDir == null ? null : libDir.toString())
                                        .encode(),
                                SessionContext.current().variant(),
                                SessionContext.current().clientEnv(),
                                SessionContext.current().jvm(),
                                SessionContext.current().config().rebuildOr(false),
                                TimelineOpts.noTimeline()),
                        SessionContext.current().jdkSpec(),
                        SessionContext.current().graalSpec(),
                        SessionContext.current().graalHome() == null
                                ? null
                                : SessionContext.current().graalHome().toString()),
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
                new ForecastRequest(
                                entryDir.toString(),
                                cache.toString(),
                                skipTests,
                                session.offline(),
                                session.force(),
                                session.config().rebuildOr(false))
                        .encode(),
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
