// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.PluginAot;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.jdk.JavaHomes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Synchronous PluginAot training for install {@code jk optimize}: pre-create {@code
 * java-compiler-*.aot} and {@code kotlinc-*.aot} under the engine host HotSpot JVM so first user
 * builds map caches instead of training mid-build.
 */
public final class WorkerAotBootstrap {

    private WorkerAotBootstrap() {}

    public record Result(List<String> trained, List<String> skipped, List<String> notes) {
        public Result {
            trained = List.copyOf(trained);
            skipped = List.copyOf(skipped);
            notes = List.copyOf(notes);
        }
    }

    /**
     * Train common workers. Best-effort; never throws. {@code timeoutMs} is per-worker train budget.
     */
    public static Result trainCommonWorkers(long timeoutMs) {
        return trainCommonWorkers(timeoutMs, false);
    }

    /**
     * @param force when true, retrain even if a cache already exists ({@code jk optimize --force})
     */
    public static Result trainCommonWorkers(long timeoutMs, boolean force) {
        List<String> trained = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Path host = JavaHomes.runningJavaHome();
        if (host == null || !Files.isDirectory(host)) {
            notes.add("no host java.home for AOT train");
            return new Result(trained, skipped, notes);
        }
        trainOne("java-compiler", host, PluginJar.JAVA_COMPILER, timeoutMs, force, trained, skipped, notes, true);
        trainOne("kotlinc", host, PluginJar.KOTLIN_COMPILER, timeoutMs, force, trained, skipped, notes, false);
        skipped.add("test-runner (fixture tests exercise the worker; no dedicated AOT trainer yet)");
        return new Result(trained, skipped, notes);
    }

    private static void trainOne(
            String tool,
            Path host,
            PluginJar jar,
            long timeoutMs,
            boolean force,
            List<String> trained,
            List<String> skipped,
            List<String> notes,
            boolean javaCompiler) {
        try {
            Path workerJar = jar.locate();
            String cp = WorkerClasspath.resolve(workerJar);
            Path cache = PluginAot.cachePath(tool, host, cp);
            if (!force && cache != null && Files.exists(cache)) {
                trained.add(tool + " (cached)");
                return;
            }
            // Explicit optimize should retry past a prior failed-train noaot marker (lazy
            // train-on-miss keeps the marker; install/optimize is a deliberate re-attempt).
            if (cache != null) {
                try {
                    Files.deleteIfExists(Path.of(cache.toString() + ".noaot"));
                } catch (Exception ignored) {
                }
            }
            PluginAot.TrainerCommand trainer;
            if (javaCompiler) {
                trainer = (aotOut, scratch) -> ForkedJavac.trainerCommandForOptimize(host, cp, aotOut, scratch);
            } else {
                trainer = (aotOut, scratch) -> KotlincDriver.trainerCommandForOptimize(host, cp, aotOut, scratch);
            }
            boolean ok = PluginAot.ensureTrained(tool, host, cp, trainer, timeoutMs, force);
            if (ok) trained.add(force ? tool + " (retrained)" : tool);
            else skipped.add(tool + " (ineligible host, train disabled, or train failed)");
        } catch (Exception e) {
            skipped.add(tool + " (" + e.getMessage() + ")");
            notes.add(tool + ": " + e.getMessage());
        }
    }
}
