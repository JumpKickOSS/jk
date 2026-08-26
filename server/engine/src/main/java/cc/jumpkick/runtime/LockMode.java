// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import org.jspecify.annotations.Nullable;

/**
 * Which semantics one {@link LockPipeline} pass runs under. Every difference between {@code jk
 * lock}, {@code jk update} and an invisible freshen (the pre-build workspace guard, {@code jk
 * sync}'s first lock, the stale-manifest auto-lock) is carried here, so the pipeline that resolves
 * and writes {@code jk-lock.toml} exists exactly once.
 */
public sealed interface LockMode {

    /**
     * {@code jk lock}: float to the latest compatible versions. {@code sources} additionally pins
     * each {@code -sources.jar} that the repositories publish.
     */
    record Explicit(boolean sources) implements LockMode {}

    /**
     * {@code jk update}: float everything, ignoring the maven-metadata TTL. {@code
     * platformOverride} is the CLI {@code --platform} ({@code enforced}|{@code floor}), or null to
     * take the project's {@code [resolve] platform}.
     */
    record Update(@Nullable String platformOverride) implements LockMode {}

    /**
     * Invisible freshen: the pins already on disk seed the solver as soft preferences, so only the
     * coordinates a new or changed constraint rules out move.
     */
    record Freshen() implements LockMode {}
}
