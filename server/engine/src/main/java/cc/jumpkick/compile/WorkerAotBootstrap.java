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
 * PluginAot training for engine idle self-heal: pre-create {@code java-compiler-*.aot} under the
 * host HotSpot JVM so first Java builds map a cache instead of training mid-build.
 *
 * <p><strong>Only java-compiler is pre-trained.</strong> Virtually every jk project compiles Java;
 * the ToolProvider worker classpath is host-stable (no per-project BTA closure). Kotlin, Groovy,
 * and other language workers train on-demand on first real use — their classpaths are
 * version-matched to the project and a dedicated bootstrap key would either fail or retrain
 * forever as projects pin different toolchains.
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
     * Train the common (java-compiler) worker. Best-effort; never throws. {@code timeoutMs} is the
     * train budget.
     */
    public static Result trainCommonWorkers(long timeoutMs) {
        return trainCommonWorkers(timeoutMs, false);
    }

    /**
     * @param force when true, retrain even if a cache already exists
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
        trainJavaCompiler(host, timeoutMs, force, trained, skipped, notes);
        // Language workers: train-on-miss with the real project classpath (Kotlin BTA closure,
        // Groovy, …). Pre-training a thin worker jar alone fails (no compiler on -cp) or keys a
        // cache no real compile will map.
        skipped.add("kotlinc (train-on-miss on first Kotlin compile)");
        skipped.add("formatter (train-on-miss on first jk format)");
        skipped.add("groovy (not AOT-cached; GroovycDriver has no cache integration)");
        // test-runner: suite -cp always includes the module's test classes + runtime deps, so every
        // project would need its own AOT key; caches would not transfer and would thrash disk.
        skipped.add("test-runner (per-project classpath; AOT not reusable — JK-1398)");
        return new Result(trained, skipped, notes);
    }

    private static void trainJavaCompiler(
            Path host, long timeoutMs, boolean force, List<String> trained, List<String> skipped, List<String> notes) {
        try {
            Path workerJar = PluginJar.JAVA_COMPILER.locate();
            String cp = WorkerClasspath.resolve(workerJar);
            Path cache = PluginAot.cachePath("java-compiler", host, cp);
            if (!force && PluginAot.usableCache(cache)) {
                trained.add("java-compiler (cached)");
                return;
            }
            if (force && cache != null) {
                try {
                    Files.deleteIfExists(PluginAot.noaotMarker(cache));
                } catch (Exception ignored) {
                }
            }
            if (!force && cache != null && Files.exists(PluginAot.noaotMarker(cache))) {
                skipped.add("java-compiler (prior train failed; will not retry until force)");
                return;
            }
            PluginAot.TrainerCommand trainer =
                    (aotOut, scratch) -> ForkedJavac.trainerCommandForOptimize(host, cp, aotOut, scratch);
            boolean ok = PluginAot.ensureTrained("java-compiler", host, cp, trainer, timeoutMs, force);
            if (ok) {
                trained.add(force ? "java-compiler (retrained)" : "java-compiler");
                return;
            }
            skipped.add("java-compiler (ineligible host, train disabled, or train failed)");
        } catch (Exception e) {
            skipped.add("java-compiler (" + e.getMessage() + ")");
            notes.add("java-compiler: " + e.getMessage());
        }
    }
}
