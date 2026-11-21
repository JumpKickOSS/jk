// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.ProcessEnv;
import org.jspecify.annotations.Nullable;

/**
 * The kind of Windows logon a process runs in, which is part of the engine's identity there.
 *
 * <p>A resident engine, and every child it runs (docker, git, their credential helpers), keeps the
 * logon of the client that started it. A desktop logon reaches the user's credential vault; an ssh
 * logon, a service or a scheduled task does not, and a helper that asks for the vault there fails with
 * "A specified logon session does not exist". So on Windows a client is served only by an engine of
 * its own kind, and one home can hold one engine of each kind.
 *
 * <p>The kind is read from {@code SESSIONNAME}, which Windows sets in a desktop session ({@code
 * Console}, {@code RDP-Tcp#3}) and leaves unset under sshd, a service and a scheduled task. The engine
 * inherits it from the client that spawns it, so both sides name the same engine.
 */
public enum EngineLogon {
    /** Not Windows: one engine serves every session of the user. */
    ANY,
    /** A Windows desktop session, at the console or over remote desktop. */
    DESKTOP,
    /** Windows with no desktop session: an ssh logon, a service or a scheduled task. */
    NO_DESKTOP;

    /** The variable Windows sets in a desktop session. */
    public static final String SESSION_NAME_ENV = "SESSIONNAME";

    /** This process's kind. */
    public static EngineLogon current() {
        return of(Os.isWindows(), ProcessEnv.get(SESSION_NAME_ENV));
    }

    static EngineLogon of(boolean windows, @Nullable String sessionName) {
        if (!windows) return ANY;
        return sessionName == null || sessionName.isBlank() ? NO_DESKTOP : DESKTOP;
    }
}
