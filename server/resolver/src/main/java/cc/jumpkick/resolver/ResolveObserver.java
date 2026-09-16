// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

/**
 * Receives per-package events during {@link LockOrchestrator#lock}. Implementations are called on
 * the resolver thread — must be thread-safe.
 */
public interface ResolveObserver {
    /**
     * Called when the progress denominator is known or grows (after the solver returns, or when the
     * graph estimate expands). May fire more than once.
     */
    void onTotal(int total);

    /** Called for each package as it is fetched and recorded in the lockfile. */
    void onPackage(String module, String version);

    /**
     * Cheap phase label during long lock (BOM load, graph solve, download) so the CLI/web bar is not
     * silent before materialization ticks fire.
     */
    default void onPhase(String label) {}

    /**
     * A package was chosen by the solver (before jar download). Used for normalized progress during
     * the graph phase; {@link #onPackage} still fires when the artifact is recorded/fetched.
     */
    default void onGraphPackage(String module, String version) {}

    /**
     * One transitive constraint the project's own pin overrode under {@code [resolve] pins =
     * "nearest"}, rendered as a sentence naming the pin, the parent and what it asked for. Fires
     * once per distinct override after the graphs are solved.
     */
    default void onOverride(String line) {}

    /**
     * A fact about the lock worth one line beside it, rendered as a sentence: an edge whose
     * classifier follows the host, or a repository a dependency's POM declared. Fires once per
     * distinct line after the graphs are solved.
     */
    default void onNote(String line) {}

    /** No-op observer — used when no progress tracking is needed. */
    ResolveObserver NOOP = new ResolveObserver() {
        @Override
        public void onTotal(int total) {}

        @Override
        public void onPackage(String module, String version) {}
    };
}
