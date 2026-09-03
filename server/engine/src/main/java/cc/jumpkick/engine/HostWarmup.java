// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.compile.WorkerAotBootstrap;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.engine.plugin.PluginAot;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import cc.jumpkick.host.AotCacheFiles;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.runtime.Calibration;
import cc.jumpkick.templates.OfficialTemplatesFreshen;
import cc.jumpkick.util.AotSettings;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.StoreWriteGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Engine self-heal: store feeds → official templates → worker AOT → host calibration.
 *
 * <p>Not a user-facing command. Queued on the idle-boundary worker after first start and each
 * wall-clock 12 h maintenance cycle ({@link EngineMaintenance}). Disable with {@code [engine]
 * auto-warmup = false} or {@code JK_AUTO_WARMUP=off} — that skips the whole pass.
 * {@link EngineMaintenance#runMaintenanceCycle} still does feeds and templates on its own
 * cadence.
 *
 * <h2>What a warmup writes, and what it therefore cannot undo</h2>
 *
 * Every root this pass touches is outside {@code JK_CACHE_DIR}:
 *
 * <ul>
 *   <li><b>artifact store</b> — {@code libs.global.toml}, {@code jdks.json}, {@code templates/},
 *       and the worker jars {@code PluginJar.locate()} fetches via {@code JkStores.storeCas()}.
 *   <li><b>state</b> — {@code state/aot} ({@code PluginAot.dir()}) and
 *       {@code state/builds/host-metrics.toml}.
 * </ul>
 *
 * <p>A cache wipe is unaffected because warmup never writes there. After a store wipe, this process
 * stands down via {@link StoreWriteGate#wipedSinceStart}; the next engine may rebuild required
 * store and state entries.
 */
public final class HostWarmup {

    private HostWarmup() {}

    /**
     * Whether the engine may schedule AOT train + calibration. Default on. Off when config/env
     * says so, or when worker AOT is fully disabled.
     */
    public static boolean enabled() {
        return enabled(JkDirs.userConfigFile(), System::getenv);
    }

    static boolean enabled(Path userConfig, Function<String, String> env) {
        // Baseline: the process-wide AOT switches, including the permanent lame-duck
        // suppression a displaced engine sets. Tests inject their own baseline — the
        // suppression is JVM-global and another suite's EngineServer shutdown must not
        // flip this config/env decision order-dependently.
        return enabled(userConfig, env, () -> AotSettings.workerAotEnabled() && AotSettings.trainingEnabled());
    }

    static boolean enabled(Path userConfig, Function<String, String> env, BooleanSupplier aot) {
        Optional<Boolean> fromEnv = env != null ? EnvValues.bool(env, "JK_AUTO_WARMUP") : Optional.empty();
        if (fromEnv.isPresent()) return fromEnv.get();
        if (!JkEngineConfig.fromToml(userConfig).autoWarmup()) return false;
        // Kill-switch also covers worker train (mapping of existing caches still OK elsewhere).
        return aot.getAsBoolean();
    }

    /**
     * True when the pre-trained java-compiler AOT for this host is missing (and host is eligible).
     * Kotlin/Groovy and other language workers train on-demand — they are not part of idle warmup.
     */
    public static boolean needsWorkerAot() {
        if (!AotSettings.workerAotEnabled() || !AotSettings.trainingEnabled()) return false;
        Path host = JavaHomes.runningJavaHome();
        if (host == null || !Files.isDirectory(host)) return false;
        // Graal / pre-25 hosts can never record caches — asking for work would re-queue a
        // warmup pass every idle boundary that trainCommonWorkers then skips.
        if (!PluginAot.hostEligible(host)) return false;
        try {
            if (workerCachePresent("java-compiler", host, PluginJar.JAVA_COMPILER)) return false;
            // Sticky noaot: a permanently failing key must not re-queue warmup every cycle.
            return missingKeyNeedsTrain(cachePath("java-compiler", host, PluginJar.JAVA_COMPILER));
        } catch (Exception e) {
            return true;
        }
    }

    /** Pure decision: a missing cache still needs train unless a sticky noaot marker blocks it. */
    static boolean missingKeyNeedsTrain(Path cache) {
        return cache == null || !Files.exists(AotCacheFiles.marker(cache));
    }

    private static Path cachePath(String tool, Path host, PluginJar jar) {
        // locate() + the closure resolve fetch into the store — one gate hold for the whole leg,
        // and a stand-down once the store was wiped, exactly as WorkerAotBootstrap's trainer leg:
        // this path runs from needsWorkerAot() on the warmup decision, before any step.
        try (var held = StoreWriteGate.write()) {
            if (StoreWriteGate.wipedSinceStart()) return null;
            Path workerJar = jar.locate();
            String cp = WorkerLaunchClasspath.resolve(workerJar);
            return PluginAot.cachePath(tool, host, cp);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean workerCachePresent(String tool, Path host, PluginJar jar) {
        try {
            return AotCacheFiles.usable(cachePath(tool, host, jar));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True when host-metrics lacks a current measured probe for this jk version + host JDK
     * identity (or the stored probe is stale).
     */
    public static boolean needsCalibration() {
        return needsCalibration(System.currentTimeMillis());
    }

    static boolean needsCalibration(long nowMillis) {
        Calibration c = Calibration.load();
        if (!c.present() || !c.measured() || c.schema() < 3) return true;
        if (c.jkVersion() == null || !JkVersion.VERSION.equals(c.jkVersion())) return true;
        if (Calibration.stale(c.jkVersion(), c.updated(), nowMillis)) return true;
        String hostJdk = hostJdkId();
        if (hostJdk != null && c.jdk() != null && !c.jdk().isBlank() && !hostJdk.equals(c.jdk())) {
            return true;
        }
        return false;
    }

    /** Anything left for the idle warmup worker. */
    public static boolean needsWork() {
        return enabled() && (needsWorkerAot() || needsCalibration());
    }

    /**
     * Full warmup pass on the idle daemon: store feeds, official templates, worker AOT, host
     * calibration — in that order, all four behind the switch. Best-effort; never throws. Network
     * errors are quiet (no retries).
     */
    public static void runIdle(boolean forceAot, Consumer<String> log) {
        Consumer<String> out = log == null ? s -> {} : log;
        runIdle(
                forceAot,
                enabled(),
                List.of(
                        () -> new StoreFeedRefresh(out).refreshFeedsQuietly(),
                        () -> OfficialTemplatesFreshen.refreshQuiet(out),
                        () -> trainWorkerAot(forceAot, out),
                        () -> calibrateHost(out)));
    }

    /**
     * Composition seam, and the whole of the fix: the switch is consulted <em>before</em> the first
     * step rather than between the second and the third. A step's own failure is that step's
     * business — each is best-effort — but whether the pass runs at all is one decision, made once,
     * here, which is what lets a test assert it without a network.
     */
    static void runIdle(boolean forceAot, boolean enabled, List<Runnable> steps) {
        if (!enabled && !forceAot) {
            return;
        }
        for (Runnable step : steps) {
            // A wiped store means the user just asked for it to be gone; hygiene must not put it
            // back. Checked per step so a wipe landing mid-pass stops the remainder.
            if (StoreWriteGate.wipedSinceStart()) return;
            try {
                step.run();
            } catch (Throwable ignored) {
                // Hygiene: one step's failure never costs the rest of the pass.
            }
        }
    }

    private static void trainWorkerAot(boolean forceAot, Consumer<String> log) {
        try {
            if (forceAot || needsWorkerAot()) {
                var result = WorkerAotBootstrap.trainCommonWorkers(90_000L, forceAot);
                log.accept("jk engine: idle worker AOT trained=["
                        + String.join(", ", result.trained())
                        + "] skipped=["
                        + String.join(", ", result.skipped())
                        + "]");
            }
        } catch (RuntimeException e) {
            log.accept("jk engine: idle worker AOT failed: " + e.getMessage());
        }
    }

    private static void calibrateHost(Consumer<String> log) {
        try {
            if (needsCalibration()) {
                Calibration cal = Calibration.ensure(null, false, true);
                log.accept(
                        cal.present() && cal.measured()
                                ? "jk engine: idle host calibration saved"
                                : "jk engine: idle host calibration deferred");
            }
        } catch (RuntimeException e) {
            log.accept("jk engine: idle host calibration failed: " + e.getMessage());
        }
    }

    private static String hostJdkId() {
        try {
            Path home = JavaHomes.runningJavaHome();
            if (home == null) return null;
            return JdkRegistry.identifierFor(home);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
