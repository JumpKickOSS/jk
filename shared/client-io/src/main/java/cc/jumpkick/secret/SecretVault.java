// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Where a login's secret lives: the OS keychain when one answers, else the owner-only record file.
 *
 * <p>The choice is {@value #ENV}, then {@code [credentials] store} in {@code ~/.jk/config.toml},
 * then {@code auto}: {@code keychain} insists on the keychain and fails a login that cannot reach
 * one; {@code file} keeps the secret in the record; {@code auto} takes the first keychain that
 * answers and otherwise the file.
 *
 * <p>Headless is the normal case: a CI server, a build container, an SSH session and a systemd
 * service have no desktop, and the keychain is a desktop convenience. So {@code auto} on a CI
 * server or in a container ({@link HostSession#headless()}) asks no keychain at all, and {@code
 * auto} with no keychain here (no session bus, no tool, no window server) settles for the file
 * without a word. The one notice is for a keychain that is there and refused (locked, denied), and
 * not even that over SSH with no display, where nobody could have unlocked it. No backend ever
 * prompts or waits on one: a locked keychain refuses on the spot.
 *
 * <p>A secret in a keychain leaves a {@link Pointer} in its record. {@link #resolve} turns the
 * pointer back into the secret. A {@code jk} command reads every pointer its logins hold ({@link
 * #readForRequest}) and sends the secret, or why the keychain did not give it up, with its request
 * ({@link RequestKeychain}), because a resident engine keeps the session of whichever shell
 * started it: the Secret Service is found through that shell's D-Bus variables, the Windows
 * Credential Manager belongs to its logon session, and the macOS login keychain is unlocked per
 * security session. The engine answers from what the command sent and asks the keychain from its
 * own process only for a pointer no command spoke for: a build the dashboard or an MCP client
 * started, which reaches a keychain only when the engine was started from a desktop session.
 */
public final class SecretVault {

    /** The environment variable that overrides {@code [credentials] store}, for CI. */
    public static final String ENV = "JK_CREDENTIALS_STORE";

    /** What {@code [credentials] store} or {@value #ENV} asks for. */
    public enum Choice {
        AUTO,
        KEYCHAIN,
        FILE;

        /** {@code auto}, {@code keychain} or {@code file}, any case. */
        public static Choice parse(String text, String source) {
            return switch (text.strip().toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "keychain" -> KEYCHAIN;
                case "file" -> FILE;
                default ->
                    throw new IllegalArgumentException(
                            source + " is \"" + text.strip() + "\"; it takes \"keychain\", \"file\" or \"auto\"");
            };
        }

        public String spelling() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Where a record says its secret is: a keychain and the item in it. Written into the record as
     * {@code <backend> <item>}.
     */
    public record Pointer(String backend, String item) {
        public Pointer {
            if (!backend.matches("[a-z][a-z0-9-]*")) throw new IllegalArgumentException("bad keychain: " + backend);
            SecretItems.requireValid(item);
        }

        public String text() {
            return backend + " " + item;
        }

        /** The pointer {@code text} spells, or empty when it is not one. */
        public static Optional<Pointer> parse(String text) {
            String[] parts = text.strip().split(" ", -1);
            if (parts.length != 2 || parts[0].isEmpty()) return Optional.empty();
            try {
                return Optional.of(new Pointer(parts[0], parts[1]));
            } catch (IllegalArgumentException notAnItem) {
                return Optional.empty();
            }
        }
    }

    /**
     * Where {@link #store} put a secret: {@code pointer} when a keychain holds it, else null and the
     * caller writes the secret into its record. {@code notice} is what the user is told when
     * {@code auto} settled for the file.
     */
    public record Stored(
            @Nullable Pointer pointer, @Nullable String notice) {}

    private final Choice choice;
    private final List<SecretBackend> backends;
    private final HostSession session;

    /** Why the configured choice could not be read; a login refuses, a read is unaffected. */
    private final @Nullable String misconfigured;

    private SecretVault(
            Choice choice, List<SecretBackend> backends, HostSession session, @Nullable String misconfigured) {
        this.choice = Objects.requireNonNull(choice, "choice");
        this.backends = List.copyOf(backends);
        this.session = Objects.requireNonNull(session, "session");
        this.misconfigured = misconfigured;
    }

    /** {@code choice} over {@code backends}, in preference order, on a desktop — tests. */
    public static SecretVault of(Choice choice, List<SecretBackend> backends) {
        return of(choice, backends, HostSession.desktop());
    }

    /** {@code choice} over {@code backends}, in preference order, in {@code session} — tests. */
    public static SecretVault of(Choice choice, List<SecretBackend> backends, HostSession session) {
        return new SecretVault(choice, backends, session, null);
    }

    /** A vault that keeps every secret in its record and follows no pointer: a scratch store. */
    public static SecretVault fileOnly() {
        return new SecretVault(Choice.FILE, List.of(), HostSession.desktop(), null);
    }

    /**
     * This machine's vault: the choice from {@value #ENV} or {@code config.toml}, over this OS's
     * keychain. A misspelled choice still reads every record; {@link #store} refuses with the
     * spelling problem.
     */
    public static SecretVault current() {
        HostSession session = HostSession.current();
        List<SecretBackend> host = hostBackends(SecretCommand.REAL, session);
        try {
            return new SecretVault(configuredChoice(), host, session, null);
        } catch (IllegalArgumentException misspelled) {
            return new SecretVault(Choice.AUTO, host, session, misspelled.getMessage());
        }
    }

    /** {@value #ENV}, then {@code [credentials] store}, then {@code auto}. */
    public static Choice configuredChoice() {
        String env = JkDirs.env(ENV);
        if (env != null && !env.isBlank()) return Choice.parse(env, ENV);
        return GlobalConfig.credentialsStore()
                .map(v -> Choice.parse(v, "[credentials] store in ~/.jk/config.toml"))
                .orElse(Choice.AUTO);
    }

    /** The keychain this OS has, in preference order. */
    public static List<SecretBackend> hostBackends(SecretCommand command, HostSession session) {
        if (Os.isDarwin()) return List.of(new MacKeychainBackend(command));
        if (Os.isWindows()) return List.of(new WindowsCredentialBackend());
        if (Os.isLinux()) return List.of(new SecretServiceBackend(command, session));
        return List.of();
    }

    /** What a pointer's backend is called, whether or not this machine has it. */
    public static String displayName(String backendId) {
        return switch (backendId) {
            case "secret-service" -> "Secret Service";
            case "macos-keychain" -> "macOS Keychain";
            case "windows-credential-manager" -> "Windows Credential Manager";
            default -> backendId;
        };
    }

    public Choice choice() {
        return choice;
    }

    /** Why the configured choice could not be read, or {@code null} when it could. */
    public @Nullable String misconfigured() {
        return misconfigured;
    }

    /** The backend a pointer names, when this vault has it. */
    public Optional<SecretBackend> backend(String id) {
        return backends.stream().filter(b -> b.id().equals(id)).findFirst();
    }

    /**
     * Where a new secret would go, without storing one: the keychain that answers, or empty for the
     * file. {@code reasons} collects why each keychain did not.
     */
    public Optional<SecretBackend> destination(List<String> reasons) {
        Placement placement = place();
        reasons.addAll(placement.refusals());
        reasons.addAll(placement.absences());
        return Optional.ofNullable(placement.backend());
    }

    /**
     * Where a new secret goes: {@code backend}, or null for the file. {@code refusals} name the
     * keychains that are there and would not answer, {@code absences} those this machine does not
     * have; {@code headless} says why no keychain was asked at all.
     */
    private record Placement(
            @Nullable SecretBackend backend,
            List<String> refusals,
            List<String> absences,
            @Nullable String headless) {}

    private Placement place() {
        List<String> refusals = new ArrayList<>();
        List<String> absences = new ArrayList<>();
        if (choice == Choice.FILE) return new Placement(null, refusals, absences, null);
        String headless = session.headless();
        if (choice == Choice.AUTO && headless != null) {
            absences.add("this is " + headless);
            return new Placement(null, refusals, absences, headless);
        }
        for (SecretBackend backend : backends) {
            SecretBackend.Probe probe = backend.probe();
            if (probe.available()) return new Placement(backend, refusals, absences, headless);
            (probe.refused() ? refusals : absences).add(backend.displayName() + ": " + probe.reason());
        }
        if (backends.isEmpty()) absences.add("jk knows no keychain on " + Os.name());
        return new Placement(null, refusals, absences, headless);
    }

    /**
     * Store {@code secret} as {@code item}. A keychain that answers takes it, and the caller records
     * the returned pointer; otherwise the caller keeps the secret in its record. {@code fileShown}
     * is the record's path, for the notice a refusing keychain earns.
     *
     * @throws IllegalStateException when the choice is {@code keychain} and no keychain took it
     */
    public Stored store(String item, String label, String secret, Path fileShown) {
        if (misconfigured != null) throw new IllegalStateException(misconfigured);
        if (choice == Choice.FILE) return new Stored(null, null);
        Placement placement = place();
        List<String> refusals = new ArrayList<>(placement.refusals());
        SecretBackend backend = placement.backend();
        if (backend != null) {
            try {
                backend.write(item, label, secret);
                Optional<String> back = backend.read(item);
                if (back.isPresent() && back.get().equals(secret)) {
                    Pointer pointer = new Pointer(backend.id(), item);
                    forget(pointer);
                    return new Stored(pointer, null);
                }
                refusals.add(backend.displayName() + ": the item did not read back as written");
                backend.delete(item);
            } catch (SecretBackendException refused) {
                refusals.add(refused.getMessage());
            }
        }
        if (choice == Choice.KEYCHAIN) {
            List<String> why = new ArrayList<>(refusals);
            why.addAll(placement.absences());
            if (placement.headless() != null) why.add("this is " + placement.headless());
            throw new IllegalStateException(
                    "no OS keychain took the secret: " + String.join(", ", why) + "\n  " + HEADLESS_RECIPE);
        }
        if (refusals.isEmpty() || session.remote()) return new Stored(null, null);
        return new Stored(
                null,
                "jk: notice: keychain refused, secret saved to " + fileShown + ": " + String.join(", ", refusals)
                        + "\n  set [credentials] store = \"file\" in ~/.jk/config.toml to skip the keychain");
    }

    /** What a machine with no keychain does instead, for the error that insists on one. */
    static final String HEADLESS_RECIPE = "unlock the keychain, or on CI set JK_REPO_<ID>_TOKEN or " + ENV + "=file";

    /**
     * The secret {@code pointer} names: the one the client sent with this request, else the
     * keychain's answer to this process when no client spoke for the pointer. A pointer the client
     * could not read fails with the client's reason, without asking the keychain again. {@code record} is the file the pointer was read from; a
     * keychain's answer is remembered until that file changes, so a build that resolves one
     * repository many times asks once.
     *
     * @throws SecretBackendException when the keychain is there and refuses
     */
    public Optional<String> resolve(Pointer pointer, Path record) {
        RequestKeychain carried = RequestKeychain.current();
        if (carried != null) {
            String sent = carried.get(pointer);
            if (sent != null) return Optional.of(sent);
            String refused = carried.refusal(pointer);
            if (refused != null) throw new SecretBackendException(refused);
        }
        SecretBackend backend =
                backend(pointer.backend()).orElseThrow(() -> new SecretBackendException(noSuchKeychain(pointer)));
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(record);
        if (stamp == null) return backend.read(pointer.item());
        return Optional.ofNullable(READS.get(
                pointer.text(), stamp, () -> backend.read(pointer.item()).orElse(null)));
    }

    /** What a client read for one pointer: the secret, or why the keychain did not give it up. */
    public record ClientRead(
            @Nullable String secret, @Nullable String refused) {
        public ClientRead {
            if ((secret == null) == (refused == null)) {
                throw new IllegalArgumentException("a read has a secret or a refusal, not both or neither");
            }
        }
    }

    /**
     * Read {@code pointer} from the keychain directly, for the client to send: the secret, or why
     * there is none — the keychain refused, it holds no such item, or this machine has no such
     * keychain.
     */
    public ClientRead readForRequest(Pointer pointer) {
        Optional<SecretBackend> backend = backend(pointer.backend());
        if (backend.isEmpty()) return new ClientRead(null, noSuchKeychain(pointer));
        try {
            Optional<String> secret = backend.get().read(pointer.item());
            return secret.isPresent()
                    ? new ClientRead(secret.get(), null)
                    : new ClientRead(null, "it holds no item " + pointer.item());
        } catch (SecretBackendException refused) {
            return new ClientRead(null, String.valueOf(refused.getMessage()));
        }
    }

    private static String noSuchKeychain(Pointer pointer) {
        return "this machine has no " + pointer.backend() + " keychain; log in again here";
    }

    /**
     * Remove what {@code pointer} names from its keychain.
     *
     * @throws SecretBackendException when the keychain is there and refuses
     */
    public void remove(Pointer pointer) {
        Optional<SecretBackend> backend = backend(pointer.backend());
        if (backend.isEmpty()) return;
        backend.get().delete(pointer.item());
        forget(pointer);
    }

    /** The login records in {@code dir}, by name; none when the store does not exist yet. */
    public static List<Path> recordFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (file, attrs) -> {
                if (attrs.isRegularFile() && !String.valueOf(file.getFileName()).startsWith(".")) files.add(file);
                return true;
            });
        } catch (IOException unreadable) {
            return List.of();
        }
        files.sort(null);
        return files;
    }

    /** Keychain answers by pointer text, each at the stamp of the record it was read through. */
    private static final StampedMemo<String, StampedMemo.FileStamp, @Nullable String> READS = StampedMemo.bounded(256);

    /** A login or logout changed what an item holds; nothing remembered about it still stands. */
    private static void forget(Pointer pointer) {
        READS.forget(pointer.text());
    }
}
