// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.OwnerOnlyFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Per-repo credentials under {@code <home>/creds/repo/} ({@code jk repo login}); owner-only
 * modes. Line format: scheme then fields ({@code bearer}/{@code basic}).
 */
public final class RepoCredentialStore {

    private final Path dir;

    public RepoCredentialStore() {
        this(JkDirs.creds().resolve("repo"));
    }

    /** Visible for tests — point the store at a scratch directory. */
    public RepoCredentialStore(Path dir) {
        this.dir = dir;
    }

    public Optional<RepoCredential> read(String repoId) {
        Path file = fileFor(repoId);
        try {
            if (!Files.exists(file)) return Optional.empty();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) return Optional.empty();
            String scheme = lines.get(0).strip().toLowerCase(Locale.ROOT);
            return switch (scheme) {
                case "bearer" ->
                    lines.size() >= 2 && !lines.get(1).isBlank()
                            ? Optional.of(new RepoCredential.Bearer(lines.get(1).strip()))
                            : Optional.empty();
                case "basic" ->
                    lines.size() >= 3
                            ? Optional.of(new RepoCredential.Basic(lines.get(1), lines.get(2)))
                            : Optional.empty();
                default -> Optional.empty();
            };
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void write(String repoId, RepoCredential cred) {
        String body =
                switch (cred) {
                    case RepoCredential.Bearer b -> "bearer\n" + b.token() + "\n";
                    case RepoCredential.Basic b -> "basic\n" + b.username() + "\n" + b.password() + "\n";
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
