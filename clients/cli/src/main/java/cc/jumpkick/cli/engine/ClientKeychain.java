// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.forge.TokenStore;
import cc.jumpkick.repo.RepoCredentialStore;
import cc.jumpkick.secret.SecretBackend;
import cc.jumpkick.secret.SecretVault;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The keychain secrets this {@code jk} command sends with its requests: those of the stored logins
 * whose keychain the engine cannot reach itself ({@link SecretBackend#daemonReachable()} — the
 * Secret Service, which is found through this shell's D-Bus variables; the daemon has those of
 * whichever shell started it, if any).
 *
 * <p>Read once per command, when the first request goes out, and only when a record points into
 * such a keychain: a machine with no keychain logins pays one directory listing. Each item is read
 * on its own platform thread, so several logins cost one tool's latency, not their sum. An item
 * the keychain will not give up is left out; the engine then tries the keychain itself and warns.
 */
final class ClientKeychain {

    private static volatile @Nullable Map<String, String> carried;

    private ClientKeychain() {}

    /** The secrets to send, by pointer text; empty when no login needs carrying. */
    static Map<String, String> carried() {
        Map<String, String> known = carried;
        if (known != null) return known;
        Map<String, String> read;
        try {
            SecretVault vault = SecretVault.current();
            read = read(vault, pointers(new RepoCredentialStore().logins(), new TokenStore().logins()));
        } catch (RuntimeException unreadable) {
            read = Map.of();
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

    /** Read every pointer whose keychain the engine cannot reach, concurrently. */
    static Map<String, String> read(SecretVault vault, List<SecretVault.Pointer> pointers) {
        List<SecretVault.Pointer> wanted = new ArrayList<>();
        for (SecretVault.Pointer pointer : pointers) {
            Optional<SecretBackend> backend = vault.backend(pointer.backend());
            if (backend.isPresent() && !backend.get().daemonReachable()) wanted.add(pointer);
        }
        if (wanted.isEmpty()) return Map.of();
        Map<String, String> got = new ConcurrentHashMap<>();
        List<Thread> readers = new ArrayList<>(wanted.size());
        for (SecretVault.Pointer pointer : wanted) {
            readers.add(Thread.ofPlatform().daemon().name("jk-keychain-read").start(() -> vault.readForRequest(pointer)
                    .ifPresent(secret -> got.put(pointer.text(), secret))));
        }
        for (Thread reader : readers) {
            try {
                reader.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        for (SecretVault.Pointer pointer : wanted) {
            String secret = got.get(pointer.text());
            if (secret != null) ordered.put(pointer.text(), secret);
        }
        return Collections.unmodifiableMap(ordered);
    }
}
