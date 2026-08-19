// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.compile.WorkerAotBootstrap;
import cc.jumpkick.engine.plugin.PluginAot;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.runtime.Calibration;
import cc.jumpkick.util.AotSettings;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Engine self-heal: store feeds → official templates → worker AOT → host calibration.
 *
 * <p>Not a user-facing command. Queued on the idle-boundary worker after first start and each
 * wall-clock 12 h maintenance cycle ({@link EngineMaintenance}). Disable with {@code [engine]
 * auto-warmup = false} or {@code JK_AUTO_WARMUP=off}.
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

    static boolean enabled(Path userConfig, Function<String, String> env, java.util.function.BooleanSupplier aot) {
        String e = env != null ? env.apply("JK_AUTO_WARMUP") : null;
        if (e != null && !e.isBlank()) {
            String t = e.trim();
            if (isOff(t)) return false;
            if (isOn(t)) return true;
        }
        // [engine] auto-warmup = false
        try {
            var scan = cc.jumpkick.config.TomlScan.scan(userConfig, "engine.auto-warmup");
            String v = scan.get("engine.auto-warmup");
            if (v != null && isOff(v.trim())) return false;
        } catch (RuntimeException ignored) {
        }
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
        return cache == null || !Files.exists(PluginAot.noaotMarker(cache));
    }

    private static Path cachePath(String tool, Path host, PluginJar jar) {
        try {
            Path workerJar = jar.locate();
            String cp = cc.jumpkick.compile.WorkerClasspath.resolve(workerJar);
            return PluginAot.cachePath(tool, host, cp);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean workerCachePresent(String tool, Path host, PluginJar jar) {
        try {
            return PluginAot.usableCache(cachePath(tool, host, jar));
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
     * Full warmup pass on the idle daemon: store feeds + templates first, then AOT/cal if needed.
     * Best-effort; never throws. Network errors are quiet (no retries).
     */
    public static void runIdle(boolean forceAot, Consumer<String> log) {
        if (log == null) log = s -> {};
        // Always attempt cheap feed/template refresh first (etag/mtime gated; fail quiet).
        try {
            new StoreFeedRefresh(log).refreshFeedsQuietly();
        } catch (Throwable ignored) {
        }
        try {
            cc.jumpkick.templates.OfficialTemplatesFreshen.refreshQuiet(log);
        } catch (Throwable ignored) {
        }
        if (!enabled() && !forceAot) {
            return;
        }
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

    private static boolean isOff(String raw) {
        return "off".equalsIgnoreCase(raw)
                || "false".equalsIgnoreCase(raw)
                || "0".equals(raw)
                || "no".equalsIgnoreCase(raw);
    }

    private static boolean isOn(String raw) {
        return "on".equalsIgnoreCase(raw)
                || "true".equalsIgnoreCase(raw)
                || "1".equals(raw)
                || "yes".equalsIgnoreCase(raw);
    }
}
