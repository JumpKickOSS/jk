// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

/**
 * Async jobs started from the embedded HTTP / MCP surface (JK-1095). Progress on SSE {@code
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
     * True test-only workspace job ({@code testOnly} pipelines — compile + run tests, no package);
     * journal kind {@code test}. Prefer for agents that mean “run the suite” (same shape as {@code
     * jk test}).
     */
    long triggerTest(String dir);

    /** Resolve and write {@code jk-lock.toml} for {@code dir}. */
    long triggerLock(String dir);

    /**
     * Cooperative cancel + worker grace→force for an HTTP/MCP request id (JK-1096). Returns {@code
     * false} if the id is unknown or already finished.
     */
    boolean cancel(long requestId);
}
