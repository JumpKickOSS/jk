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
 * <p>{@code secret-tool} has no way to refuse instead of prompting: a lookup or store in a locked
 * collection asks the keyring to unlock it and waits for an answer nobody may be there to give. So
 * every call is preceded by the probe, which reads the default collection's {@code Locked}
 * property over D-Bus ({@code busctl}, else {@code gdbus}); that never prompts, and a locked or
 * absent default collection is a refusal on the spot.
 *
 * <p>{@code secret-tool lookup} and {@code clear} exit 1 with nothing on stderr when no item
 * matches; any complaint on stderr (no D-Bus session, a locked or absent collection) is the
 * keychain refusing.
 */
public final class SecretServiceBackend implements SecretBackend {

    static final String TOOL = "secret-tool";

    /** The collection {@code secret-tool store} writes to. */
    static final String DEFAULT_COLLECTION = "/org/freedesktop/secrets/aliases/default";

    static final List<String> BUSCTL = List.of(
            "busctl",
            "--user",
            "get-property",
            "org.freedesktop.secrets",
            DEFAULT_COLLECTION,
            "org.freedesktop.Secret.Collection",
            "Locked");

    static final List<String> GDBUS = List.of(
            "gdbus",
            "call",
            "--session",
            "--dest",
            "org.freedesktop.secrets",
            "--object-path",
            DEFAULT_COLLECTION,
            "--method",
            "org.freedesktop.DBus.Properties.Get",
            "org.freedesktop.Secret.Collection",
            "Locked");

    /** The probe has ruled out a prompt, so a call that takes longer than this is stuck. */
    private static final Duration CAP = Duration.ofSeconds(5);

    private static final Duration PROBE_CAP = Duration.ofSeconds(1);

    private final SecretCommand command;
    private final HostSession session;

    public SecretServiceBackend(SecretCommand command) {
        this(command, HostSession.current());
    }

    public SecretServiceBackend(SecretCommand command, HostSession session) {
        this.command = Objects.requireNonNull(command, "command");
        this.session = Objects.requireNonNull(session, "session");
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
    public Probe probe() {
        if (!session.sessionBus()) return Probe.unavailable("no D-Bus session bus");
        if (!session.onPath(TOOL)) return Probe.unavailable(TOOL + " is not installed");
        @Nullable List<String> ask = session.onPath("busctl") ? BUSCTL : session.onPath("gdbus") ? GDBUS : null;
        if (ask == null) {
            return Probe.refused("neither busctl nor gdbus is installed");
        }
        try {
            return lockAnswer(command.run(ask, null, PROBE_CAP));
        } catch (IOException e) {
            return Probe.refused(e.getMessage() == null ? ask.get(0) + " failed" : e.getMessage());
        }
    }

    /** What {@link #BUSCTL} or {@link #GDBUS} said about the default collection, as a probe. */
    static Probe lockAnswer(SecretCommand.Result r) {
        String out = r.stdout().strip();
        if (r.exit() == 0) {
            if (out.equals("b false") || out.equals("(<false>,)")) return Probe.AVAILABLE;
            if (out.equals("b true") || out.equals("(<true>,)")) {
                return Probe.refused("the default keyring is locked, and jk never asks to unlock it");
            }
            return Probe.refused("the default keyring's Locked property reads \"" + out + "\"");
        }
        String said = r.complaint();
        if (said.contains("ServiceUnknown") || said.contains("not provided by any .service files")) {
            return Probe.unavailable("no Secret Service on the session bus");
        }
        if (said.contains("Failed to connect to bus") || said.contains("Could not connect")) {
            return Probe.unavailable("the D-Bus session bus does not answer (" + said + ")");
        }
        if (said.contains("does not exist") || said.contains("UnknownObject") || said.contains("UnknownMethod")) {
            return Probe.refused("the keyring has no default collection, and creating one would prompt");
        }
        return Probe.refused(said);
    }

    @Override
    public Optional<String> read(String item) {
        requireAnswering("read", item);
        SecretCommand.Result r = run(List.of(TOOL, "lookup", "service", SecretItems.SERVICE, "account", item), null);
        if (r.exit() == 0) return Optional.of(r.stdout());
        if (r.exit() == 1 && r.stderr().isBlank()) return Optional.empty();
        throw new SecretBackendException("the Secret Service refused to read " + item + ": " + r.complaint());
    }

    @Override
    public void write(String item, String label, String secret) {
        requireAnswering("store", item);
        SecretCommand.Result r = run(
                List.of(TOOL, "store", "--label=" + label, "service", SecretItems.SERVICE, "account", item),
                secret.getBytes(StandardCharsets.UTF_8));
        if (r.exit() != 0) {
            throw new SecretBackendException("the Secret Service refused to store " + item + ": " + r.complaint());
        }
    }

    @Override
    public void delete(String item) {
        requireAnswering("remove", item);
        SecretCommand.Result r = run(List.of(TOOL, "clear", "service", SecretItems.SERVICE, "account", item), null);
        if (r.exit() == 0 || (r.exit() == 1 && r.stderr().isBlank())) return;
        throw new SecretBackendException("the Secret Service refused to remove " + item + ": " + r.complaint());
    }

    /** Refuse before {@code secret-tool} can wait on an unlock prompt. */
    private void requireAnswering(String verb, String item) {
        Probe probe = probe();
        if (!probe.available()) {
            throw new SecretBackendException(
                    "the Secret Service refused to " + verb + " " + item + ": " + probe.reason());
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
