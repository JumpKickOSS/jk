// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Os;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The environment of the shell that ran {@code jk}, as a test JVM reads it: what the request
 * carried ({@link RequestShell}), then what the client answers when asked ({@link CallerAsker}).
 * A variable is {@link State#SET}, {@link State#UNSET}, or {@link State#UNKNOWN} when no one can
 * say: a build with no client attached (the web UI, MCP, a detached run), or a client that went
 * away. Unknown is never unset: a suite that read it is keyed so that no build with a caller
 * takes its result, and the test JVM reads it as unset.
 *
 * <p>Windows names are case-insensitive, as the process environment's are.
 */
public final class CallerEnv {

    /** What the caller's shell says of one variable. */
    public enum State {
        SET,
        UNSET,
        UNKNOWN
    }

    /** One variable: its state and, when set, its value. */
    public record Value(State state, @Nullable String value) {
        public static final Value UNSET = new Value(State.UNSET, null);
        public static final Value UNKNOWN = new Value(State.UNKNOWN, null);

        public static Value of(String value) {
            return new Value(State.SET, value);
        }

        @Override
        public String toString() {
            return state.name(); // never the value
        }
    }

    private final @Nullable RequestShell shell;
    private final @Nullable CallerAsker asker;

    private CallerEnv(@Nullable RequestShell shell, @Nullable CallerAsker asker) {
        this.shell = shell;
        this.asker = asker;
    }

    /** The caller of the request being served; off a request, or for one with no client, none. */
    public static CallerEnv current() {
        Request r = Request.current();
        return r == null ? new CallerEnv(null, null) : new CallerEnv(r.shell(), r.get(CallerAsker.SLOT));
    }

    /** A caller of {@code shell} that answers through {@code asker}. */
    public static CallerEnv of(@Nullable RequestShell shell, @Nullable CallerAsker asker) {
        return new CallerEnv(shell, asker);
    }

    /** Whether a client sent the request: a shell, or one to ask. */
    public boolean attached() {
        return shell != null || asker != null;
    }

    /** Whether a question to the client went unanswered: what it was not asked before is unknown. */
    public boolean lost() {
        return asker != null && asker.lost();
    }

    /** {@code name} in the caller's shell. */
    public Value get(String name) {
        return values(List.of(name)).getOrDefault(name, Value.UNKNOWN);
    }

    /** Each of {@code names} in the caller's shell; one question to the client covers every name the request lacks. */
    public Map<String, Value> values(Collection<String> names) {
        Map<String, Value> out = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            String v = shell == null ? null : shell.get(name);
            if (v != null) out.put(name, Value.of(v));
            else if (shell != null && shell.knownUnset(name)) out.put(name, Value.UNSET);
            else missing.add(name);
        }
        if (missing.isEmpty()) return out;
        Map<String, String> answered = asker == null ? null : asker.values(missing);
        Map<String, String> found = answered == null ? null : byName(answered);
        for (String name : missing) {
            if (found == null) out.put(name, Value.UNKNOWN);
            else {
                String v = found.get(name);
                out.put(name, v == null ? Value.UNSET : Value.of(v));
            }
        }
        return out;
    }

    /** The names the caller's shell sets, or {@code null} when no one can say. */
    public @Nullable Set<String> names() {
        Set<String> sent = shell == null ? null : shell.names();
        if (sent != null) return sent;
        return asker == null ? null : asker.names();
    }

    /** Every variable of the caller's shell with its value, or {@code null} when no one can say. */
    public @Nullable Map<String, String> all() {
        if (shell != null && shell.whole()) return shell.vars();
        Map<String, String> all = asker == null ? null : asker.all();
        return all == null ? null : byName(all);
    }

    private static Map<String, String> byName(Map<String, String> vars) {
        if (!Os.isWindows()) return vars;
        Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        out.putAll(vars);
        return out;
    }

    @Override
    public String toString() {
        return "CallerEnv[" + (shell == null ? "no shell" : shell) + (asker == null ? "" : ", asks") + "]";
    }
}
