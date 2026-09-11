// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Fetch remote Giter8 templates via {@code git clone} into {@link JkDirs#templates()}.
 *
 * <p>Supported refs:
 *
 * <ul>
 *   <li>{@code https://…} / {@code git@…} / {@code ssh://…} URIs (optional {@code #branch} or
 *       {@code @tag})
 *   <li>GitHub shorthand {@code owner/repo} or {@code owner/repo.g8}
 * </ul>
 *
 * <p>Requires {@code git} on {@code PATH}. Shallow clone ({@code --depth 1}).
 */
public final class Giter8Git {

    /** GitHub {@code owner/repo} — owner must start with a letter/digit (not {@code ./path}). */
    private static final Pattern GITHUB_SHORTHAND = Pattern.compile(
            "^(?<owner>[A-Za-z0-9][A-Za-z0-9_.-]*)/(?<repo>[A-Za-z0-9][A-Za-z0-9_.-]*?)(?:\\.g8)?(?:#(?<rev>.+))?$");

    private Giter8Git() {}

    /** True when {@code ref} looks like a git URI or {@code owner/repo} shorthand (not a short name). */
    public static boolean looksRemote(String ref) {
        if (ref == null || ref.isBlank()) return false;
        String r = ref.strip();
        if (r.startsWith("https://") || r.startsWith("http://") || r.startsWith("git@") || r.startsWith("ssh://")) {
            return true;
        }
        // Reject path-like refs (./x, ../x, absolute paths) before GitHub shorthand.
        if (r.startsWith(".") || r.startsWith("/") || r.contains("\\")) return false;
        // owner/repo — not a short catalog name
        return GITHUB_SHORTHAND.matcher(r).matches() && !Giter8Catalog.isShortName(r);
    }

