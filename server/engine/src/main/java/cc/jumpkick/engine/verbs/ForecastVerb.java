// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoReads;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.host.Errors;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.BuildService;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ForecastVerb implements HostedVerb {

    private final VerbHost host;

    public ForecastVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.FORECAST_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("forecast");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-forecast-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                Path entryDir = Path.of(Jsonl.str(requestLine, "dir"));
                Path cache = Path.of(Jsonl.str(requestLine, "cache"));
                boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
                JkConfig config = JkConfig.empty()
                        .withOffline(Jsonl.bool(requestLine, "offline", false))
                        .withRebuild(Jsonl.bool(requestLine, "rebuild", false))
                        .withForce(Jsonl.bool(requestLine, "force", false));
                Session session = Session.defaults()
                        .withConfig(config)
                        .withWorkingDir(entryDir)
                        .withCacheDir(cache)
                        // The forecast's run-tests key must equal the one the live build computes,
                        // and [test] env is part of both. Resolving it here against the daemon's
                        // environment and there against the caller's would make them disagree —
                        // "tests up-to-date" for a suite whose environment actually changed.
                        .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine));
                JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve(ManifestPaths.MANIFEST));
                SessionContext.where(session, () -> {
                    BuildService.ResolvedGraph graph;
                    try {
                        graph = BuildService.resolveGraph(entryDir, entryBuild);
                    } catch (IOException e) {
                        host.sendQuiet(
                                writer, ProtoReads.forecastAck(List.of(), false, false, List.of(Errors.text(e))));
                        return null;
                    }
                    if (graph.hasErrors()) {
                        host.sendQuiet(writer, ProtoReads.forecastAck(List.of(), false, false, graph.errors()));
                        return null;
                    }
                    List<String> dirty = new ArrayList<>();
                    // Read-only: a forecast that stored the dirty memo recreated target/.jk
                    // right after jk clean --force wiped it (JK-2205).
                    for (Path d : BuildService.forecastDirtyDirsReadOnly(graph, cache, skipTests, entryDir))
                        dirty.add(d.toString());
                    boolean lockStale =
                            BuildService.workspaceLockStale(entryDir, entryBuild, LockPaths.lockFile(entryDir));
                    host.sendQuiet(writer, ProtoReads.forecastAck(dirty, lockStale, graph.isEmpty(), List.of()));
                    return null;
                });
            } catch (Exception e) {
                host.sendQuiet(writer, ProtoReads.forecastAck(List.of(), false, false, List.of(Errors.text(e))));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
