// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import cc.jumpkick.secret.SecretBackendException;
import cc.jumpkick.secret.SecretItems;
import cc.jumpkick.secret.SecretVault;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.OwnerOnlyFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Persists forge tokens, keyed by host, under {@code <home>/creds/forge/}. One file per host
 * (filename = sanitized host), so a developer can be logged into {@code github.com}, a private GHE,
 * and {@code codeberg.org} at once.
 *
 * <p>The file holds the token itself, or, when an OS keychain holds it, {@code keychain <backend>
 * <item>} — a {@link SecretVault.Pointer} behind a word no token starts with followed by a space.
 * Which one a login uses is {@link SecretVault}'s choice; a file of either shape reads.
 *
 * <p>The directory is created {@code rwx------} and each token file {@code rw-------} on POSIX; on
 * Windows the permission tightening is a best-effort no-op (the user profile dir is already
 * per-user).
 */
public final class TokenStore {

    private static final String KEYCHAIN = "keychain ";

    /** One stored forge login as {@code jk doctor} lists it: the host file and its pointer, if any. */
    public record Login(String host, SecretVault.@Nullable Pointer pointer) {}

    private final Path dir;
    private final SecretVault vault;

    public TokenStore() {
        this(JkDirs.creds().resolve("forge"), SecretVault.current());
    }

    /**
     * A store in {@code dir} that keeps every token in its file and follows no keychain pointer: a
     * scratch directory ({@code --credentials-dir}, tests) never reaches this machine's keychain.
     */
    public TokenStore(Path dir) {
        this(dir, SecretVault.fileOnly());
    }

    public TokenStore(Path dir, SecretVault vault) {
        this.dir = Objects.requireNonNull(dir, "dir");
        this.vault = Objects.requireNonNull(vault, "vault");
    }

    public Optional<String> read(String host) {
        Path file = fileFor(host);
        String content = contentOf(file);
        if (content == null) return Optional.empty();
        Optional<SecretVault.Pointer> pointer = pointerIn(content);
        if (pointer.isEmpty()) return Optional.of(content);
        String why;
        try {
            Optional<String> token = vault.resolve(pointer.get(), file).map(String::strip);
            if (token.isPresent() && !token.get().isEmpty()) return token;
            why = "it holds no item " + pointer.get().item();
        } catch (SecretBackendException refused) {
            why = refused.getMessage();
        }
        String reason = why;
        RunNotices.warnOnce(
                "forge-token-keychain:" + host,
                () -> "jk: warning: could not read the token for " + host + " from the "
                        + SecretVault.displayName(pointer.get().backend()) + ": " + reason + "; run jk auth login");
        return Optional.empty();
    }

    /**
     * Store {@code token} for {@code host} where {@link SecretVault} puts it.
     *
     * @return where the token went: the keychain pointer, or none for the file itself, and the
     *     notice to show when a keychain refused
     */
    public SecretVault.Stored write(String host, String token) {
        Path file = fileFor(host);
        String existing = contentOf(file);
        SecretVault.Pointer previous =
                existing == null ? null : pointerIn(existing).orElse(null);
        SecretVault.Stored stored = vault.store(
                SecretItems.of("forge", ForgeKind.normalizeHost(host)), "jk auth login " + host, token, file);
        SecretVault.Pointer pointer = stored.pointer();
        try {
            OwnerOnlyFiles.write(dir, file, pointer == null ? token : KEYCHAIN + pointer.text() + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("failed to store credential for " + host, e);
        }
        if (previous != null && !previous.equals(pointer)) {
            try {
                vault.remove(previous);
            } catch (SecretBackendException leftBehind) {
                String note = stored.notice() == null ? "" : stored.notice() + "\n";
                return new SecretVault.Stored(
                        pointer,
                        note + "jk: notice: the previous token is still in the "
                                + SecretVault.displayName(previous.backend()) + " as " + previous.item() + " ("
                                + leftBehind.getMessage() + "); remove it there.");
            }
        }
        return stored;
    }

    /**
     * Forget the token for {@code host}: its keychain item, then its file. A keychain that refuses
     * keeps the file, so the logout can be run again once the keychain answers.
     *
     * @throws SecretBackendException when the keychain refuses to remove the item
     */
    public void clear(String host) {
        Path file = fileFor(host);
        String content = contentOf(file);
        if (content != null) {
            Optional<SecretVault.Pointer> pointer = pointerIn(content);
            if (pointer.isPresent()) vault.remove(pointer.get());
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** Every stored forge login, by host file, without a token. */
    public List<Login> logins() {
        List<Login> out = new ArrayList<>();
        for (Path file : SecretVault.recordFiles(dir)) {
            String content = contentOf(file);
            if (content != null)
                out.add(new Login(
                        String.valueOf(file.getFileName()), pointerIn(content).orElse(null)));
        }
        return List.copyOf(out);
    }

    private static @Nullable String contentOf(Path file) {
        try {
            if (!Files.exists(file)) return null;
            String content = Files.readString(file, StandardCharsets.UTF_8).strip();
            return content.isBlank() ? null : content;
        } catch (IOException e) {
            return null;
        }
    }

    private static Optional<SecretVault.Pointer> pointerIn(String content) {
        if (!content.startsWith(KEYCHAIN)) return Optional.empty();
        return SecretVault.Pointer.parse(content.substring(KEYCHAIN.length()));
    }

    private Path fileFor(String host) {
        return dir.resolve(sanitize(ForgeKind.normalizeHost(host)));
    }

    /** Map a host to a safe single-path-segment filename. */
    static String sanitize(String host) {
        StringBuilder sb = new StringBuilder(host.length());
        for (char c : host.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append((Character.isLetterOrDigit(c) || c == '.' || c == '-') ? c : '_');
        }
        return sb.toString();
    }
}
