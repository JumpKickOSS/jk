// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Durable project identity for history, metrics, and dashboard routes.
 *
 * <p>Resolution order (first hit wins):
 *
 * <ol>
 *   <li>Explicit {@code [project] id} in {@code jk.toml} (rare override)
 *   <li>{@code project-id} in root {@code jk-lock.toml} (normal auto-id)
 *   <li>Recovered id from an existing {@code identity.toml} (path or git match — JK-1794)
 *   <li>Git remote + path relative to worktree root
 *   <li>Absolute path (last resort)
 * </ol>
 *
 * <p>Coord ({@code group:name}) is display metadata and is deliberately absent from the hash
 * material — renaming a project must not split its identity (JK-1794).
 *
 * <p>The opaque {@link #id()} is URL-safe and is the sole key under {@code builds/projects/&lt;id&gt;/}.
 * Absolute path is operational (where to build), not the identity.
 */
public record ProjectIdentity(String id, String coord, Path path, Source source, String gitRemote, String gitRelPath) {

    public enum Source {
        EXPLICIT,
        LOCK,
        GIT,
        PATH
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    public ProjectIdentity {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id");
        if (coord == null || coord.isBlank()) coord = "unknown:unknown";
        path = path == null
                ? Path.of(".").toAbsolutePath().normalize()
                : path.toAbsolutePath().normalize();
        if (source == null) source = Source.PATH;
        id = normalizeId(id);
    }

    /** Resolve identity for a project or workspace root directory. */
    public static ProjectIdentity resolve(Path projectDir) {
        Path abs = projectDir == null
                ? Path.of(".").toAbsolutePath().normalize()
                : projectDir.toAbsolutePath().normalize();
        String coord = coordOf(abs);

        Optional<String> explicit = explicitId(abs);
        if (explicit.isPresent()) {
            return new ProjectIdentity(explicit.get(), coord, abs, Source.EXPLICIT, null, null);
        }

        Optional<String> lockId = lockId(abs);
        if (lockId.isPresent()) {
            return new ProjectIdentity(lockId.get(), coord, abs, Source.LOCK, null, null);
        }

        Optional<GitInfo> git = gitInfo(abs);

        // Before minting a hash, recover an id already recorded for this checkout (identity.toml
        // path or git match). This keeps locked/previously-built projects on one id even when the
        // checkout has no lock right now — including dead checkouts (deleted workspace) whose
        // history rows must still route to the existing project home, not a fresh
        // unknown:unknown hash (JK-1794).
        Optional<String> recovered = recoverId(abs, git);
        if (recovered.isPresent()) {
            if (git.isPresent()) {
                return new ProjectIdentity(
                        recovered.get(),
                        coord,
                        abs,
                        Source.GIT,
                        git.get().remote(),
                        git.get().relPath());
            }
            return new ProjectIdentity(recovered.get(), coord, abs, Source.PATH, null, null);
        }

        // Coord ([project] group:name) is display metadata, NOT identity material: hashing it in
        // would split a lockless project's identity on rename (JK-1794). The remote+relPath (GIT)
        // or the absolute path (PATH) alone are the identity.
        if (git.isPresent()) {
            GitInfo g = git.get();
            String material = "git\0" + g.remote() + "\0" + g.relPath();
            return new ProjectIdentity(hashId(material), coord, abs, Source.GIT, g.remote(), g.relPath());
        }

        String material = "path\0" + abs;
        return new ProjectIdentity(hashId(material), coord, abs, Source.PATH, null, null);
    }

    /**
     * Ensure a lock carries a durable {@code project-id}: preserve existing, recover from
     * history when possible, otherwise mint.
     */
    public static Lockfile ensureProjectId(Lockfile lock, Path projectDir) {
        if (lock == null) return null;
        if (lock.projectId() != null && !lock.projectId().isBlank()) {
            return lock;
        }
        Path abs = projectDir == null
                ? Path.of(".").toAbsolutePath().normalize()
                : projectDir.toAbsolutePath().normalize();
        Optional<String> recovered = recoverId(abs);
        String id = recovered.orElseGet(ProjectIdentity::mintId);
        return lock.withProjectId(id);
    }

    /** Mint a new random 128-bit hex id (32 chars). */
    public static String mintId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Normalize / validate an id string; reject path-like values. */
    public static String normalizeId(String raw) {
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > 128) {
            throw new IllegalArgumentException("invalid project id length");
        }
        if (s.indexOf('/') >= 0 || s.indexOf('\\') >= 0 || s.contains("..")) {
            throw new IllegalArgumentException("project id must not look like a path: " + raw);
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) throw new IllegalArgumentException("invalid project id char: " + raw);
        }
        return s;
    }

    public static boolean isValidId(String raw) {
        try {
            normalizeId(raw);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Find an existing project home whose identity matches this checkout (path or git), for
     * re-lock recovery after a wiped lockfile.
     */
    public static Optional<String> recoverId(Path projectDir) {
        Path abs = projectDir.toAbsolutePath().normalize();
        return recoverId(abs, gitInfo(abs));
    }

    /** {@link #recoverId(Path)} with git info the caller already computed (resolve's hot path). */
    private static Optional<String> recoverId(Path abs, Optional<GitInfo> git) {
        Path root = ProjectBuilds.projectsRoot();
        if (!Files.isDirectory(root)) return Optional.empty();
        // Prefer lock-sourced / higher-run homes when multiple ids claim the same path (re-key churn).
        String bestId = null;
        long bestScore = Long.MIN_VALUE;
        try (Stream<Path> homes = Files.list(root)) {
            for (Path home : homes.toList()) {
                if (!Files.isDirectory(home)) continue;
                Optional<IdentityFile> idf = IdentityFile.read(home);
                if (idf.isEmpty()) continue;
                IdentityFile f = idf.get();
                if (f.id() == null || f.id().isBlank()) continue;
                boolean pathMatch = false;
                try {
                    pathMatch = abs.equals(Path.of(f.path()).toAbsolutePath().normalize());
                } catch (RuntimeException ignored) {
                }
                boolean gitMatch = git.isPresent()
                        && f.gitRemote() != null
                        && f.gitRelPath() != null
                        && git.get().remote().equals(f.gitRemote())
                        && git.get().relPath().equals(f.gitRelPath());
                if (!pathMatch && !gitMatch) continue;
                long score = ProjectBuilds.metricsHomeScore(home, f);
                if (score > bestScore) {
                    bestScore = score;
                    bestId = f.id();
                }
            }
        } catch (IOException ignored) {
            // best-effort recovery
        }
        if (bestId == null) return Optional.empty();
        try {
            return Optional.of(normalizeId(bestId));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Locate project home by id under the builds root. */
    public static Optional<Path> homeForId(String id) {
        if (!isValidId(id)) return Optional.empty();
        Path home = ProjectBuilds.projectHome(normalizeId(id));
        return Files.isDirectory(home) ? Optional.of(home) : Optional.empty();
    }

    /** Last known absolute path for this id from identity.toml, if any. */
    public static Optional<Path> pathForId(String id) {
        return homeForId(id)
                .flatMap(IdentityFile::read)
                .map(f -> Path.of(f.path()).toAbsolutePath().normalize());
    }

    public static String coordOf(Path projectDir) {
        try {
            var project = JkBuildParser.parse(projectDir.resolve("jk.toml")).project();
            String g = project.group() == null ? "" : project.group();
            String n = project.name() == null ? "" : project.name();
            if (g.isBlank() && n.isBlank()) return "unknown:unknown";
            return g + ":" + n;
        } catch (Exception e) {
            return "unknown:unknown";
        }
    }

    private static Optional<String> explicitId(Path projectDir) {
        Path toml = projectDir.resolve("jk.toml");
        if (!Files.isRegularFile(toml)) return Optional.empty();
        try {
            // Read raw TOML so [project] id stays an unadvertised escape hatch without widening
            // JkBuild.Project (rare override; not on the happy-path model).
            var result = org.tomlj.Toml.parse(toml);
            var project = result.getTable("project");
            if (project == null) return Optional.empty();
            String id = project.getString("id");
            if (id == null || id.isBlank()) return Optional.empty();
            return Optional.of(normalizeId(id));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<String> lockId(Path projectDir) {
        Path lock = LockPaths.lockFile(projectDir);
        if (!Files.isRegularFile(lock)) return Optional.empty();
        try {
            Lockfile lf = LockfileReader.read(lock);
            if (lf.projectId() == null || lf.projectId().isBlank()) return Optional.empty();
            return Optional.of(normalizeId(lf.projectId()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String hashId(String material) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            // 32 hex chars (128 bits) — URL-safe, distinct from minted random ids only by content
            return HexFormat.of().formatHex(dig).substring(0, 32);
        } catch (Exception e) {
            return mintId();
        }
    }

    private record GitInfo(String remote, String relPath) {}

    private static Optional<GitInfo> gitInfo(Path projectDir) {
        Path top = gitTopLevel(projectDir);
        if (top == null) return Optional.empty();
        String remote = gitRemote(top);
        if (remote == null || remote.isBlank()) return Optional.empty();
        remote = canonicalizeRemote(remote);
        Path abs = projectDir.toAbsolutePath().normalize();
        Path rel;
        try {
            rel = top.relativize(abs);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String relStr = rel.toString().replace('\\', '/');
        if (relStr.isEmpty()) relStr = ".";
        return Optional.of(new GitInfo(remote, relStr));
    }

    private static Path gitTopLevel(Path dir) {
        String out = git(dir, "rev-parse", "--show-toplevel");
        if (out == null || out.isBlank()) return null;
        Path p = Path.of(out.trim());
        return Files.isDirectory(p) ? p.toAbsolutePath().normalize() : null;
    }

    private static String gitRemote(Path top) {
        String origin = git(top, "remote", "get-url", "origin");
        if (origin != null && !origin.isBlank()) return origin.trim();
        String remotes = git(top, "remote");
        if (remotes == null || remotes.isBlank()) return null;
        String first = remotes.lines()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse(null);
        if (first == null) return null;
        String url = git(top, "remote", "get-url", first);
        return url == null ? null : url.trim();
    }

    private static String canonicalizeRemote(String remote) {
        String r = remote.trim();
        // git@host:path.git → https://host/path (stable-ish)
        if (r.startsWith("git@")) {
            int colon = r.indexOf(':');
            if (colon > 4) {
                String host = r.substring(4, colon);
                String path = r.substring(colon + 1);
                if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
                r = "https://" + host + "/" + path;
            }
        }
        if (r.endsWith(".git")) r = r.substring(0, r.length() - 4);
        if (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r.toLowerCase(Locale.ROOT);
    }

    private static String git(Path cwd, String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            pb.command(new ArrayList<>() {
                {
                    add("git");
                    for (String a : args) add(a);
                }
            });
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out;
            try (BufferedReader br =
                    new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = br.lines().reduce((a, b) -> a + "\n" + b).orElse("");
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Sidecar written under {@code builds/projects/<id>/identity.toml}. */
    public record IdentityFile(
            String id, String coord, String path, String source, String gitRemote, String gitRelPath) {

        public static Optional<IdentityFile> read(Path projectHome) {
            Path f = projectHome.resolve(ProjectBuilds.IDENTITY);
            if (!Files.isRegularFile(f)) return Optional.empty();
            try {
                String id = null, coord = null, path = null, source = null, remote = null, rel = null;
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String k = line.substring(0, eq).trim();
                    String v = unquote(line.substring(eq + 1).trim());
                    switch (k) {
                        case "id" -> id = v;
                        case "coord" -> coord = v;
                        case "path" -> path = v;
                        case "source" -> source = v;
                        case "git-remote" -> remote = v;
                        case "git-rel-path" -> rel = v;
                        default -> {}
                    }
                }
                if (path == null) return Optional.empty();
                // Legacy files had only coord+path; directory name is the id.
                if (id == null || id.isBlank()) id = projectHome.getFileName().toString();
                return Optional.of(new IdentityFile(id, coord, path, source, remote, rel));
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        public static void write(Path projectHome, ProjectIdentity identity) throws IOException {
            Files.createDirectories(projectHome);
            StringBuilder b = new StringBuilder();
            b.append("id = ").append(q(identity.id())).append('\n');
            b.append("coord = ").append(q(identity.coord())).append('\n');
            b.append("path = ").append(q(identity.path().toString())).append('\n');
            b.append("source = ")
                    .append(q(identity.source().name().toLowerCase(Locale.ROOT)))
                    .append('\n');
            if (identity.gitRemote() != null) {
                b.append("git-remote = ").append(q(identity.gitRemote())).append('\n');
            }
            if (identity.gitRelPath() != null) {
                b.append("git-rel-path = ").append(q(identity.gitRelPath())).append('\n');
            }
            cc.jumpkick.util.AtomicWrites.replace(projectHome.resolve(ProjectBuilds.IDENTITY), b.toString());
        }

        private static String q(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        private static String unquote(String v) {
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                return v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
            }
            return v;
        }
    }
}
