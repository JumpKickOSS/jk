// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.EtaEvent;
import cc.jumpkick.wire.protocol.ExplainDoneEvent;
import cc.jumpkick.wire.protocol.ExplainEdgeEvent;
import cc.jumpkick.wire.protocol.ExplainModuleEvent;
import cc.jumpkick.wire.protocol.ExplainRequest;
import cc.jumpkick.wire.protocol.ExplainTaskEvent;
import cc.jumpkick.wire.protocol.PreflightEvent;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk explain}: send {@link EngineProtocol#EXPLAIN_REQUEST} and rebuild a real
 * {@link ExplainPlan} from the module/task/edge burst that answers it.
 *
 * <p>Not a {@link EngineReads} verb even though it is synchronous and read-only: those answer with
 * one ack line, this one answers with a burst that has to be accumulated across lines and only
 * becomes a plan at {@code explain-done}. Unlike the build stream there is no inert-object trickery
 * here — {@link TaskForecast.Module}/{@link TaskForecast.Task} are pure public data, reconstructed
 * through {@code Module.fromWire}.
 *
 * <p>What this forecast says must match what a build would then do; see
 * {@code docs/contributors/} on explain/build parity — the request below carries every
 * plan-affecting option for exactly that reason, including the engine-side ETA estimate ({@code
 * eta} event; {@code 0} = unknown) that arrives before {@code explain-done}.
 */
final class EngineExplainDecoder {

    private EngineExplainDecoder() {}

    /**
     * Forecast {@code req}'s build against the engine. {@code etaOut} (may be {@code null}) takes
     * the engine's estimates in millis: slot {@code [0]} the remaining-work ETA, and — when the
     * array has a second slot — slot {@code [1]} the full-rebuild ETA (the rebuild-effort
     * denominator). Length-guarded, so a one-slot caller still gets the plain ETA.
     */
    static ExplainPlan explain(
            EnginePaths.Paths paths,
            EngineRequests.ExplainRequest req,
            long @Nullable [] etaOut,
            @Nullable Consumer<String> onPreflightLabel)
            throws IOException {
        String request = new ExplainRequest(
                        req.entryDir().toString(),
                        req.cache().toString(),
                        req.workers(),
                        req.skipTests(),
                        req.profile(),
                        req.jdksDir() != null ? req.jdksDir().toString() : null,
                        req.serial(),
                        req.parallelTests(),
                        req.verbose(),
                        req.rebuild(),
                        req.maxModuleConcurrency(),
                        req.modules(),
                        // The session's resolved selection, same as jk build sends: it feeds every
                        // module's run-tests stamp key, and an explain that omits it forecasts a suite
                        // re-run for every module in the tree.
                        SessionContext.current().testSelection())
                .encode();
        // The same session envelope every build line carries — variant, client env, worker-JVM
        // tuning, toolchain — so the engine decodes the explain and the build to one Session.
        var session = SessionContext.current();
        String enveloped = ProtoSession.withToolchain(
                ProtoSession.withSession(
                        request,
                        session.variant(),
                        session.clientEnv(),
                        session.jvm(),
                        session.config().rebuildOr(false)),
                session);
        return EngineWire.stream(paths, enveloped, (reader, ch) -> {
            List<TaskForecast.Module> modules = new ArrayList<>();
            Map<String, List<TaskForecast.Task>> stepsByDir = new LinkedHashMap<>();
            Map<String, String> coordByDir = new LinkedHashMap<>();
            Map<String, int[]> countsByDir = new LinkedHashMap<>(); // [sourceCount, testCount]
            Map<String, boolean[]> flagsByDir = new LinkedHashMap<>(); // [producesJar, producesImage]
            Map<String, String> reasonByDir = new LinkedHashMap<>();
            List<String> order = new ArrayList<>();
            Map<Path, Set<Path>> edges = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();

            return WireStream.pumpRead(reader, (type, line) -> {
                switch (type) {
                    case EngineProtocol.EXPLAIN_MODULE -> {
                        ExplainModuleEvent e = ExplainModuleEvent.decode(line);
                        String dir = e.dir();
                        order.add(dir);
                        coordByDir.put(dir, e.coord());
                        countsByDir.put(dir, new int[] {e.sourceCount(), e.testCount()});
                        flagsByDir.put(dir, new boolean[] {e.producesJar(), e.producesImage()});
                        if (e.reason() != null) reasonByDir.put(dir, e.reason());
                        stepsByDir.put(dir, new ArrayList<>());
                    }
                    case EngineProtocol.EXPLAIN_TASK -> {
                        ExplainTaskEvent e = ExplainTaskEvent.decode(line);
                        stepsByDir
                                .computeIfAbsent(e.dir(), k -> new ArrayList<>())
                                .add(new TaskForecast.Task(
                                        e.name(), TaskForecast.Status.valueOf(e.status()), e.text(), e.key()));
                    }
                    case EngineProtocol.EXPLAIN_EDGE -> {
                        ExplainEdgeEvent e = ExplainEdgeEvent.decode(line);
                        edges.computeIfAbsent(Path.of(e.dir()), d -> new LinkedHashSet<>())
                                .add(Path.of(e.dependsOnDir()));
                    }
                    case EngineProtocol.PREFLIGHT -> {
                        // The engine is telling us what it is doing before the plan burst — e.g.
                        // the once-per-machine host probe. The client renders it, never predicts it.
                        PreflightEvent e = PreflightEvent.decode(line);
                        if (onPreflightLabel != null
                                && e.done() < e.total()
                                && !e.label().isEmpty()) {
                            onPreflightLabel.accept(e.label());
                        }
                    }
                    case EngineProtocol.ERROR -> errors.add(Jsonl.str(line, "message"));
                    case EngineProtocol.ETA -> {
                        if (etaOut != null) {
                            EtaEvent e = EtaEvent.decode(line);
                            etaOut[0] = e.remainingMs();
                            // Optional full-rebuild ETA (explain effort denominator); 0 when absent.
                            if (etaOut.length > 1) {
                                etaOut[1] = Math.max(0, e.fullMillis());
                            }
                        }
                    }
                    case EngineProtocol.EXPLAIN_DONE -> {
                        for (String dir : order) {
                            int[] counts = countsByDir.getOrDefault(dir, new int[2]);
                            boolean[] flags = flagsByDir.getOrDefault(dir, new boolean[2]);
                            modules.add(TaskForecast.Module.fromWire(
                                    Path.of(dir),
                                    coordByDir.get(dir),
                                    stepsByDir.get(dir),
                                    counts[0],
                                    counts[1],
                                    flags[0],
                                    flags[1],
                                    reasonByDir.get(dir)));
                        }
                        // An absent width is one lane here, where the record reads 0.
                        int width = Jsonl.has(line, "maxReadyWidth")
                                ? ExplainDoneEvent.decode(line).maxReadyWidth()
                                : 1;
                        return new ExplainPlan(modules, edges, width, errors);
                    }
                    default -> {
                        /* forward-compatible no-op */
                    }
                }
                return null;
            });
        });
    }
}
