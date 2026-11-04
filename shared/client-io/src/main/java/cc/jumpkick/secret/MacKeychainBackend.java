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
 * needs quoting. {@code security} exits 44 when no item matches.
 */
public final class MacKeychainBackend implements SecretBackend {

    static final String TOOL = "/usr/bin/security";

    /** {@code errSecItemNotFound}, as {@code security} reports it. */
    static final int NOT_FOUND = 44;

    private static final Duration CAP = Duration.ofSeconds(60);

    private static final Duration PROBE_CAP = Duration.ofSeconds(5);

    private final SecretCommand command;

    public MacKeychainBackend(SecretCommand command) {
        this.command = Objects.requireNonNull(command, "command");
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
    public boolean daemonReachable() {
        return true;
    }

    @Override
    public Probe probe() {
        try {
            SecretCommand.Result r = command.run(List.of(TOOL, "default-keychain"), null, PROBE_CAP);
            return r.exit() == 0 ? Probe.AVAILABLE : Probe.unavailable(r.complaint());
        } catch (IOException e) {
            return Probe.unavailable(TOOL + " is not available: " + e.getMessage());
        }
    }

    @Override
    public Optional<String> read(String item) {
        SecretCommand.Result r =
                run(List.of(TOOL, "find-generic-password", "-s", SecretItems.SERVICE, "-a", item, "-w"), null);
        if (r.exit() == 0) return Optional.of(withoutNewline(r.stdout()));
        if (r.exit() == NOT_FOUND) return Optional.empty();
        throw new SecretBackendException("the macOS Keychain refused to read " + item + ": " + r.complaint());
    }

    @Override
    public void write(String item, String label, String secret) {
        SecretCommand.Result r =
                run(List.of(TOOL, "-i"), addCommand(item, secret).getBytes(StandardCharsets.UTF_8));
        // `security -i` keeps going after a failed command, so its stderr is the verdict.
        if (r.exit() != 0 || !r.stderr().isBlank()) {
            throw new SecretBackendException("the macOS Keychain refused to store " + item + ": " + r.complaint());
        }
    }

    @Override
    public void delete(String item) {
        SecretCommand.Result r =
                run(List.of(TOOL, "delete-generic-password", "-s", SecretItems.SERVICE, "-a", item), null);
        if (r.exit() == 0 || r.exit() == NOT_FOUND) return;
        throw new SecretBackendException("the macOS Keychain refused to remove " + item + ": " + r.complaint());
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

    private SecretCommand.Result run(List<String> argv, byte @Nullable [] stdin) {
        try {
            return command.run(argv, stdin, CAP);
        } catch (IOException e) {
            throw new SecretBackendException(TOOL + " failed: " + e.getMessage(), e);
        }
    }
}
