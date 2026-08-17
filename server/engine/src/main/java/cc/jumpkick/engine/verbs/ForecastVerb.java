// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoReads;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.runtime.BuildService;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                Path entryDir = Path.of(Jsonl.str(requestLine, "dir"));
                Path cache = Path.of(Jsonl.str(requestLine, "cache"));
                boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
                JkConfig config = new JkConfig(
                        Optional.empty(),
                        Optional.of(Jsonl.bool(requestLine, "offline", false)),
                        Optional.of(Jsonl.bool(requestLine, "rebuild", false)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(Jsonl.bool(requestLine, "force", false)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
                Session session = Session.defaults()
                        .withConfig(config)
                        .withWorkingDir(entryDir)
                        .withCacheDir(cache);
                JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve("jk.toml"));
                SessionContext.where(session, () -> {
                    BuildService.ResolvedGraph graph;
                    try {
                        graph = BuildService.resolveGraph(entryDir, entryBuild);
                    } catch (IOException e) {
                        host.sendQuiet(
                                writer,
                                ProtoReads.forecastAck(
                                        List.of(), false, false, List.of(String.valueOf(e.getMessage()))));
                        return null;
                    }
                    if (graph.hasErrors()) {
                        host.sendQuiet(writer, ProtoReads.forecastAck(List.of(), false, false, graph.errors()));
                        return null;
                    }
                    List<String> dirty = new ArrayList<>();
                    for (Path d : BuildService.forecastDirtyDirs(graph, cache, skipTests, entryDir))
                        dirty.add(d.toString());
                    boolean lockStale = BuildService.workspaceLockStale(
                            entryDir, entryBuild, cc.jumpkick.lock.LockPaths.lockFile(entryDir));
                    host.sendQuiet(writer, ProtoReads.forecastAck(dirty, lockStale, graph.isEmpty(), List.of()));
                    return null;
                });
            } catch (Exception e) {
                host.sendQuiet(
                        writer,
                        ProtoReads.forecastAck(List.of(), false, false, List.of(String.valueOf(e.getMessage()))));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }
}
