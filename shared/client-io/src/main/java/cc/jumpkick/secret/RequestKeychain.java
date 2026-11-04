// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import cc.jumpkick.run.ContextPropagator;
import java.util.Map;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * The keychain secrets the {@code jk} command read and sent with the request it is being served,
 * keyed by {@link SecretVault.Pointer#text()}, bound for the extent of that request.
 *
 * <p>The client reads the items of a keychain the engine cannot reach ({@link
 * SecretBackend#daemonReachable()}) and sends them, the way {@code JK_REPO_*} variables ride the
 * request's shell; {@link SecretVault#resolve} answers from here first. Values are secrets:
 * {@link #toString} names only the count, and nothing journals, logs or replies with them.
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

    private RequestKeychain(Map<String, String> secrets) {
        this.secrets = Map.copyOf(secrets);
    }

    /** The secrets bound to the running request, or {@code null} when it carried none. */
    public static @Nullable RequestKeychain current() {
        return BOUND.isBound() ? BOUND.get() : null;
    }

    /** Run {@code body} with {@code secrets} bound; an empty map binds nothing. */
    public static <T> T where(Map<String, String> secrets, Callable<T> body) throws Exception {
        if (secrets.isEmpty()) return body.call();
        return ScopedValue.where(BOUND, new RequestKeychain(secrets)).call(body::call);
    }

    /** {@link #where} for a body with no result. */
    public static void runWhere(Map<String, String> secrets, Runnable body) {
        if (secrets.isEmpty()) {
            body.run();
            return;
        }
        ScopedValue.where(BOUND, new RequestKeychain(secrets)).run(body);
    }

    /** The secret the client sent for {@code pointer}, or {@code null}. */
    public @Nullable String get(SecretVault.Pointer pointer) {
        return secrets.get(pointer.text());
    }

    @Override
    public String toString() {
        return "RequestKeychain[" + secrets.size() + " items]";
    }
}
