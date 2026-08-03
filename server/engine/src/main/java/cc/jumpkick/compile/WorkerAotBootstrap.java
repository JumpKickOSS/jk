// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.PluginAot;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.jdk.JavaHomes;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * PluginAot training for install {@code jk optimize}: pre-create {@code java-compiler-*.aot} and
 * {@code kotlinc-*.aot} under the engine host HotSpot JVM so first user builds map caches instead
 * of training mid-build. Invoked from the engine idle-boundary worker (not the request thread).
 *
 * <p>Pins training to the <em>current</em> shipping plugin classpaths (latest Kotlin/Groovy
 * workers). Older language versions train on-demand via PluginAot train-on-miss.
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
        // test-runner: forked via PluginLoader without PluginAot keys today (suite JVM also sets
        // -Djk.aot.train=off). Fixture `jk test` warms process/JIT only — no dedicated trainer (JK-1398).
        skipped.add("test-runner (no PluginAot path; fixtures warm the process only)");
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
                    Files.deleteIfExists(PluginAot.noaotMarker(cache));
                } catch (Exception ignored) {
                }
            }
            // kotlinc: if fixture builds already produced any kotlinc-*.aot for this host, count as
            // success even when the dedicated bootstrap key still fails (JK-1397).
            if (!force && "kotlinc".equals(tool) && anyToolCache("kotlinc")) {
                trained.add(tool + " (cached)");
                return;
            }
            PluginAot.TrainerCommand trainer;
            if (javaCompiler) {
                trainer = (aotOut, scratch) -> ForkedJavac.trainerCommandForOptimize(host, cp, aotOut, scratch);
            } else {
                trainer = (aotOut, scratch) -> KotlincDriver.trainerCommandForOptimize(host, cp, aotOut, scratch);
            }
            boolean ok = PluginAot.ensureTrained(tool, host, cp, trainer, timeoutMs, force);
            if (ok) {
                trained.add(force ? tool + " (retrained)" : tool);
                return;
            }
            // After a failed dedicated train, still accept sibling caches from real compiles.
            if ("kotlinc".equals(tool) && anyToolCache("kotlinc")) {
                trained.add(tool + " (cached)");
                // Clear sticky noaot on the dedicated key so map path can retry later.
                if (cache != null) {
                    try {
                        Files.deleteIfExists(PluginAot.noaotMarker(cache));
                    } catch (Exception ignored) {
                    }
                }
                notes.add("kotlinc: dedicated bootstrap key missed; using existing kotlinc-*.aot from compiles");
                return;
            }
            skipped.add(tool + " (ineligible host, train disabled, or train failed)");
        } catch (Exception e) {
            skipped.add(tool + " (" + e.getMessage() + ")");
            notes.add(tool + ": " + e.getMessage());
        }
    }

    /** True when any {@code <tool>-*.aot} file exists under the PluginAot dir (complete caches only). */
    static boolean anyToolCache(String tool) {
        if (tool == null || tool.isBlank()) return false;
        Path dir = PluginAot.dir();
        if (!Files.isDirectory(dir)) return false;
        String prefix = tool + "-";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, prefix + "*.aot")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.endsWith(".aot") && !name.contains(".tmp-") && Files.size(p) > 0) return true;
            }
        } catch (IOException ignored) {
        }
        // Fallback walk for odd FS implementations
        try (Stream<Path> walk = Files.list(dir)) {
            return walk.anyMatch(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(prefix) && n.endsWith(".aot") && !n.contains(".tmp-");
            });
        } catch (IOException e) {
            return false;
        }
    }
}
