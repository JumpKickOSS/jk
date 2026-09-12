// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.config.TomlScan;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Durable project identity for history, metrics, and dashboard routes.
 *
 * <p>Resolution order (first hit wins):
 *
 * <ol>
 *   <li>Explicit root-level {@code id} in {@code jk.toml} (rare override)
 *   <li>{@code project-id} in root {@code jk-lock.toml} (normal auto-id)
 *   <li>Recovered id from an existing {@code identity.toml} (path or git match — )
 *   <li>Git remote + path relative to worktree root
 *   <li>Absolute path (last resort)
 * </ol>
 *
 * <p>Coord ({@code group:name}) is display metadata and is deliberately absent from the hash
 * material — renaming a project must not split its identity.
 *
 * <p>The opaque {@link #id()} is URL-safe and is the sole key under {@code builds/projects/&lt;id&gt;/}.
 * Absolute path is operational (where to build), not the identity.
 */
public record ProjectIdentity(
        String id,
        String coord,
        Path path,
        Source source,
        @Nullable String gitRemote,
        @Nullable String gitRelPath) {

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
    public static ProjectIdentity resolve(@Nullable Path projectDir) {
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
        // unknown:unknown hash.
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

        // Coord (group:name) is display metadata, NOT identity material: hashing it in
        // would split a lockless project's identity on rename. The remote+relPath (GIT)
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
    public static Lockfile ensureProjectId(Lockfile lock, @Nullable Path projectDir) {
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
        return Hashing.hex(bytes);
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
                } catch (RuntimeException e) {
                    Log.debug("recoverId: RuntimeException ignored", e);
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

    /**
     * Display {@code group:name} from bootstrap TOML — no {@code JkBuildParser}. Missing group
     * inherits from the workspace root when this dir is a listed member.
     */
    public static String coordOf(Path projectDir) {
        Path dir = projectDir.toAbsolutePath().normalize();
        Path toml = dir.resolve(ManifestPaths.MANIFEST);
        var local = TomlScan.scan(toml, "group", "name");
        String g = blankToEmpty(local.get("group"));
        String n = blankToEmpty(local.get("name"));
        if (g.isEmpty()) {
            Optional<Path> root = WorkspaceScan.findRoot(dir);
            if (root.isPresent()) {
                String inherited = TomlScan.scan(root.get().resolve(ManifestPaths.MANIFEST), "group")
                        .get("group");
                g = blankToEmpty(inherited);
            }
        }
        if (g.isEmpty() && n.isEmpty()) return "unknown:unknown";
        return g + ":" + n;
    }

    private static String blankToEmpty(@Nullable String s) {
        return s == null || s.isBlank() ? "" : s.strip();
    }

    /**
     * Root-level {@code id} — an unadvertised escape hatch, deliberately not on {@code
     * Project}. Scanned, not parsed, for the same reason {@link #coordOf} is: identity is
     * resolved on the client for history and dashboard routes, and {@code checkCliNoParseTypes}
     * keeps {@link cc.jumpkick.config.JkBuildParser} — and tomlj with it — off the native image.
     */
    private static Optional<String> explicitId(Path projectDir) {
        String id =
                TomlScan.scan(projectDir.resolve(ManifestPaths.MANIFEST), "id").get("id");
        if (id == null || id.isBlank()) return Optional.empty();
        return Optional.of(normalizeId(id));
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
            // 32 hex chars (128 bits) — URL-safe, distinct from minted random ids only by content
            return Hashing.sha256Hex(material).substring(0, 32);
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

    private static @Nullable Path gitTopLevel(Path dir) {
        String out = git(dir, "rev-parse", "--show-toplevel");
        if (out == null || out.isBlank()) return null;
        Path p = Path.of(out.trim());
        return Files.isDirectory(p) ? p.toAbsolutePath().normalize() : null;
    }

    private static @Nullable String gitRemote(Path top) {
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

    private static @Nullable String git(Path cwd, String... args) {
        List<String> command = new ArrayList<>(args.length + 1);
        command.add("git");
        command.addAll(List.of(args));
        return run(cwd, GIT_TIMEOUT, command);
    }

    /** How long one git probe may take before the identity falls back to the path tier. */
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * {@code command}'s output (stderr merged) when it exits 0 within {@code timeout}; {@code null}
     * on a non-zero exit, a timeout, or a command that cannot start. The output is drained on its
     * own thread so the bound holds for a command that neither exits nor closes its pipe — a git
     * blocked on a credential prompt or a hung filesystem must not hang a lock write with it. A
     * timed-out command is killed with its descendants.
     */
    static @Nullable String run(Path cwd, Duration timeout, List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(
                    () -> {
                        try (var in = p.getInputStream()) {
                            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            return "";
                        }
                    },
                    r -> Thread.ofPlatform().daemon().name("jk-identity-probe").start(r));
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            return output.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** Sidecar written under {@code builds/projects/<id>/identity.toml}. */
    public record IdentityFile(
            String id,
            @Nullable String coord,
            String path,
            @Nullable String source,
            @Nullable String gitRemote,
            @Nullable String gitRelPath) {

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
                    String v = MinimalToml.unquote(line.substring(eq + 1).trim());
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
                if (path == null || id == null || id.isBlank()) return Optional.empty();
                return Optional.of(new IdentityFile(id, coord, path, source, remote, rel));
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        public static void write(Path projectHome, ProjectIdentity identity) throws IOException {
            Files.createDirectories(projectHome);
            StringBuilder b = new StringBuilder();
            b.append("id = ").append(MinimalToml.quote(identity.id())).append('\n');
            b.append("coord = ").append(MinimalToml.quote(identity.coord())).append('\n');
            b.append("path = ")
                    .append(MinimalToml.quote(identity.path().toString()))
                    .append('\n');
            b.append("source = ")
                    .append(MinimalToml.quote(identity.source().name().toLowerCase(Locale.ROOT)))
                    .append('\n');
            if (identity.gitRemote() != null) {
                b.append("git-remote = ")
                        .append(MinimalToml.quote(identity.gitRemote()))
                        .append('\n');
            }
            if (identity.gitRelPath() != null) {
                b.append("git-rel-path = ")
                        .append(MinimalToml.quote(identity.gitRelPath()))
                        .append('\n');
            }
            AtomicWrites.replace(projectHome.resolve(ProjectBuilds.IDENTITY), b.toString());
        }
    }
}
