// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.templates;

import cc.jumpkick.config.JkTemplatesConfig;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Shallow clone / fetch of the official Giter8 monorepo ({@code jkbuild/jk-templates}) for engine
 * self-heal and short-name resolution. Fail-fast, quiet, no retries — next 12 h cycle or engine
 * restart will try again.
 *
 * <p>Cache layout matches CLI {@code Giter8Git} keys under {@code ~/.jk/cache/templates/} (and
 * XDG {@link JkDirs#cache()}{@code /templates} as a secondary root when present).
 */
public final class OfficialTemplatesFreshen {

    private OfficialTemplatesFreshen() {}

    /** Best-effort freshen; never throws. Network / missing git → silent skip. */
    public static void refreshQuiet(Consumer<String> log) {
        if (log == null) log = s -> {};
        try {
            refresh(JkTemplatesConfig.resolve(), log);
        } catch (Throwable t) {
            // Quiet: one short line only when something unexpected is worth a breadcrumb.
            String m = t.getMessage();
            if (m != null && !m.isBlank() && m.length() < 120) {
                log.accept("jk engine: templates freshen skipped (" + t.getClass().getSimpleName() + ")");
            }
        }
    }

    static void refresh(JkTemplatesConfig config, Consumer<String> log) throws IOException {
        JkTemplatesConfig cfg = config == null ? JkTemplatesConfig.defaults() : config;
        String ref = cfg.officialUrl();
        if (ref == null || ref.isBlank()) ref = JkTemplatesConfig.DEFAULT_OFFICIAL;
        Path cacheRoot = primaryCacheRoot();
        Files.createDirectories(cacheRoot);
        Parsed p = parse(ref);
        Path dest = cacheRoot.resolve(p.cacheKey());
        if (!Files.isDirectory(dest) || isEmptyDir(dest)) {
            if (Files.exists(dest)) deleteRecursively(dest);
            Files.createDirectories(dest.getParent());
            runGit(p.cloneArgs(dest), 120);
            log.accept("jk engine: cloned official templates (" + dest.getFileName() + ")");
            return;
        }
        // Existing shallow clone: cheap fetch + hard reset (no merge noise).
        List<String> fetch = new ArrayList<>();
        fetch.add("git");
        fetch.add("-C");
        fetch.add(dest.toString());
        fetch.add("fetch");
        fetch.add("--depth");
        fetch.add("1");
        fetch.add("origin");
        if (p.rev() != null && !p.rev().isBlank()) {
            fetch.add(p.rev());
        }
        runGit(fetch, 60);
        List<String> reset = List.of("git", "-C", dest.toString(), "reset", "--hard", "FETCH_HEAD");
        runGit(reset, 30);
    }

    /** Prefer legacy {@code ~/.jk/cache/templates} (CLI Giter8Git), else XDG cache. */
    public static Path primaryCacheRoot() {
        Path legacy = Path.of(System.getProperty("user.home"), ".jk", "cache", "templates");
        if (Files.isDirectory(legacy.getParent()) || Files.isDirectory(legacy)) return legacy;
        return JkDirs.cache().resolve("templates");
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    static void runGit(List<String> args, int timeoutSec) throws IOException {
        // Discard output at the OS level; reading the pipe inline would block past the
        // timeout on a stalled fetch (the single maintenance thread must never hang).
        ProcessBuilder pb = new ProcessBuilder(args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("git not on PATH", e);
        }
        try {
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("git timed out");
            }
            if (proc.exitValue() != 0) throw new IOException("git exit " + proc.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("git interrupted", e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted((a, b) -> b.compareTo(a)).toList();
            for (Path p : paths) Files.deleteIfExists(p);
        }
    }

    static Parsed parse(String ref) {
        String r = ref.strip();
        String url = r;
        String rev = null;
        int hash = r.lastIndexOf('#');
        if (hash > 0) {
            url = r.substring(0, hash);
            rev = r.substring(hash + 1);
        }
        if (!url.endsWith(".git")
                && (url.startsWith("https://github.com/") || url.startsWith("http://github.com/"))) {
            url = url + ".git";
        }
        return new Parsed(url, rev, cacheKeyForUrl(url, rev));
    }

    private static String cacheKeyForUrl(String url, String rev) {
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

    record Parsed(String url, String rev, String cacheKey) {
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
