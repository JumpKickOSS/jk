// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import org.jspecify.annotations.Nullable;

/**
 * Which semantics one {@link LockPipeline} pass runs under. Every difference between {@code jk
 * lock}, {@code jk lock -F}, {@code jk update} and an invisible freshen (the pre-build workspace
 * guard, {@code jk sync}'s first lock, the stale-manifest auto-lock) is carried here, so the
 * pipeline that resolves and writes {@code jk-lock.toml} exists exactly once.
 */
public sealed interface LockMode {

    /**
     * Bare {@code jk lock}: the pins already on disk seed the solver as soft preferences, so only
     * the coordinates a new or changed constraint rules out move. {@code sources} additionally pins
     * each {@code -sources.jar} that the repositories publish.
     */
    record Keep(boolean sources) implements LockMode {}

    /**
     * {@code jk lock -F}: float to the latest compatible versions. {@code sources} is as in {@link
     * Keep}.
     */
    record Latest(boolean sources) implements LockMode {}

    /**
     * {@code jk update}: float everything, ignoring the maven-metadata TTL. {@code
     * platformOverride} is the CLI {@code --platform} ({@code enforced}|{@code floor}), or null to
     * take the project's {@code [resolve] platform}.
     */
    record Update(@Nullable String platformOverride) implements LockMode {}

    /**
     * A lock the user did not ask for by name — the pre-build workspace guard, {@code jk sync}'s
     * first lock, the stale-manifest auto-lock. Keeps pins like {@link Keep}, but offline it falls
     * back to solving from the warm store rather than failing the command that triggered it.
     */
    record Freshen() implements LockMode {}
}
