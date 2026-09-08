// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.EtaEvent;
import cc.jumpkick.wire.protocol.ExplainRequest;
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
    static ExplainPlan explain(EnginePaths.Paths paths, EngineRequests.ExplainRequest req, long @Nullable [] etaOut)
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
                        // The session's resolved selection, same as jk build sends: it feeds every
                        // module's run-tests stamp key, and an explain that omits it forecasts a suite
                        // re-run for every module in the tree.
                        SessionContext.current().testSelection())
                .encode();
        return EngineWire.stream(paths, request, (reader, ch) -> {
            List<TaskForecast.Module> modules = new ArrayList<>();
            Map<String, List<TaskForecast.Task>> stepsByDir = new LinkedHashMap<>();
            Map<String, String> coordByDir = new LinkedHashMap<>();
            Map<String, int[]> countsByDir = new LinkedHashMap<>(); // [sourceCount, testCount]
            Map<String, boolean[]> flagsByDir = new LinkedHashMap<>(); // [producesJar, producesImage]
            List<String> order = new ArrayList<>();
            Map<Path, Set<Path>> edges = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();

            return WireStream.pumpRead(reader, (type, line) -> {
                switch (type) {
                    case EngineProtocol.EXPLAIN_MODULE -> {
                        String dir = Jsonl.str(line, "dir");
                        order.add(dir);
                        coordByDir.put(dir, Jsonl.str(line, "coord"));
                        countsByDir.put(dir, new int[] {
                            Jsonl.intValue(line, "sourceCount", 0), Jsonl.intValue(line, "testCount", 0)
                        });
                        flagsByDir.put(dir, new boolean[] {
                            Jsonl.bool(line, "producesJar", false), Jsonl.bool(line, "producesImage", false)
                        });
                        stepsByDir.put(dir, new ArrayList<>());
                    }
                    case EngineProtocol.EXPLAIN_TASK -> {
                        String dir = Jsonl.str(line, "dir");
                        stepsByDir
                                .computeIfAbsent(dir, k -> new ArrayList<>())
                                .add(new TaskForecast.Task(
                                        Jsonl.str(line, "name"),
                                        TaskForecast.Status.valueOf(Jsonl.str(line, "status")),
                                        Jsonl.str(line, "text"),
                                        Jsonl.str(line, "key")));
                    }
                    case EngineProtocol.EXPLAIN_EDGE -> {
                        Path dir = Path.of(Jsonl.str(line, "dir"));
                        Path dependsOn = Path.of(Jsonl.str(line, "dependsOnDir"));
                        edges.computeIfAbsent(dir, d -> new LinkedHashSet<>()).add(dependsOn);
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
                                    flags[1]));
                        }
                        return new ExplainPlan(modules, edges, Jsonl.intValue(line, "maxReadyWidth", 1), errors);
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
