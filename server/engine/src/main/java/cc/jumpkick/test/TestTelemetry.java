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

    /** The variable that turns off per-class selection's shadow alone ({@code ClassShadow}). */
    public static final String SHADOW_ENV = "JK_TEST_SHADOW";

    /**
     * The variable that has the shadow also work out what it would have run were the uses of earlier
     * classes of a JVM carried into each later class's ({@code ClassReads.attribute}); off unless set.
     */
    public static final String CARRY_ENV = "JK_TEST_SHADOW_CARRY";

    /** Whether the shadow also prices carrying uses forward ({@link #CARRY_ENV}). */
    public static boolean carry() {
        return shadow() && EnvValues.bool(BuildEnv::get, CARRY_ENV).orElse(false);
    }

    /** Whether each run also works out, in shadow, what per-class selection would have run. */
    public static boolean shadow() {
        return on() && EnvValues.bool(BuildEnv::get, SHADOW_ENV).orElse(true);
    }

    /** Whether the class telemetry is on. */
    public static boolean on() {
        return EnvValues.bool(BuildEnv::get, ENV).orElse(true);
    }
}
