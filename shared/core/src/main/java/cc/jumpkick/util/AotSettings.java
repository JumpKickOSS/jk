// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

/**
 * Process-wide switches for JEP 514 AOT caches (engine JVM + short-lived {@code PluginMain}
 * workers).
 *
 * <p><strong>Live / interactive default:</strong> training and mapping are on when eligible
 * (HotSpot 25+). Background train-on-miss keeps the resident engine and compiler workers warm.
 *
 * <p><strong>Build/test / CI:</strong> training is pure overhead when engines are short-lived or
 * nested. Suppress training with {@code JK_AOT_TRAIN=off} (or {@code -Djk.aot.train=off}). Existing
 * caches are still <em>used</em> when present unless the worker AOT kill switch is also off.
 *
 * <table>
 *   <caption>Switches</caption>
 *   <tr><th>Env / property</th><th>Effect</th></tr>
 *   <tr><td>{@code JK_AOT_TRAIN=off} / {@code jk.aot.train=off}</td>
 *       <td>No train-on-miss (engine sidecar or worker). Still map existing caches.</td></tr>
 *   <tr><td>{@code JK_WORKER_AOT=off} / {@code jk.worker.aot=off}</td>
 *       <td>Workers: no train and no map. Engine AOT is unchanged.</td></tr>
 * </table>
 */
public final class AotSettings {

    private AotSettings() {}

    /**
     * Whether a train-on-miss may start (engine sidecar or plugin-worker trainer). Default
     * {@code true}. Off when {@code JK_AOT_TRAIN}/{@code jk.aot.train} is {@code off}/{@code
     * false}/{@code 0}.
     */
    public static boolean trainingEnabled() {
        return !isOff(propOrEnv("jk.aot.train", "JK_AOT_TRAIN"));
    }

    /**
     * Whether plugin-worker AOT may map or train. Default {@code true}. Off when {@code
     * JK_WORKER_AOT}/{@code jk.worker.aot} is {@code off}/{@code false}/{@code 0}.
     */
    public static boolean workerAotEnabled() {
        return !isOff(propOrEnv("jk.worker.aot", "JK_WORKER_AOT"));
    }

    private static String propOrEnv(String prop, String env) {
        String p = System.getProperty(prop);
        if (p != null && !p.isBlank()) return p.trim();
        String e = System.getenv(env);
        return e == null ? "" : e.trim();
    }

    private static boolean isOff(String raw) {
        if (raw == null || raw.isBlank()) return false;
        return "off".equalsIgnoreCase(raw)
                || "false".equalsIgnoreCase(raw)
                || "0".equals(raw)
                || "no".equalsIgnoreCase(raw);
    }
}
