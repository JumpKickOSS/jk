// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.templates;

import cc.jumpkick.config.JkTemplatesConfig;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.StoreWriteGate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import java.util.function.Consumer;

/**
 * Shallow clone / fetch of the official Giter8 monorepo ({@code JumpKickOSS/jk-templates}) for engine
 * self-heal and short-name resolution. Fail-fast, quiet, no retries — next 12 h cycle or engine
 * restart will try again.
 *
 * <p>Clones land under {@link JkDirs#templates()} ({@code <store>/templates}), one subdirectory
 * per source cache key.
 */
public final class OfficialTemplatesFreshen {

    /**
     * Last freshen attempt (success or failure) per cache key. Short-name resolution calls
     * {@link #refreshQuiet} from the engine's request path: without this guard an offline
     * host would re-run a 60–120 s git attempt on every retry of a missing template.
     */
    private static final ConcurrentHashMap<String, Long> LAST_ATTEMPT_NANOS = new ConcurrentHashMap<>();

    static final long ATTEMPT_TTL_NANOS = TimeUnit.MINUTES.toNanos(10);

    private OfficialTemplatesFreshen() {}

    /**
     * Best-effort freshen; never throws. Network / missing git → silent skip. Attempts are rate
     * limited to one per cache key per {@link #ATTEMPT_TTL_NANOS} (success <em>or</em> failure) —
     * for the 12 h maintenance cycle and engine-start warmup, both long-lived-process callers where
     * the guard actually protects against a hung run retrying itself. {@link #refreshNow} is the
     * on-demand counterpart for a real user action ({@code jk new}/{@code init}): every call
     * attempts a real fetch, no TTL.
     */
    public static void refreshQuiet(Consumer<String> log) {
        if (log == null) log = s -> {};
        // Hygiene freshens stand down once the store was wiped — a clone landing after the wipe
        // recreates the store the nuke reported gone. refreshNow (a real user action) still runs.
        if (StoreWriteGate.wipedSinceStart()) return;
        try {
            JkTemplatesConfig cfg = JkTemplatesConfig.resolve();
            if (!markAttempt(parse(officialRef(cfg)).cacheKey(), System.nanoTime())) return;
            refresh(cfg, log);
        } catch (Throwable t) {
            // Quiet: one short line only when something unexpected is worth a breadcrumb.
            String m = t.getMessage();
            if (m != null && !m.isBlank() && m.length() < 120) {
                log.accept(
                        "jk engine: templates freshen skipped (" + t.getClass().getSimpleName() + ")");
            }
        }
    }

    /**
     * On-demand freshen for a real user action ({@code jk new}/{@code init}, engine-hosted via
     * {@code FRESHEN_CATALOG_REQUEST}): always attempts a fetch — no TTL guard, since the caller is
     * the engine (already the single long-lived process; a CLI-process-local TTL here would reset
     * on every invocation and never actually gate anything). Best-effort; never throws.
     */
    public static void refreshNow(Consumer<String> log) {
        if (log == null) log = s -> {};
        try {
            refresh(JkTemplatesConfig.resolve(), log);
        } catch (Throwable t) {
            String m = t.getMessage();
            if (m != null && !m.isBlank() && m.length() < 120) {
                log.accept(
                        "jk engine: templates freshen skipped (" + t.getClass().getSimpleName() + ")");
            }
        }
    }

    /** True when the caller won the attempt slot (none in the last TTL); atomically records it. */
    static boolean markAttempt(String cacheKey, long nowNanos) {
        boolean[] won = {false};
        LAST_ATTEMPT_NANOS.compute(cacheKey, (k, last) -> {
            if (last != null && nowNanos - last < ATTEMPT_TTL_NANOS) return last;
            won[0] = true;
            return nowNanos;
        });
        return won[0];
    }

    static void resetAttemptGuardForTests() {
        LAST_ATTEMPT_NANOS.clear();
    }

    static String officialRef(JkTemplatesConfig config) {
        JkTemplatesConfig cfg = config == null ? JkTemplatesConfig.defaults() : config;
        String ref = cfg.officialUrl();
        return ref == null || ref.isBlank() ? JkTemplatesConfig.DEFAULT_OFFICIAL : ref;
    }

