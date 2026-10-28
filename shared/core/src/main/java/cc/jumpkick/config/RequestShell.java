// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Os;
import cc.jumpkick.run.ContextPropagator;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * The whole environment of the shell that ran {@code jk}, bound for the extent of the request it
 * rode. {@link BuildEnv#get} answers from it, so a name the caller left unset reads as unset rather
 * than as whatever the daemon's own shell had. A request with no shell (MCP, HTTP, a test) binds
 * nothing and {@link BuildEnv#get} falls back to the engine's environment.
 *
 * <p>Values are the caller's secrets as much as its settings: {@link #toString} names only the
 * count, and nothing journals, logs or replies with {@link #vars}.
 */
public final class RequestShell {

    private static final ScopedValue<RequestShell> BOUND = ScopedValue.newInstance();

    static {
        // Pool workers run a request's steps; without this they would see no shell, or another
        // request's.
        ContextPropagator.add(new ContextPropagator.Propagator() {
            @Override
            public Runnable wrapRunnable(Runnable r) {
                RequestShell shell = current();
                return shell == null ? r : () -> ScopedValue.where(BOUND, shell).run(r);
            }

            @Override
            public <T> Callable<T> wrapCallable(Callable<T> c) {
                RequestShell shell = current();
                return shell == null ? c : () -> ScopedValue.where(BOUND, shell).call(c::call);
            }
        });
    }

    private final Map<String, String> vars;

    private RequestShell(Map<String, String> vars) {
        // Windows names are case-insensitive: the caller's `Path` is the engine's `PATH`.
        Map<String, String> copy = Os.isWindows() ? new TreeMap<>(String.CASE_INSENSITIVE_ORDER) : new TreeMap<>();
        copy.putAll(vars);
        this.vars = Collections.unmodifiableMap(copy);
    }

    /** The shell bound to the running request, or {@code null} off a request or for one without a shell. */
    public static @Nullable RequestShell current() {
        return BOUND.isBound() ? BOUND.get() : null;
    }

    /** Run {@code body} with {@code vars} as the request's shell; a null or empty map binds nothing. */
    public static <T> T where(@Nullable Map<String, String> vars, Callable<T> body) throws Exception {
        if (vars == null || vars.isEmpty()) return body.call();
        return ScopedValue.where(BOUND, new RequestShell(vars)).call(body::call);
    }

    /** {@link #where} for a body with no result. */
    public static void runWhere(@Nullable Map<String, String> vars, Runnable body) {
        if (vars == null || vars.isEmpty()) {
            body.run();
            return;
        }
        ScopedValue.where(BOUND, new RequestShell(vars)).run(body);
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
