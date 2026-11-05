// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.forge.TokenStore;
import cc.jumpkick.repo.RepoCredentialStore;
import cc.jumpkick.secret.SecretVault;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What this {@code jk} command sends with its requests from the keychain: for every stored login
 * whose secret is in one, the secret, or why the keychain did not give it up. A resident engine
 * keeps the session of whichever shell started it, and this command may run in another, so the
 * command always reads; the engine never asks the keychain again for an item named here, and
 * warns with the command's reason for one it could not read.
 *
 * <p>Read once per command ({@link SessionContext#perInvocation}), when the first request goes
 * out, and only when a record points into a keychain: a machine with no keychain logins pays one
 * directory listing. The next command in the same process (the JVM client, an in-process test)
 * reads again, so a login rotated or removed in between is what it sends. Each item is read on its
 * own platform thread, so several logins cost one tool's latency, not their sum.
 */
final class ClientKeychain {

    /** The secrets and the refusals, each by pointer text. */
    record Carried(Map<String, String> secrets, Map<String, String> refused) {
        static final Carried NONE = new Carried(Map.of(), Map.of());
    }

    /** The key of this command's read in its invocation's facts. */
    private static final Object KEY = new Object();

    private ClientKeychain() {}

    /** What this command sends; {@link Carried#NONE} when no login points into a keychain. */
    static Carried carried() {
        return SessionContext.perInvocation(KEY, ClientKeychain::readNow);
    }

    private static Carried readNow() {
        try {
            SecretVault vault = SecretVault.current();
            return read(vault, pointers(new RepoCredentialStore().logins(), new TokenStore().logins()));
        } catch (RuntimeException unreadable) {
            return Carried.NONE;
        }
    }

    /** Every pointer the stored logins hold, in listing order, without repeats. */
    static List<SecretVault.Pointer> pointers(List<RepoCredentialStore.Login> repo, List<TokenStore.Login> forge) {
        Set<SecretVault.Pointer> out = new LinkedHashSet<>();
        for (RepoCredentialStore.Login login : repo) {
            if (login.pointer() != null) out.add(login.pointer());
        }
        for (TokenStore.Login login : forge) {
            if (login.pointer() != null) out.add(login.pointer());
        }
        return List.copyOf(out);
    }

    /** Read every pointer, concurrently: each one's secret, or why there is none. */
    static Carried read(SecretVault vault, List<SecretVault.Pointer> pointers) {
        if (pointers.isEmpty()) return Carried.NONE;
        Map<String, SecretVault.ClientRead> got = new ConcurrentHashMap<>();
        List<Thread> readers = new ArrayList<>(pointers.size());
        for (SecretVault.Pointer pointer : pointers) {
            readers.add(Thread.ofPlatform()
                    .daemon()
                    .name("jk-keychain-read")
                    .start(() -> got.put(pointer.text(), vault.readForRequest(pointer))));
        }
        for (Thread reader : readers) {
            try {
                reader.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Map<String, String> secrets = new LinkedHashMap<>();
        Map<String, String> refused = new LinkedHashMap<>();
        for (SecretVault.Pointer pointer : pointers) {
            SecretVault.ClientRead answer = got.get(pointer.text());
            if (answer == null) continue;
            if (answer.secret() != null) secrets.put(pointer.text(), answer.secret());
            else if (answer.refused() != null) refused.put(pointer.text(), answer.refused());
        }
        return new Carried(Collections.unmodifiableMap(secrets), Collections.unmodifiableMap(refused));
    }
}
