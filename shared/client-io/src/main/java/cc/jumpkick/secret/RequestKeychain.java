// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import cc.jumpkick.config.Request;
import java.util.Map;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * What the {@code jk} command that sent the request being served read from the keychain: each
 * item's secret, or why the keychain did not give it up, keyed by {@link SecretVault.Pointer#text()}
 * and carried by that {@link Request} under {@link #SLOT}.
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

    /** Where a request carries its keychain answers. */
    public static final Request.Slot<RequestKeychain> SLOT = new Request.Slot<>("keychain");

    private final Map<String, String> secrets;
    private final Map<String, String> refusals;

    private RequestKeychain(Map<String, String> secrets, Map<String, String> refusals) {
        this.secrets = Map.copyOf(secrets);
        this.refusals = Map.copyOf(refusals);
    }

    /**
     * The answers {@code secrets} and {@code refusals} (why an item was not read), or {@code null}
     * when both are empty: a request that carried none.
     */
    public static @Nullable RequestKeychain of(Map<String, String> secrets, Map<String, String> refusals) {
        return secrets.isEmpty() && refusals.isEmpty() ? null : new RequestKeychain(secrets, refusals);
    }

    /** The secrets the running request carries, or {@code null} when it carried none. */
    public static @Nullable RequestKeychain current() {
        Request r = Request.current();
        return r == null ? null : r.get(SLOT);
    }

    /**
     * Run {@code body} with {@code secrets} and {@code refusals} carried by the current request, or
     * by a request that is no job off one; both empty binds nothing.
     */
    public static <T> T where(Map<String, String> secrets, Map<String, String> refusals, Callable<T> body)
            throws Exception {
        RequestKeychain keychain = of(secrets, refusals);
        if (keychain == null) return body.call();
        return Request.currentOrDetached().with(SLOT, keychain).call(body::call);
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
