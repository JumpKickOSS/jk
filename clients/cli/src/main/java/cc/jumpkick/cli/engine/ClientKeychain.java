// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

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
import org.jspecify.annotations.Nullable;

/**
 * What this {@code jk} command sends with its requests from the keychain: for every stored login
 * whose secret is in one, the secret, or why the keychain did not give it up. A resident engine
 * keeps the session of whichever shell started it, and this command may run in another, so the
 * command always reads; the engine never asks the keychain again for an item named here, and
 * warns with the command's reason for one it could not read.
 *
 * <p>Read once per command, when the first request goes out, and only when a record points into a
 * keychain: a machine with no keychain logins pays one directory listing. Each item is read on its
 * own platform thread, so several logins cost one tool's latency, not their sum.
 */
final class ClientKeychain {

    /** The secrets and the refusals, each by pointer text. */
    record Carried(Map<String, String> secrets, Map<String, String> refused) {
        static final Carried NONE = new Carried(Map.of(), Map.of());
    }

    private static volatile @Nullable Carried carried;

    private ClientKeychain() {}

    /** What to send; {@link Carried#NONE} when no login points into a keychain. */
    static Carried carried() {
        Carried known = carried;
        if (known != null) return known;
        Carried read;
        try {
            SecretVault vault = SecretVault.current();
            read = read(vault, pointers(new RepoCredentialStore().logins(), new TokenStore().logins()));
        } catch (RuntimeException unreadable) {
            read = Carried.NONE;
        }
        carried = read;
        return read;
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
