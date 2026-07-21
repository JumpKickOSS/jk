// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * Mill-shaped concurrent-work budget (JK-1082): how many module/worker units may run at once.
 *
 * <p>Semantics (same for CLI {@code -j}/{@code --jobs}, {@code [engine] jobs}, {@code JK_JOBS}):
 *
 * <ul>
 *   <li><b>absent / default</b> — all cores ({@link Runtime#availableProcessors()})
 *   <li><b>0</b> — all cores (explicit)
 *   <li><b>1</b> — serial
 *   <li><b>N &gt; 1</b> — cap at N
 * </ul>
 *
 * <p>Always resolves to a positive concurrency. Free-RAM may still reduce live worker JVMs via
 * {@code HeapPlan}/{@code PluginSlots}; this value is the <em>requested</em> ceiling.
 */
public final class Jobs {

    private Jobs() {}

    /**
     * Resolve a user/config/env spec to a positive concurrency. {@code null} or {@code 0} → cores;
     * negative → cores (invalid treated as default).
     */
    public static int effective(Integer spec) {
        return effective(spec, () -> Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    /** As {@link #effective(Integer)} with an injectable core count (tests). */
    public static int effective(Integer spec, IntSupplier cores) {
        int c = Math.max(1, cores.getAsInt());
        if (spec == null || spec == 0) return c;
        if (spec < 0) return c;
        return spec;
    }

    /**
     * Precedence: CLI value if present, else env {@code JK_JOBS}, else {@code [engine] jobs}, else
     * default (cores).
     */
    public static int resolve(Optional<Integer> cli, JkEngineConfig engine, Function<String, String> env) {
        if (cli != null && cli.isPresent()) return effective(cli.get());
        Optional<Integer> fromEnv = EnvValues.intValue(env, "JK_JOBS");
        if (fromEnv.isEmpty()) fromEnv = EnvValues.intValue(env, "JK_ENGINE_JOBS");
        if (fromEnv.isPresent()) return effective(fromEnv.get());
        if (engine != null && engine.jobs() != null) return effective(engine.jobs());
        return effective(null);
    }

    /** Machine-config only (no CLI): env + {@link JkEngineConfig#jobs()}. */
    public static int resolve(JkEngineConfig engine) {
        return resolve(Optional.empty(), engine, System::getenv);
    }
}
