// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import cc.jumpkick.run.ContextPropagator;
import java.util.Map;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * What the {@code jk} command that sent the request being served read from the keychain: each
 * item's secret, or why the keychain did not give it up, keyed by {@link SecretVault.Pointer#text()}
 * and bound for the extent of that request.
 *
 * <p>A resident engine keeps the session of whichever shell started it, and the command may run
 * in another, so the command reads every item its logins point at and sends the answers, the way
 * {@code JK_REPO_*} variables ride the request's shell. {@link SecretVault#resolve} answers from
 * here first and never asks the keychain again for an item the command spoke for; the engine reads
 * the keychain itself only for a pointer no command sent (a dashboard or MCP build). Values are
 * secrets: {@link #toString} names only the counts, and nothing journals, logs or replies with
 * them.
 */
public final class RequestKeychain {

    private static final ScopedValue<RequestKeychain> BOUND = ScopedValue.newInstance();

    static {
        // Pool workers run a request's steps; without this they would see no secrets, or another
        // request's.
        ContextPropagator.add(new ContextPropagator.Propagator() {
            @Override
            public Runnable wrapRunnable(Runnable r) {
                RequestKeychain bound = current();
                return bound == null ? r : () -> ScopedValue.where(BOUND, bound).run(r);
            }

            @Override
            public <T> Callable<T> wrapCallable(Callable<T> c) {
                RequestKeychain bound = current();
                return bound == null ? c : () -> ScopedValue.where(BOUND, bound).call(c::call);
            }
        });
    }

    private final Map<String, String> secrets;
    private final Map<String, String> refusals;

    private RequestKeychain(Map<String, String> secrets, Map<String, String> refusals) {
        this.secrets = Map.copyOf(secrets);
        this.refusals = Map.copyOf(refusals);
    }

    /** The secrets bound to the running request, or {@code null} when it carried none. */
    public static @Nullable RequestKeychain current() {
        return BOUND.isBound() ? BOUND.get() : null;
    }

    /**
     * Run {@code body} with {@code secrets} and {@code refusals} (why an item was not read) bound;
     * both empty binds nothing.
     */
    public static <T> T where(Map<String, String> secrets, Map<String, String> refusals, Callable<T> body)
            throws Exception {
        if (secrets.isEmpty() && refusals.isEmpty()) return body.call();
        return ScopedValue.where(BOUND, new RequestKeychain(secrets, refusals)).call(body::call);
    }

    /** {@link #where} for a body with no result. */
    public static void runWhere(Map<String, String> secrets, Map<String, String> refusals, Runnable body) {
        if (secrets.isEmpty() && refusals.isEmpty()) {
            body.run();
            return;
        }
        ScopedValue.where(BOUND, new RequestKeychain(secrets, refusals)).run(body);
    }

    /** The secret the client sent for {@code pointer}, or {@code null}. */
    public @Nullable String get(SecretVault.Pointer pointer) {
        return secrets.get(pointer.text());
    }

    /** Why the client could not read {@code pointer}, or {@code null} when it did not say. */
    public @Nullable String refusal(SecretVault.Pointer pointer) {
        return refusals.get(pointer.text());
    }

    @Override
    public String toString() {
        return "RequestKeychain[" + secrets.size() + " items, " + refusals.size() + " refused]";
    }
}
