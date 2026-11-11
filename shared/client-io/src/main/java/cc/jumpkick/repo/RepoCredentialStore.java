// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.secret.SecretBackendException;
import cc.jumpkick.secret.SecretItems;
import cc.jumpkick.secret.SecretVault;
import cc.jumpkick.task.RunNotices;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
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
 * Per-repo logins under {@code <home>/creds/repo/} ({@code jk repo login}); owner-only modes. One
 * record per repository: {@code <scheme> <origin>} on the first line, then the fields ({@code
 * bearer}: the token; {@code basic}: username, password).
 *
 * <p>When an OS keychain holds the secret, the first line ends in {@code keychain} and the secret's
 * line is a {@link SecretVault.Pointer} instead ({@code secret-service repo/nexus}): the record
 * keeps the binding and the username, the keychain keeps the token or password. Which one a login
 * uses is {@link SecretVault}'s choice; a record of either shape reads.
 *
 * <p>The origin — {@code scheme://host[:port]} of the repository the login was for — is part of
 * the record because the repository id alone is not a destination: a project's {@code jk.toml}
 * chooses which URL an id points at, and a stored credential is only ever sent to the origin it
 * was stored for. A file whose first line carries no origin reads back with a null one, and the
 * resolver then falls back to the bindings that apply to any name-keyed source.
 */
public final class RepoCredentialStore {

    /** The first line's last word when the secret is in a keychain. */
    static final String KEYCHAIN = "keychain";

    /** One stored login: the credential and the origin it was stored for ({@code null} when unrecorded). */
    public record Entry(RepoCredential credential, @Nullable URI origin) {
        public Entry {
            Objects.requireNonNull(credential, "credential");
        }
    }

    /**
     * One record as {@code jk doctor} lists it, without its secret: the file's name, the scheme,
     * the origin, and the keychain pointer when a keychain holds the secret.
     */
    public record Login(
            String name, String scheme, @Nullable URI origin, SecretVault.@Nullable Pointer pointer) {}

    private final Path dir;
    private final SecretVault vault;

    public RepoCredentialStore() {
        this(JkDirs.creds().resolve("repo"), SecretVault.current());
    }

    /**
     * A store in {@code dir} that keeps every secret in its record and follows no keychain pointer:
     * a scratch directory ({@code --credentials-dir}, tests) never reaches this machine's keychain.
     */
    public RepoCredentialStore(Path dir) {
        this(dir, SecretVault.fileOnly());
    }

    public RepoCredentialStore(Path dir, SecretVault vault) {
        this.dir = Objects.requireNonNull(dir, "dir");
        this.vault = Objects.requireNonNull(vault, "vault");
    }

    /**
     * The login stored for {@code repoId}. A record whose secret is in a keychain the keychain
     * cannot answer for (refused, or the item is gone) reads as absent, with a warning naming
     * the repository and the remedy.
     */
    public Optional<Entry> read(String repoId) {
        Path file = fileFor(repoId);
        Optional<Record> parsed = parse(file);
        if (parsed.isEmpty()) return Optional.empty();
        Record record = parsed.get();
        SecretVault.Pointer pointer = record.pointer();
        String secret = record.secret();
        if (pointer != null) {
            secret = keychainSecret(repoId, pointer, file);
            if (secret == null) return Optional.empty();
        }
        RepoCredential credential = record.username() == null
                ? new RepoCredential.Bearer(secret.strip())
                : new RepoCredential.Basic(record.username(), secret);
        return Optional.of(new Entry(credential, record.origin()));
    }

    private @Nullable String keychainSecret(String repoId, SecretVault.Pointer pointer, Path file) {
        String why;
        try {
            Optional<String> secret = vault.resolve(pointer, file);
            if (secret.isPresent()) return secret.get();
            why = "it holds no item " + pointer.item();
        } catch (SecretBackendException refused) {
            why = refused.getMessage();
        }
        String reason = why;
        RunNotices.warnOnce(
                "repo-credential-keychain:" + repoId,
                () -> "jk: warning: " + repoId + ": could not read the secret from the "
                        + SecretVault.displayName(pointer.backend()) + ": " + reason + "; run jk repo login " + repoId);
        return null;
    }

    /**
     * Store {@code cred} for {@code repoId}, bound to the origin of {@code url}. Only the origin is
     * kept: two repositories on one host share a credential the way a browser's do. The secret goes
     * where {@link SecretVault} puts it; a keychain item a previous login left behind and this one
     * no longer uses is removed.
     *
     * @return where the secret went: the keychain pointer, or none for the record itself, and the
     *     notice to show when a keychain refused
     * @throws IllegalStateException when the vault insists on a keychain that did not take it
     */
    public SecretVault.Stored write(String repoId, RepoCredential cred, URI url) {
        String origin = originOf(url).toString();
        Path file = fileFor(repoId);
        SecretVault.@Nullable Pointer previous =
                parse(file).map(Record::pointer).orElse(null);
        boolean replacing = Files.exists(file);
        String secret;
        String head;
        switch (cred) {
            case RepoCredential.Bearer b -> {
                secret = b.token();
                head = "bearer " + origin;
            }
            case RepoCredential.Basic b -> {
                secret = b.password();
                head = "basic " + origin + "\n" + b.username();
            }
            case RepoCredential.Anonymous ignored ->
                throw new IllegalArgumentException("refusing to store an anonymous credential");
        }
        SecretVault.Stored stored =
                vault.store(SecretItems.of("repo", repoId), "jk repo login " + repoId, secret, file);
        SecretVault.Pointer pointer = stored.pointer();
        String body =
                pointer == null ? head + "\n" + secret + "\n" : withKeychainMark(head) + "\n" + pointer.text() + "\n";
        try {
            OwnerOnlyFiles.write(dir, file, body);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to store credential for " + repoId, e);
        }
        String user = cred instanceof RepoCredential.Basic b ? "user " + b.username() + ", password ***" : "token ***";
        ChangeJournal.note(
                "credential for repository " + repoId,
                Change.STORE,
                (replacing ? "replaced" : "stored") + " the credential for repository " + repoId + " at " + origin,
                user + ", kept in "
                        + (pointer == null
                                ? "the file " + file.getFileName()
                                : SecretVault.displayName(pointer.backend())));
        if (previous != null && !previous.equals(pointer)) {
            try {
                vault.remove(previous);
            } catch (SecretBackendException leftBehind) {
                String note = stored.notice() == null ? "" : stored.notice() + "\n";
                // The record no longer points at it; the item is inert, and the notice says so.
                return new SecretVault.Stored(
                        pointer,
                        note + "jk: notice: the previous secret is still in the "
                                + SecretVault.displayName(previous.backend()) + " as " + previous.item() + " ("
                                + leftBehind.getMessage() + "); remove it there.");
            }
        }
        return stored;
    }

    /** The first line, marked as pointing into a keychain. */
    private static String withKeychainMark(String head) {
        int nl = head.indexOf('\n');
        return nl < 0 ? head + " " + KEYCHAIN : head.substring(0, nl) + " " + KEYCHAIN + head.substring(nl);
    }

    /**
     * Forget the login for {@code repoId}: its keychain item, then its record. A keychain that
     * refuses keeps the record, so the logout can be run again once the keychain answers.
     *
     * @throws SecretBackendException when the keychain refuses to remove the item
     */
    public void clear(String repoId) {
        Path file = fileFor(repoId);
        Optional<SecretVault.Pointer> pointer = parse(file).map(Record::pointer);
        if (pointer.isPresent()) vault.remove(pointer.get());
        try {
            if (Files.deleteIfExists(file)) {
                ChangeJournal.note(
                        "credential for repository " + repoId,
                        Change.CLEAR,
                        "removed the credential for repository " + repoId);
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** Every record in the store, by file name, without a secret. */
    public List<Login> logins() {
        List<Login> out = new ArrayList<>();
        for (Path file : SecretVault.recordFiles(dir)) {
            parse(file)
                    .ifPresent(r -> out.add(
                            new Login(String.valueOf(file.getFileName()), r.scheme(), r.origin(), r.pointer())));
        }
        return List.copyOf(out);
    }

    /**
     * A record as written: {@code secret} is the token or password, or the pointer's text when
     * {@code pointer} is set; {@code username} is set for {@code basic} only.
     */
    private record Record(
            String scheme,
            @Nullable URI origin,
            @Nullable String username,
            String secret,
            SecretVault.@Nullable Pointer pointer) {}

    private static Optional<Record> parse(Path file) {
        List<String> lines;
        try {
            if (!Files.exists(file)) return Optional.empty();
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (lines.isEmpty()) return Optional.empty();
        String[] head = lines.get(0).strip().split("\\s+", 3);
        String scheme = head[0].toLowerCase(Locale.ROOT);
        URI origin = head.length > 1 ? parseOrigin(head[1]) : null;
        boolean inKeychain = head.length > 2 && head[2].strip().equalsIgnoreCase(KEYCHAIN);
        String username;
        String secret;
        switch (scheme) {
            case "bearer" -> {
                if (lines.size() < 2 || lines.get(1).isBlank()) return Optional.empty();
                username = null;
                secret = lines.get(1);
            }
            case "basic" -> {
                if (lines.size() < 3) return Optional.empty();
                username = lines.get(1);
                secret = lines.get(2);
            }
            default -> {
                return Optional.empty();
            }
        }
        SecretVault.Pointer pointer = null;
        if (inKeychain) {
            Optional<SecretVault.Pointer> parsed = SecretVault.Pointer.parse(secret);
            if (parsed.isEmpty()) return Optional.empty();
            pointer = parsed.get();
        }
        return Optional.of(new Record(scheme, origin, username, secret, pointer));
    }

    /**
     * {@code scheme://host[:port]} of {@code url}, lower-cased, with no path, query or userinfo —
     * the boundary a credential is scoped to.
     *
     * @throws IllegalArgumentException when {@code url} has no scheme or host
     */
    public static URI originOf(URI url) {
        Objects.requireNonNull(url, "url");
        if (url.getScheme() == null || url.getHost() == null) {
            throw new IllegalArgumentException("a repository URL needs a scheme and a host, got: " + url);
        }
        String origin = url.getScheme().toLowerCase(Locale.ROOT) + "://"
                + url.getHost().toLowerCase(Locale.ROOT) + (url.getPort() == -1 ? "" : ":" + url.getPort());
        return URI.create(origin);
    }

    private static @Nullable URI parseOrigin(String text) {
        try {
            URI parsed = URI.create(text.strip());
            return parsed.getScheme() == null || parsed.getHost() == null ? null : parsed;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private Path fileFor(String repoId) {
        return dir.resolve(sanitize(repoId));
    }

    /** Map a repo id to a safe single-path-segment filename. */
    static String sanitize(String id) {
        StringBuilder sb = new StringBuilder(id.length());
        for (char c : id.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append((Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') ? c : '_');
        }
        return sb.length() == 0 ? "_" : sb.toString();
    }
}
