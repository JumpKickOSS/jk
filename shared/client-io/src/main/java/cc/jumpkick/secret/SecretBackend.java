// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One OS keychain. An item is named by jk ({@code repo/nexus}, {@code forge/github.com}) and holds
 * one secret; what the secret is for stays in the record file that points at the item.
 *
 * <p>Every method may throw {@link SecretBackendException} when the keychain is there but refuses.
 */
public interface SecretBackend {

    /** The spelling a record's pointer uses: {@code secret-service}, {@code macos-keychain}, … */
    String id();

    /** What {@code jk doctor} and the login notices call it. */
    String displayName();

    /** Whether this keychain answers here and now; {@code reason} says why not when it does not. */
    record Probe(boolean available, @Nullable String reason) {
        public static final Probe AVAILABLE = new Probe(true, null);

        public static Probe unavailable(String reason) {
            return new Probe(false, reason);
        }
    }

    /** Ask the keychain whether it answers, without touching any item. */
    Probe probe();

    /**
     * Whether a daemon of this user reaches the keychain as well as the shell that ran {@code jk}.
     * The macOS keychain and the Windows Credential Manager belong to the user's logon session,
     * which the engine shares. The Secret Service is found through the shell's D-Bus variables,
     * and a resident engine has those of whichever shell started it, if any (none when it was
     * started over SSH or by systemd), so the client reads it and sends the secret with the
     * request.
     */
    boolean daemonReachable();

    /** The secret {@code item} holds, or empty when there is no such item. */
    Optional<String> read(String item);

    /** Store {@code secret} as {@code item}, replacing what it held. {@code label} is what a keychain UI shows. */
    void write(String item, String label, String secret);

    /** Remove {@code item}; an absent item is not an error. */
    void delete(String item);
}
