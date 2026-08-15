// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

/**
 * Async jobs started from the embedded HTTP / MCP surface. Progress on SSE {@code
 * GET /api/events}; cancel via {@link #cancel(long)}.
 */
public interface EngineHttpJobs extends BuildTrigger {

    /** {@link #triggerBuild(String)} — dashboard/MCP default. */
    @Override
    default long trigger(String dir) {
        return triggerBuild(dir);
    }

    /** Workspace build (tests included unless the project skips them). */
    long triggerBuild(String dir);

    /**
     * True test-only workspace job ({@code testOnly} plans — compile + run tests, no package);
     * journal kind {@code test}. Prefer for agents that mean “run the suite” (same shape as {@code
     * jk test}).
     */
    long triggerTest(String dir);

    /** Resolve and write {@code jk-lock.toml} for {@code dir}. */
    long triggerLock(String dir);

    /**
     * Start a job from MCP {@code jk_run} (kind, modules, tags). Default routes to the simple
     * verbs so tests that only stub build/test/lock keep working.
     */
    default long trigger(HttpJobSpec spec) {
        String kind = spec == null ? "build" : spec.kind();
        String dir = spec == null ? "" : spec.dir();
        return switch (kind) {
            case "test" -> triggerTest(dir);
            case "lock", "update" -> triggerLock(dir);
            default -> triggerBuild(dir);
        };
    }

    /**
     * Cooperative cancel + worker grace→force for an HTTP/MCP request id. Returns {@code
     * false} if the id is unknown or already finished.
     */
    boolean cancel(long requestId);
}
