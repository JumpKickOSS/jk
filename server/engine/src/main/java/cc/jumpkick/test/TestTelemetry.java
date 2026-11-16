// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.EnvValues;

/**
 * Whether test JVMs record what each test class did: the class windows, native libraries loaded and
 * system properties read, and the class records the engine distils from them. On unless the shell
 * running {@code jk} sets {@code JK_TEST_TELEMETRY=off} (or {@code 0}, {@code false}, {@code no}), which leaves the recording that keys the
 * suite as it was: the switch the overhead of the rest is measured against.
 */
public final class TestTelemetry {

    private TestTelemetry() {}

    /** The engine environment variable that turns the class telemetry off. */
    public static final String ENV = "JK_TEST_TELEMETRY";

    /** Whether the class telemetry is on. */
    public static boolean on() {
        return EnvValues.bool(BuildEnv::get, ENV).orElse(true);
    }
}
