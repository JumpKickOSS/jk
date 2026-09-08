// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.workspace.BuildService;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ForecastRequest;
import cc.jumpkick.wire.protocol.ProtoReads;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            try {
                ForecastRequest req = ForecastRequest.decode(requestLine);
                Path entryDir = Path.of(req.dir());
                Path cache = Path.of(req.cache());
                boolean skipTests = req.skipTests();
                JkConfig config = JkConfig.empty()
                        .withOffline(req.offline())
                        .withRebuild(req.rebuild())
                        .withForce(req.force());
                Session session = Session.defaults()
                        .withConfig(config)
                        .withWorkingDir(entryDir)
                        .withCacheDir(cache)
                        // The guard lanes (tree, fixtures) are planned only under --guard; a forecast
                        // that did not know the flag called a gate "up to date" without them.
                        .withTestSelection(TestSelection.of(
                                List.of(), false, List.of(), List.of(), false, req.guard(), false, false))
                        // The forecast's run-tests key must equal the one the live build computes,
                        // and [test] env is part of both. Resolving it here against the daemon's
                        // environment and there against the caller's would make them disagree —
                        // "tests up-to-date" for a suite whose environment actually changed.
                        .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine))
                        // The request's toolchain selection belongs on it too: without this the SWITCH tier is
                        // empty and a resident engine ignores both --jdk and JK_JDK.
                        .withToolchainSpecs(
                                ProtoSession.jdkSpecOf(requestLine),
                                ProtoSession.graalSpecOf(requestLine),
                                ProtoSession.graalHomeOf(requestLine));
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
                    // right after jk clean --force wiped it.
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
