// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The macOS login keychain, through {@code /usr/bin/security}. An item is a generic password with
 * service {@code jk} and account {@code <item>}.
 *
 * <p>A write goes through {@code security -i}, whose commands are read from stdin, so the secret
 * never appears in a process listing; it travels hex-encoded ({@code -X}), so no character of it
 * needs quoting. {@code security} exits with the low byte of the Security framework's status:
 * 44 when no item matches ({@code errSecItemNotFound}), 36 when the keychain is locked and this
 * session has no window server to unlock it ({@code errSecInteractionNotAllowed}, "User interaction
 * is not allowed": SSH, launchd), 51 when the keychain denied jk ({@code errSecAuthFailed}).
 *
 * <p>Every call is preceded by the probe. It reads the default keychain's lock status in process
 * ({@link MacKeychainLock}), which shows nothing and takes microseconds; a locked keychain is a
 * refusal before {@code security} runs, since a search of a locked keychain over SSH answers "not
 * found" and in a desktop session may ask to unlock it. Where the status cannot be read (no native
 * access), the probe is {@code show-keychain-info} on the default keychain, which reads its
 * settings and so fails the same way a locked keychain would.
 *
 * <p>Items stay with {@code security} rather than {@code SecItemAdd}/{@code SecItemCopyMatching}
 * in process: a login keychain item's access list names the program that created it, so an item
 * the native {@code jk} wrote would refuse (or, in a desktop session, prompt) the engine's JVM and
 * every later {@code jk} binary. Through {@code /usr/bin/security}, writer and readers are the
 * same Apple-signed program.
 */
public final class MacKeychainBackend implements SecretBackend {

    static final String TOOL = "/usr/bin/security";

    /** {@code errSecItemNotFound}, as {@code security} reports it. */
    static final int NOT_FOUND = 44;

    /** {@code errSecInteractionNotAllowed}: locked, and no window server to unlock it. */
    static final int INTERACTION_NOT_ALLOWED = 36;

    /** {@code errSecAuthFailed}: the keychain denied the request. */
    static final int AUTH_FAILED = 51;

    private static final Duration CAP = Duration.ofSeconds(5);

    private static final Duration PROBE_CAP = Duration.ofSeconds(1);

    private final SecretCommand command;
    private final MacKeychainLock lock;

    public MacKeychainBackend(SecretCommand command) {
        this(command, MacKeychainLock.HOST);
    }

    /** Over {@code command}, with the lock status from {@code lock}: tests. */
    MacKeychainBackend(SecretCommand command, MacKeychainLock lock) {
        this.command = Objects.requireNonNull(command, "command");
        this.lock = Objects.requireNonNull(lock, "lock");
    }

    @Override
    public String id() {
        return "macos-keychain";
    }

    @Override
    public String displayName() {
        return "macOS Keychain";
    }

    @Override
    public Probe probe() {
        MacKeychainLock.Status status = lock.status();
        switch (status.state()) {
            case UNLOCKED -> {
                return Probe.AVAILABLE;
            }
            case LOCKED -> {
                return Probe.refused(Objects.requireNonNullElse(status.reason(), "the default keychain is locked"));
            }
            case NO_KEYCHAIN -> {
                return Probe.unavailable(Objects.requireNonNullElse(status.reason(), "no default keychain"));
            }
            case UNKNOWN -> {
                // Ask security below.
            }
        }
        try {
            SecretCommand.Result r = command.run(List.of(TOOL, "show-keychain-info"), null, PROBE_CAP);
            if (r.exit() == 0) return Probe.AVAILABLE;
            if (noWindowServer(r)) {
                return Probe.unavailable("the login keychain is locked and this session cannot unlock it");
            }
            return Probe.refused(complaint(r));
        } catch (IOException e) {
            return Probe.unavailable(TOOL + " is not available: " + e.getMessage());
        }
    }

    /** Whether {@code security} said the keychain is locked with nobody able to unlock it. */
    static boolean noWindowServer(SecretCommand.Result r) {
        return r.exit() == INTERACTION_NOT_ALLOWED
                || (r.stdout() + r.stderr()).contains("User interaction is not allowed");
    }

    /** What {@code security} said, in words for the refusals jk knows. */
    static String complaint(SecretCommand.Result r) {
        if (noWindowServer(r))
            return "the keychain is locked and this session cannot unlock it (" + r.complaint() + ")";
        if (r.exit() == AUTH_FAILED) return "the keychain denied access (" + r.complaint() + ")";
        return r.complaint();
    }

    @Override
    public Optional<String> read(String item) {
        requireAnswering("read", item);
        SecretCommand.Result r =
                run(List.of(TOOL, "find-generic-password", "-s", SecretItems.SERVICE, "-a", item, "-w"), null);
        if (r.exit() == 0) return Optional.of(withoutNewline(r.stdout()));
        if (r.exit() == NOT_FOUND) return Optional.empty();
        throw new SecretBackendException("the macOS Keychain refused to read " + item + ": " + complaint(r));
    }

    @Override
    public void write(String item, String label, String secret) {
        requireAnswering("store", item);
        SecretCommand.Result r =
                run(List.of(TOOL, "-i"), addCommand(item, secret).getBytes(StandardCharsets.UTF_8));
        // `security -i` keeps going after a failed command, so its stderr is the verdict.
        if (r.exit() != 0 || !r.stderr().isBlank()) {
            throw new SecretBackendException("the macOS Keychain refused to store " + item + ": " + complaint(r));
        }
    }

    @Override
    public void delete(String item) {
        requireAnswering("remove", item);
        SecretCommand.Result r =
                run(List.of(TOOL, "delete-generic-password", "-s", SecretItems.SERVICE, "-a", item), null);
        if (r.exit() == 0 || r.exit() == NOT_FOUND) return;
        throw new SecretBackendException("the macOS Keychain refused to remove " + item + ": " + complaint(r));
    }

    /**
     * The one {@code security -i} line that adds or updates ({@code -U}) the item. The label is
     * the item's own name, which needs no quoting.
     */
    static String addCommand(String item, String secret) {
        SecretItems.requireValid(item);
        String hex = Hashing.hex(secret.getBytes(StandardCharsets.UTF_8));
        return "add-generic-password -U -s " + SecretItems.SERVICE + " -a " + item + " -l " + SecretItems.SERVICE + ":"
                + item + " -X " + hex + "\n";
    }

    /** {@code -w} prints the password and a newline. */
    static String withoutNewline(String out) {
        return out.endsWith("\n") ? out.substring(0, out.length() - 1) : out;
    }

    /** Refuse before {@code security} can ask for an item in a locked keychain. */
    private void requireAnswering(String verb, String item) {
        Probe probe = probe();
        if (!probe.available()) {
            throw new SecretBackendException(
                    "the macOS Keychain refused to " + verb + " " + item + ": " + probe.reason());
        }
    }

    private SecretCommand.Result run(List<String> argv, byte @Nullable [] stdin) {
        try {
            return command.run(argv, stdin, CAP);
        } catch (IOException e) {
            throw new SecretBackendException(TOOL + " failed: " + e.getMessage(), e);
        }
    }
}
