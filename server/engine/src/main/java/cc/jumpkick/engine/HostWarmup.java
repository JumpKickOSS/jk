// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.host.Log;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.runtime.Calibration;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.StoreWriteGate;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Engine self-heal: host calibration on the idle daemon.
 *
 * <p>Not a user-facing command. Queued on the idle-boundary worker after first start and each
 * wall-clock 12 h maintenance cycle ({@link EngineMaintenance}). Disable with {@code [engine]
 * auto-warmup = false} or {@code JK_AUTO_WARMUP=off}. Store feeds and official templates are
 * {@link EngineMaintenance#runMaintenanceCycle}'s alone: it refreshes both immediately before the
 * hook that reaches this pass, so their cadence has one owner and is not behind the auto-warmup
 * switch.
 *
 * <p>The pass writes {@code state/builds/host-metrics.toml} and fetches the probe's worker jars
 * into the artifact store, both outside {@code JK_CACHE_DIR}, so a cache wipe is unaffected. After
 * a store wipe this process stands down via {@link StoreWriteGate#wipedSinceStart}.
 */
public final class HostWarmup {

    private HostWarmup() {}

    /** Whether the engine may schedule calibration. Default on; off when config or env says so. */
    public static boolean enabled() {
        return enabled(JkDirs.userConfigFile(), System::getenv);
    }

    static boolean enabled(Path userConfig, @Nullable Function<String, @Nullable String> env) {
        Optional<Boolean> fromEnv = env != null ? EnvValues.bool(env, "JK_AUTO_WARMUP") : Optional.empty();
        if (fromEnv.isPresent()) return fromEnv.get();
        return JkEngineConfig.fromToml(userConfig).autoWarmup();
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
        if (!c.present() || !c.measured()) return true;
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
        return enabled() && needsCalibration();
    }

    /** Calibration pass on the idle daemon, behind the switch. Best-effort; never throws. */
    public static void runIdle(Consumer<String> log) {
        Consumer<String> out = log == null ? s -> {} : log;
        runIdle(enabled(), List.of(() -> calibrateHost(out)));
    }

    /**
     * Composition seam: the switch is consulted once, before the first step, which is what lets a
     * test assert it without a network. A step's own failure is that step's business.
     */
    static void runIdle(boolean enabled, List<Runnable> steps) {
        if (!enabled) {
            return;
        }
        for (Runnable step : steps) {
            // A wiped store means the user just asked for it to be gone; hygiene must not put it
            // back. Checked per step so a wipe landing mid-pass stops the remainder.
            if (StoreWriteGate.wipedSinceStart()) return;
            try {
                step.run();
            } catch (Throwable e) {
                // Hygiene: one step's failure never costs the rest of the pass.
                Log.debug("runIdle: Hygiene", e);
            }
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

    private static @Nullable String hostJdkId() {
        try {
            Path home = JavaHomes.runningJavaHome();
            if (home == null) return null;
            return JdkRegistry.identifierFor(home);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