    /**
     * Clone or reuse a cached clone for {@code ref}, then return a Giter8 template root under it.
     * Single-template repos return the clone (or its only nested {@code *.g8}); multi-template
     * monorepos return the first nested template found (prefer {@link #findNamedTemplate} for short
     * names).
     */
    public static Path fetch(String ref, Path cacheRoot) throws IOException {
        Path clone = ensureClone(ref, cacheRoot);
        if (Giter8Catalog.isTemplateRoot(clone)) return clone.toAbsolutePath().normalize();
        try (var stream = Files.list(clone)) {
            Optional<Path> nested = stream.filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().endsWith(".g8") || Giter8Catalog.isTemplateRoot(d))
                    .filter(Giter8Catalog::isTemplateRoot)
                    .findFirst();
            if (nested.isPresent()) return nested.get().toAbsolutePath().normalize();
        }
        throw new IOException(
                "git clone succeeded but no Giter8 layout (default.properties / src/main/g8) under " + clone);
    }

    /**
     * Ensure {@code ref} is shallow-cloned under {@code cacheRoot}; return the clone directory (not
     * necessarily a single-template root — monorepos keep all nested {@code *.g8} trees).
     */
    public static Path ensureClone(String ref, Path cacheRoot) throws IOException {
        Parsed p = parse(ref);
        Files.createDirectories(cacheRoot);
        Path dest = cacheRoot.resolve(p.cacheKey());
        // Reuse cache when present and non-empty; re-clone if missing.
        if (!Files.isDirectory(dest) || isEmptyDir(dest)) {
            if (Files.exists(dest)) deleteRecursively(dest);
            Files.createDirectories(dest.getParent());
            runGit(p.cloneArgs(dest));
        }
        return dest.toAbsolutePath().normalize();
    }

    /**
     * Locate {@code shortName} inside a monorepo clone: {@code name.g8/}, {@code templates/name.g8/},
     * {@code name/}, {@code templates/name/}, or a one-level walk for matching {@code *.g8}.
     */
    public static Optional<Path> findNamedTemplate(Path cloneRoot, String shortName) throws IOException {
        if (cloneRoot == null || shortName == null || shortName.isBlank()) return Optional.empty();
        String name = shortName.strip();
        String dirG8 = name + ".g8";
        List<Path> candidates = new ArrayList<>();
        for (String lang : List.of("java", "kotlin", "groovy")) {
            candidates.add(cloneRoot.resolve(lang).resolve("none").resolve(dirG8));
            candidates.add(
                    cloneRoot.resolve("templates").resolve(lang).resolve("none").resolve(dirG8));
            candidates.add(cloneRoot.resolve(lang).resolve(dirG8));
            candidates.add(cloneRoot.resolve("templates").resolve(lang).resolve(dirG8));
            candidates.add(cloneRoot.resolve(lang).resolve(name));
            candidates.add(cloneRoot.resolve("templates").resolve(lang).resolve(name));
        }
        candidates.addAll(List.of(
                cloneRoot.resolve(dirG8),
                cloneRoot.resolve("templates").resolve(dirG8),
                cloneRoot.resolve(name),
                cloneRoot.resolve("templates").resolve(name)));
        for (Path c : candidates) {
            if (Giter8Catalog.isTemplateRoot(c)) {
                return Optional.of(c.toAbsolutePath().normalize());
            }
        }
        if (!Files.isDirectory(cloneRoot)) return Optional.empty();
        try (var stream = Files.list(cloneRoot)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(child)) continue;
                String fn = child.getFileName().toString();
                if (fn.equals(dirG8) || fn.equals(name)) {
                    if (Giter8Catalog.isTemplateRoot(child)) {
                        return Optional.of(child.toAbsolutePath().normalize());
                    }
                }
            }
        }
        // Nested templates/ only (already tried exact path; walk for deeper layouts is out of scope).
        return Optional.empty();
    }

    /** Default clone root: {@link JkDirs#templates()}. */
    public static Path defaultCacheRoot() {
        return JkDirs.templates();
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    static Parsed parse(String ref) throws IOException {
        String r = ref.strip();
        if (r.startsWith("https://") || r.startsWith("http://") || r.startsWith("git@") || r.startsWith("ssh://")) {
            String url = r;
            String rev = null;
            int hash = r.lastIndexOf('#');
            int at = r.lastIndexOf('@');
            // Prefer #branch; @tag only when not part of git@host
            if (hash > 0) {
                url = r.substring(0, hash);
                rev = r.substring(hash + 1);
            } else if (at > 0 && !r.startsWith("git@")) {
                url = r.substring(0, at);
                rev = r.substring(at + 1);
            }
            return new Parsed(url, rev, cacheKeyForUrl(url, rev));
        }
        Matcher m = GITHUB_SHORTHAND.matcher(r);
        if (!m.matches()) {
            throw new IOException("not a git template ref: " + ref);
        }
        String owner = m.group("owner");
        String repo = m.group("repo");
        String rev = m.group("rev");
        String url = "https://github.com/" + owner + "/" + repo + ".git";
        return new Parsed(url, rev, cacheKeyForUrl(url, rev));
    }

    private static String cacheKeyForUrl(String url, @Nullable String rev) {
        String base = url.toLowerCase(Locale.ROOT)
                .replaceAll("^https?://", "")
                .replaceAll("^git@", "")
                .replaceAll("\\.git$", "")
                .replaceAll("[^a-z0-9._-]+", "_");
        if (rev != null && !rev.isBlank()) {
            base = base + "_" + rev.replaceAll("[^a-zA-Z0-9._-]+", "_");
        }
        return base;
    }

    private static void runGit(List<String> args) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("git is required for remote templates (not found on PATH): " + e.getMessage(), e);
        }
        String out;
        try (var in = proc.getInputStream()) {
            out = new String(in.readAllBytes());
        }
        try {
            boolean finished = proc.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new IOException("git clone timed out after 120s");
            }
            if (proc.exitValue() != 0) {
                String msg = out == null || out.isBlank() ? "(no output)" : out.strip();
                if (msg.length() > 400) msg = msg.substring(0, 400) + "…";
                throw new IOException("git clone failed: " + msg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("git clone interrupted", e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted((a, b) -> b.compareTo(a)).toList();
            for (Path p : paths) Files.deleteIfExists(p);
        }
    }

    record Parsed(String url, @Nullable String rev, String cacheKey) {
        List<String> cloneArgs(Path dest) {
            List<String> args = new ArrayList<>();
            args.add("git");
            args.add("clone");
            args.add("--depth");
            args.add("1");
            if (rev != null && !rev.isBlank()) {
                args.add("--branch");
                args.add(rev);
            }
            args.add(url);
            args.add(dest.toString());
            return args;
        }
    }
}
