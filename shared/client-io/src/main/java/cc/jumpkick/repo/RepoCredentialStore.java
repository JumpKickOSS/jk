// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.OwnerOnlyFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Per-repo credentials under {@code <home>/creds/repo/} ({@code jk repo login}); owner-only
 * modes. Line format: {@code <scheme> <origin>} then the fields ({@code bearer}: the token;
 * {@code basic}: username, password).
 *
 * <p>The origin — {@code scheme://host[:port]} of the repository the login was for — is part of
 * the record because the repository id alone is not a destination: a project's {@code jk.toml}
 * chooses which URL an id points at, and a stored credential is only ever sent to the origin it
 * was stored for. A file whose first line carries no origin reads back with a null one, and the
 * resolver then falls back to the bindings that apply to any name-keyed source.
 */
public final class RepoCredentialStore {

    /** One stored login: the credential and the origin it was stored for ({@code null} when unrecorded). */
    public record Entry(RepoCredential credential, @Nullable URI origin) {
        public Entry {
            Objects.requireNonNull(credential, "credential");
        }
    }

    private final Path dir;

    public RepoCredentialStore() {
        this(JkDirs.creds().resolve("repo"));
    }

    /** Visible for tests — point the store at a scratch directory. */
    public RepoCredentialStore(Path dir) {
        this.dir = dir;
    }

    public Optional<Entry> read(String repoId) {
        Path file = fileFor(repoId);
        try {
            if (!Files.exists(file)) return Optional.empty();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) return Optional.empty();
            String[] head = lines.get(0).strip().split("\\s+", 2);
            String scheme = head[0].toLowerCase(Locale.ROOT);
            URI origin = head.length > 1 ? parseOrigin(head[1]) : null;
            Optional<RepoCredential> credential =
                    switch (scheme) {
                        case "bearer" ->
                            lines.size() >= 2 && !lines.get(1).isBlank()
                                    ? Optional.of(new RepoCredential.Bearer(
                                            lines.get(1).strip()))
                                    : Optional.empty();
                        case "basic" ->
                            lines.size() >= 3
                                    ? Optional.of(new RepoCredential.Basic(lines.get(1), lines.get(2)))
                                    : Optional.empty();
                        default -> Optional.empty();
                    };
            return credential.map(c -> new Entry(c, origin));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Store {@code cred} for {@code repoId}, bound to the origin of {@code url}. Only the origin is
     * kept: two repositories on one host share a credential the way a browser's do.
     */
    public void write(String repoId, RepoCredential cred, URI url) {
        String origin = originOf(url).toString();
        String body =
                switch (cred) {
                    case RepoCredential.Bearer b -> "bearer " + origin + "\n" + b.token() + "\n";
                    case RepoCredential.Basic b -> "basic " + origin + "\n" + b.username() + "\n" + b.password() + "\n";
                    case RepoCredential.Anonymous ignored ->
                        throw new IllegalArgumentException("refusing to store an anonymous credential");
                };
        Path file = fileFor(repoId);
        try {
            OwnerOnlyFiles.write(dir, file, body);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to store credential for " + repoId, e);
        }
    }

    public void clear(String repoId) {
        try {
            Files.deleteIfExists(fileFor(repoId));
        } catch (IOException ignored) {
            // best-effort
        }
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
