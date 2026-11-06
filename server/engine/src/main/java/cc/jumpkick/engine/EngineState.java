// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

/**
 * Where this engine stands, read by {@link EngineElection#state} from the endpoint pointer and the
 * pid file (docs/contributors/http.md, "When an engine does exit"). The exit watchdog, the session
 * write gate and {@code stop-sessions} all decide from this one answer.
 */
enum EngineState {
    /** The endpoint and the pid file name this engine. Never self-terminates; writes the sessions. */
    PRIMARY,

    /**
     * The endpoint, the pid file or a hello of the socket names another engine: a successor holds
     * the sessions. Yields HTTP, drains, writes no session and honours {@code stop-sessions}.
     */
    DISPLACED,

    /**
     * No endpoint pointer exists and nobody succeeded this engine. Keeps writing its own sessions,
     * refuses {@code stop-sessions}, and exits once unused.
     */
    ORPHANED;

    /** Only a displaced engine has a successor to hand its sessions to. */
    boolean writesSessions() {
        return this != DISPLACED;
    }
}
