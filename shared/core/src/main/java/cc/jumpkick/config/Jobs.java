// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Mill-shaped concurrent-work budgethow many module/worker units may run at once.
 *
 * <p>Semantics (same for CLI {@code -j}/{@code --jobs}, {@code [engine] jobs}, {@code JK_JOBS}):
 *
 * <ul>
 * <li><b>absent / default</b> — all effective cores ({@link AvailableCpus#count})
 * <li><b>0</b> — all effective cores (explicit)
 * <li><b>1</b> — serial
 * <li><b>N &gt; 1</b> — cap at N
 * </ul>
 *
 * <p>{@link AvailableCpus} prefers cgroup CPU quota (containers) over a bare host
 * {@link Runtime#availableProcessors} when the quota is readable.
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
    public static int effective(@Nullable Integer spec) {
        return effective(spec, AvailableCpus::count);
    }

    /** As {@link #effective(Integer)} with an injectable core count (tests). */
    public static int effective(@Nullable Integer spec, IntSupplier cores) {
        int c = Math.max(1, cores.getAsInt());
        if (spec == null || spec == 0) return c;
        if (spec < 0) return c;
        return spec;
    }

    /**
     * Precedence: CLI value if present, else env {@code JK_JOBS}, else {@code [engine] jobs}, else
     * default (cores).
     */
    public static int resolve(Optional<Integer> cli, JkEngineConfig engine, Function<String, @Nullable String> env) {
        if (cli != null && cli.isPresent()) return effective(cli.get());
        Optional<Integer> fromEnv = EnvValues.intValue(env, "JK_JOBS");
        if (fromEnv.isPresent()) return effective(fromEnv.get());
        if (engine != null && engine.jobs() != null) return effective(engine.jobs());
        return effective(null);
    }

    /** Machine-config only (no CLI): env + {@link JkEngineConfig#jobs()}. */
    public static int resolve(JkEngineConfig engine) {
        return resolve(Optional.empty(), engine, System::getenv);
    }
}