    /**
     * Freshen the official catalog plus every {@code [templates.sources]} entry. Each source is
     * independent: one failing clone (auth, typo, offline mirror) must not block the others, so
     * the first failure is rethrown only after every ref got its attempt.
     */
    static void refresh(JkTemplatesConfig config, Consumer<String> log) throws IOException {
        // Clones land inside the store — a wipe must not overlap the git subprocess.
        try (var held = StoreWriteGate.write()) {
            JkTemplatesConfig cfg = config == null ? JkTemplatesConfig.defaults() : config;
            Path cacheRoot = JkDirs.templates();
            Files.createDirectories(cacheRoot);
            List<String> refs = new ArrayList<>();
            refs.add(officialRef(cfg));
            for (JkTemplatesConfig.Source source : cfg.sources()) {
                refs.add(source.gitRef());
            }
            IOException first = null;
            for (String ref : refs) {
                try {
                    refreshRef(ref, cacheRoot, log);
                } catch (IOException e) {
                    if (first == null) first = e;
                }
            }
            if (first != null) throw first;
        }
    }

    static void refreshRef(String ref, Path cacheRoot, Consumer<String> log) throws IOException {
        Parsed p = parse(ref);
        Path dest = cacheRoot.resolve(p.cacheKey());
        // Incomplete clones (e.g. only a .git dir left from a failed private-repo attempt) must be
        // wiped and re-cloned — fetch/reset cannot recover them.
        if (!Files.isDirectory(dest)
                || isEmptyDir(dest)
                || isLegacyLangKindLayout(dest)
                || !looksLikeTemplateMonorepo(dest)) {
            if (Files.exists(dest)) deleteRecursively(dest);
            Files.createDirectories(dest.getParent());
            runGit(p.cloneArgs(dest), 120);
            log.accept("jk engine: cloned templates source (" + dest.getFileName() + ")");
            return;
        }
        // Existing shallow clone: cheap fetch + hard reset (no merge noise).
        try {
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
        } catch (IOException fetchFailed) {
            // Corrupt / auth-skewed cache: delete and clone clean.
            deleteRecursively(dest);
            Files.createDirectories(dest.getParent());
            runGit(p.cloneArgs(dest), 120);
            log.accept("jk engine: re-cloned templates source (" + dest.getFileName() + ")");
        }
    }

    /**
     * True when {@code dest} looks like a usable templates monorepo
     * ({@code <lang>/<framework>/*.g8}). A bare {@code .git} from a failed clone is not.
     */
    static boolean looksLikeTemplateMonorepo(Path dest) {
        if (dest == null || !Files.isDirectory(dest)) return false;
        if (isLegacyLangKindLayout(dest)) return false;
        for (String lang : List.of("java", "kotlin", "groovy")) {
            Path langDir = dest.resolve(lang);
            if (!Files.isDirectory(langDir)) continue;
            try (var frameworks = Files.list(langDir)) {
                if (frameworks.anyMatch(fw -> Files.isDirectory(fw)
                        && !fw.getFileName().toString().startsWith(".")
                        && !fw.getFileName().toString().endsWith(".g8")
                        && hasG8Child(fw))) {
                    return true;
                }
            } catch (IOException ignored) {
                // try next lang
            }
        }
        Path nested = dest.resolve("templates");
        return Files.isDirectory(nested) && looksLikeTemplateMonorepo(nested);
    }

    /** Old {@code <lang>/<name>.g8} catalog — wipe and re-clone. */
    static boolean isLegacyLangKindLayout(Path dest) {
        if (dest == null || !Files.isDirectory(dest)) return false;
        for (String lang : List.of("java", "kotlin", "groovy")) {
            Path langDir = dest.resolve(lang);
            if (!Files.isDirectory(langDir)) continue;
            try (var children = Files.list(langDir)) {
                if (children.anyMatch(
                        p -> Files.isDirectory(p) && p.getFileName().toString().endsWith(".g8"))) {
                    return true;
                }
            } catch (IOException ignored) {
                // try next lang
            }
        }
        Path nested = dest.resolve("templates");
        return Files.isDirectory(nested) && isLegacyLangKindLayout(nested);
    }

    private static boolean hasG8Child(Path frameworkDir) {
        try (var tmpls = Files.list(frameworkDir)) {
            return tmpls.anyMatch(
                    p -> Files.isDirectory(p) && p.getFileName().toString().endsWith(".g8"));
        } catch (IOException e) {
            return false;
        }
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
        if (!url.endsWith(".git") && (url.startsWith("https://github.com/") || url.startsWith("http://github.com/"))) {
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
