// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Os;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * The whole environment of the shell that ran {@code jk}, carried by the {@link Request} it rode.
 * {@link BuildEnv#get} answers from it, so a name the caller left unset reads as unset rather
 * than as whatever the daemon's own shell had. A request with no shell (MCP, HTTP, a test) binds
 * nothing and {@link BuildEnv#get} falls back to the engine's environment.
 *
 * <p>Values are the caller's secrets as much as its settings: {@link #toString} names only the
 * count, and nothing journals, logs or replies with {@link #vars}.
 */
public final class RequestShell {

    private final Map<String, String> vars;
    private final Set<String> asked;
    private final boolean whole;
    private final @Nullable Set<String> names;

    private RequestShell(
            Map<String, String> vars, Collection<String> asked, boolean whole, @Nullable Collection<String> names) {
        // Windows names are case-insensitive: the caller's `Path` is the engine's `PATH`.
        Map<String, String> copy = Os.isWindows() ? new TreeMap<>(String.CASE_INSENSITIVE_ORDER) : new TreeMap<>();
        copy.putAll(vars);
        this.vars = Collections.unmodifiableMap(copy);
        this.asked = Collections.unmodifiableSet(nameSet(asked));
        this.whole = whole;
        this.names = names == null ? null : Collections.unmodifiableSet(nameSet(names));
    }

    private static Set<String> nameSet(Collection<String> names) {
        Set<String> out = Os.isWindows() ? new TreeSet<>(String.CASE_INSENSITIVE_ORDER) : new TreeSet<>();
        out.addAll(names);
        return out;
    }

    /** A shell of {@code vars}, or {@code null} for a null or empty map: a request with no shell. */
    public static @Nullable RequestShell of(@Nullable Map<String, String> vars) {
        return vars == null || vars.isEmpty() ? null : new RequestShell(vars, Set.of(), false, null);
    }

    /**
     * A shell of {@code vars} the client sent knowing more about it: the names it looked up, set
     * or not ({@code asked}), whether {@code vars} is its whole environment, and the names its shell
     * sets ({@code names}, null when not sent). {@code null} when it sent nothing at all.
     */
    public static @Nullable RequestShell of(
            @Nullable Map<String, String> vars,
            Collection<String> asked,
            boolean whole,
            @Nullable Collection<String> names) {
        boolean none = (vars == null || vars.isEmpty()) && asked.isEmpty() && !whole && names == null;
        return none ? null : new RequestShell(vars == null ? Map.of() : vars, asked, whole, names);
    }

    /**
     * Whether the caller's shell is known to leave {@code name} unset: {@link #vars} lacks it and the
     * client looked it up, sent its whole environment, or listed the names it sets. A name the
     * client never looked up is not known either way: a request carries only what it was asked for.
     */
    public boolean knownUnset(String name) {
        if (vars.containsKey(name)) return false;
        return whole || asked.contains(name) || (names != null && !names.contains(name));
    }

    /** Whether {@link #vars} is the caller's whole environment. */
    public boolean whole() {
        return whole;
    }

    /** The names the caller's shell sets, when the client sent them or its whole environment; else null. */
    public @Nullable Set<String> names() {
        if (whole) return vars.keySet();
        return names;
    }

    /** The shell of the running request, or {@code null} off a request or for one without a shell. */
    public static @Nullable RequestShell current() {
        Request r = Request.current();
        return r == null ? null : r.shell();
    }

    /**
     * Run {@code body} with {@code vars} as the shell of the current request, or of a request that is
     * no job off one; a null or empty map binds nothing.
     */
    public static <T> T where(@Nullable Map<String, String> vars, Callable<T> body) throws Exception {
        RequestShell shell = of(vars);
        if (shell == null) return body.call();
        return Request.currentOrDetached().withShell(shell).call(body::call);
    }

    /** {@link #where} for a body with no result. */
    public static void runWhere(@Nullable Map<String, String> vars, Runnable body) {
        RequestShell shell = of(vars);
        if (shell == null) {
            body.run();
            return;
        }
        Request.currentOrDetached().withShell(shell).run(body::run);
    }

    /** The caller's value for {@code name}, or {@code null} when its shell did not set it. */
    public @Nullable String get(String name) {
        return vars.get(name);
    }

    /** Every variable the caller's shell had. */
    public Map<String, String> vars() {
        return vars;
    }

    @Override
    public String toString() {
        return "RequestShell[" + vars.size() + " variables]";
    }
}
