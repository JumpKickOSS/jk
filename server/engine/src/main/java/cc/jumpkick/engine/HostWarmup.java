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
import java.util.function.Function;

/**
 * Engine self-heal for worker AOT caches + host calibration (install / first start / 12 h GC).
 *
 * <p>Not a user-facing command: the resident engine queues idle-boundary work when caches or
 * calibration are missing for the current HotSpot host + jk version. Disable with {@code
 * [engine] auto-warmup = false} in the user config, or {@code JK_AUTO_WARMUP=off}.
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
        return AotSettings.workerAotEnabled() && AotSettings.trainingEnabled();
    }

    /** True when java-compiler or kotlinc AOT for this host is missing (and host is eligible). */
    public static boolean needsWorkerAot() {
        if (!AotSettings.workerAotEnabled() || !AotSettings.trainingEnabled()) return false;
        Path host = JavaHomes.runningJavaHome();
        if (host == null || !Files.isDirectory(host)) return false;
        try {
            if (!workerCachePresent("java-compiler", host, PluginJar.JAVA_COMPILER)) return true;
            // kotlinc: dedicated key, or any existing kotlinc-*.aot from real compiles
            if (workerCachePresent("kotlinc", host, PluginJar.KOTLIN_COMPILER)) return false;
            if (WorkerAotBootstrap.anyToolCache("kotlinc")) return false;
            // Prior dedicated train failed (noaot) and no sibling cache — don't thrash every start.
            Path kotlincCache = cachePath("kotlinc", host, PluginJar.KOTLIN_COMPILER);
            if (kotlincCache != null && Files.exists(PluginAot.noaotMarker(kotlincCache))) return false;
            return true;
        } catch (Exception e) {
            return true;
        }
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
            Path workerJar = jar.locate();
            String cp = cc.jumpkick.compile.WorkerClasspath.resolve(workerJar);
            Path cache = PluginAot.cachePath(tool, host, cp);
            return cache != null && Files.isRegularFile(cache) && Files.size(cache) > 0;
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
     * Run worker AOT train (if needed) then calibration (if needed). Best-effort; never throws.
     * Intended for the engine idle daemon thread only.
     */
    public static void runIdle(boolean forceAot, java.util.function.Consumer<String> log) {
        if (log == null) log = s -> {};
        if (!enabled() && !forceAot) {
            log.accept("jk engine: host warmup skipped (auto-warmup disabled)");
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
                log.accept(cal.present() && cal.measured()
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
