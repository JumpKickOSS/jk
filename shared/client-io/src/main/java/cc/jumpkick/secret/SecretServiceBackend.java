// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The freedesktop Secret Service (GNOME Keyring, KWallet, KeePassXC), through libsecret's {@code
 * secret-tool}. An item carries the attributes {@code service=jk} and {@code account=<item>}.
 *
 * <p>{@code secret-tool lookup} and {@code clear} exit 1 with nothing on stderr when no item
 * matches; any complaint on stderr (no D-Bus session, a locked or absent collection) is the
 * keychain refusing.
 */
public final class SecretServiceBackend implements SecretBackend {

    static final String TOOL = "secret-tool";

    /** Long enough for a user to answer the keyring's unlock prompt. */
    private static final Duration CAP = Duration.ofSeconds(60);

    private static final Duration PROBE_CAP = Duration.ofSeconds(5);

    private final SecretCommand command;

    public SecretServiceBackend(SecretCommand command) {
        this.command = Objects.requireNonNull(command, "command");
    }

    @Override
    public String id() {
        return "secret-service";
    }

    @Override
    public String displayName() {
        return "Secret Service";
    }

    @Override
    public boolean daemonReachable() {
        return false;
    }

    @Override
    public Probe probe() {
        try {
            // A search for an attribute no item has touches no item, so it never prompts.
            SecretCommand.Result r =
                    command.run(List.of(TOOL, "search", "service", SecretItems.SERVICE + "-probe"), null, PROBE_CAP);
            return r.exit() == 0 ? Probe.AVAILABLE : Probe.unavailable(r.complaint());
        } catch (IOException e) {
            return Probe.unavailable(TOOL + " is not available: " + e.getMessage());
        }
    }

    @Override
    public Optional<String> read(String item) {
        SecretCommand.Result r = run(List.of(TOOL, "lookup", "service", SecretItems.SERVICE, "account", item), null);
        if (r.exit() == 0) return Optional.of(r.stdout());
        if (r.exit() == 1 && r.stderr().isBlank()) return Optional.empty();
        throw new SecretBackendException("the Secret Service refused to read " + item + ": " + r.complaint());
    }

    @Override
    public void write(String item, String label, String secret) {
        SecretCommand.Result r = run(
                List.of(TOOL, "store", "--label=" + label, "service", SecretItems.SERVICE, "account", item),
                secret.getBytes(StandardCharsets.UTF_8));
        if (r.exit() != 0) {
            throw new SecretBackendException("the Secret Service refused to store " + item + ": " + r.complaint());
        }
    }

    @Override
    public void delete(String item) {
        SecretCommand.Result r = run(List.of(TOOL, "clear", "service", SecretItems.SERVICE, "account", item), null);
        if (r.exit() == 0 || (r.exit() == 1 && r.stderr().isBlank())) return;
        throw new SecretBackendException("the Secret Service refused to remove " + item + ": " + r.complaint());
    }

    private SecretCommand.Result run(List<String> argv, byte @Nullable [] stdin) {
        try {
            return command.run(argv, stdin, CAP);
        } catch (IOException e) {
            throw new SecretBackendException(TOOL + " failed: " + e.getMessage(), e);
        }
    }
}
