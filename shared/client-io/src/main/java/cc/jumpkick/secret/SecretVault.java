// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.util.JkDirs;
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
 * answers and otherwise the file, with one notice saying why.
 *
 * <p>A secret in a keychain leaves a {@link Pointer} in its record. {@link #resolve} turns the
 * pointer back into the secret: from what the client sent with the request ({@link
 * RequestKeychain}) when it did, else by asking the keychain from this process.
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

    /** Why the configured choice could not be read; a login refuses, a read is unaffected. */
    private final @Nullable String misconfigured;

    private SecretVault(Choice choice, List<SecretBackend> backends, @Nullable String misconfigured) {
        this.choice = Objects.requireNonNull(choice, "choice");
        this.backends = List.copyOf(backends);
        this.misconfigured = misconfigured;
    }

    /** {@code choice} over {@code backends}, in preference order — tests. */
    public static SecretVault of(Choice choice, List<SecretBackend> backends) {
        return new SecretVault(choice, backends, null);
    }

    /** A vault that keeps every secret in its record and follows no pointer: a scratch store. */
    public static SecretVault fileOnly() {
        return new SecretVault(Choice.FILE, List.of(), null);
    }

    /**
     * This machine's vault: the choice from {@value #ENV} or {@code config.toml}, over this OS's
     * keychain. A misspelled choice still reads every record; {@link #store} refuses with the
     * spelling problem.
     */
    public static SecretVault current() {
        List<SecretBackend> host = hostBackends(SecretCommand.REAL);
        try {
            return new SecretVault(configuredChoice(), host, null);
        } catch (IllegalArgumentException misspelled) {
            return new SecretVault(Choice.AUTO, host, misspelled.getMessage());
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
    public static List<SecretBackend> hostBackends(SecretCommand command) {
        if (Os.isDarwin()) return List.of(new MacKeychainBackend(command));
        if (Os.isWindows()) return List.of(new WindowsCredentialBackend(command));
        if (Os.isLinux()) return List.of(new SecretServiceBackend(command));
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
        if (choice == Choice.FILE) return Optional.empty();
        for (SecretBackend backend : backends) {
            SecretBackend.Probe probe = backend.probe();
            if (probe.available()) return Optional.of(backend);
            reasons.add(backend.displayName() + ": " + probe.reason());
        }
        if (backends.isEmpty()) reasons.add("jk knows no keychain on " + Os.name());
        return Optional.empty();
    }

    /**
     * Store {@code secret} as {@code item}. A keychain that answers takes it, and the caller records
     * the returned pointer; otherwise the caller keeps the secret in its record. {@code fileShown}
     * is the record's path, for the notice.
     *
     * @throws IllegalStateException when the choice is {@code keychain} and no keychain took it
     */
    public Stored store(String item, String label, String secret, Path fileShown) {
        if (misconfigured != null) throw new IllegalStateException(misconfigured);
        List<String> reasons = new ArrayList<>();
        Optional<SecretBackend> destination = destination(reasons);
        if (destination.isPresent()) {
            SecretBackend backend = destination.get();
            try {
                backend.write(item, label, secret);
                Optional<String> back = backend.read(item);
                if (back.isPresent() && back.get().equals(secret)) {
                    Pointer pointer = new Pointer(backend.id(), item);
                    forget(pointer);
                    return new Stored(pointer, null);
                }
                reasons.add(backend.displayName() + ": the item did not read back as written");
                backend.delete(item);
            } catch (SecretBackendException refused) {
                reasons.add(refused.getMessage());
            }
        }
        if (choice == Choice.FILE) return new Stored(null, null);
        String why = String.join("; ", reasons);
        if (choice == Choice.KEYCHAIN) {
            throw new IllegalStateException("[credentials] store is \"keychain\", and no OS keychain took the secret ("
                    + why + "). Unlock or start the keychain, or set " + ENV + "=file.");
        }
        return new Stored(
                null,
                "jk: notice: no OS keychain answered (" + why + "), so the secret is in the owner-only file "
                        + fileShown
                        + ". Set [credentials] store = \"file\" in ~/.jk/config.toml to keep it there without this"
                        + " notice.");
    }

    /**
     * The secret {@code pointer} names: the one the client sent with this request, else the
     * keychain's answer to this process. {@code record} is the file the pointer was read from; a
     * keychain's answer is remembered until that file changes, so a build that resolves one
     * repository many times asks once.
     *
     * @throws SecretBackendException when the keychain is there and refuses
     */
    public Optional<String> resolve(Pointer pointer, Path record) {
        RequestKeychain carried = RequestKeychain.current();
        String sent = carried == null ? null : carried.get(pointer);
        if (sent != null) return Optional.of(sent);
        SecretBackend backend = backend(pointer.backend())
                .orElseThrow(() -> new SecretBackendException("this machine has no " + pointer.backend()
                        + " keychain (the record names one; run the login again here)"));
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(record);
        if (stamp == null) return backend.read(pointer.item());
        return Optional.ofNullable(READS.get(
                pointer.text(), stamp, () -> backend.read(pointer.item()).orElse(null)));
    }

    /**
     * Read {@code pointer} from the keychain directly, for the client to send: empty when the
     * keychain has no such item or refuses.
     */
    public Optional<String> readForRequest(Pointer pointer) {
        try {
            return backend(pointer.backend()).flatMap(b -> b.read(pointer.item()));
        } catch (SecretBackendException refused) {
            return Optional.empty();
        }
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
