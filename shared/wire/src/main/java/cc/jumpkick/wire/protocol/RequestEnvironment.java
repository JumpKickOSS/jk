// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.wire.runtime.progress.ProgressBarMode;
import org.jspecify.annotations.Nullable;

/**
 * What the requesting process's environment says about a build request: its trigger, the session
 * that asked, and its progress-bar mode. Read once, where the CLI builds the request — never inside
 * {@code encode()}, because the engine encodes requests too (web and MCP submissions) and its own
 * environment is not the requester's: a daemon started under {@code JK_BUILD_TRIGGER=ci} would have
 * journaled every dashboard build as {@code ci}.
 *
 * <p>A long-lived client process that is itself one session — the BSP server an IDE spawns —
 * {@link #declare declares} its trigger and session once, and every request it sends afterwards
 * carries them.
 */
public final class RequestEnvironment {

    private static final String TRIGGER_PROPERTY = "jk.build.trigger";
    private static final String SESSION_PROPERTY = "jk.build.session";

    private RequestEnvironment() {}

    /** {@code jk.build.trigger} system property, else {@code JK_BUILD_TRIGGER}; null when neither is set. */
    public static @Nullable String trigger() {
        return firstNonBlank(System.getProperty(TRIGGER_PROPERTY), ProcessEnv.get("JK_BUILD_TRIGGER"));
    }

    /**
     * The session that asked: {@code jk.build.session} system property, else {@code
     * JK_BUILD_SESSION}, else who is running this process ({@link CliSessionIdentity}: the terminal
     * tab, the agent, the script). Journaled beside the trigger so a supervisor can tell one IDE
     * window, terminal or agent from another.
     */
    public static String session() {
        String explicit = explicitSession();
        return explicit != null ? explicit : CliSessionIdentity.current().label();
    }

    /**
     * The process whose exit ends this requester's session, as {@code <pid>@<start millis>}; null
     * for an explicit session, which ends by expiry like any other, or when no leader is known.
     */
    public static @Nullable String sessionLeader() {
        return explicitSession() != null ? null : CliSessionIdentity.current().leader();
    }

    private static @Nullable String explicitSession() {
        return firstNonBlank(System.getProperty(SESSION_PROPERTY), ProcessEnv.get("JK_BUILD_SESSION"));
    }

    /** Stamp this process as {@code trigger} on behalf of {@code session} for every later request. */
    public static void declare(String trigger, @Nullable String session) {
        System.setProperty(TRIGGER_PROPERTY, trigger);
        if (session == null || session.isBlank()) System.clearProperty(SESSION_PROPERTY);
        else System.setProperty(SESSION_PROPERTY, session.trim());
    }

    /** The progress-bar mode the environment asks for, or null for the default (auto). */
    public static @Nullable String progressMode() {
        ProgressBarMode mode = ProgressBarMode.fromEnvironment();
        return mode == ProgressBarMode.AUTO ? null : mode.wireName();
    }

    private static @Nullable String firstNonBlank(@Nullable String a, @Nullable String b) {
        String v = a == null || a.isBlank() ? b : a;
        return v == null || v.isBlank() ? null : v.trim();
    }
}
