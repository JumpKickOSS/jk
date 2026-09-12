// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.templates;

import cc.jumpkick.config.JkTemplatesConfig;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.StoreWriteGate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

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
            log.accept(skipped(t));
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
            log.accept(skipped(t));
        }
    }

    /** Widest a quiet-path breadcrumb gets; the failing argv plus git's last words fit, a stack dump does not. */
    static final int SKIPPED_WIDTH = 320;

    /** The one line a quiet freshen leaves behind: the exception's class and its message, cut to {@link #SKIPPED_WIDTH}. */
    static String skipped(Throwable t) {
        String m = t.getMessage();
        String why = m == null || m.isBlank() ? "" : ": " + clip(m.strip().replaceAll("\\s+", " "), SKIPPED_WIDTH);
        return "jk engine: templates freshen skipped (" + t.getClass().getSimpleName() + why + ")";
    }

    private static String clip(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width - 1) + "…";
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
                } catch (IllegalArgumentException unusable) {
                    // A source that names no usable cache directory fails like a source that
                    // cannot be cloned: this ref only, the others still get their attempt.
                    if (first == null) first = new IOException(unusable.getMessage(), unusable);
                }
            }
            if (first != null) throw first;
        }
    }

    static void refreshRef(String ref, Path cacheRoot, Consumer<String> log) throws IOException {
        Parsed p = parse(ref);
        Path dest = destination(cacheRoot, p.cacheKey());
        // Incomplete clones (e.g. only a .git dir left from a failed private-repo attempt) must be
        // wiped and re-cloned — fetch/reset cannot recover them. So must a catalog-shaped directory
        // that is not a repository of its own: a fetch and reset there would act on whatever
        // repository encloses it.
        if (!Files.isDirectory(dest)
                || isEmptyDir(dest)
                || !isRepositoryRoot(dest)
                || isLegacyLangKindLayout(dest)
                || !looksLikeTemplateMonorepo(dest)) {
            PathUtil.deleteRecursivelyOrThrow(dest);
            Files.createDirectories(dest.getParent());
            runGit(p.cloneArgs(dest), 120);
            log.accept("jk engine: cloned templates source (" + dest.getFileName() + ")");
            return;
        }
        // Existing shallow clone: cheap fetch + hard reset (no merge noise).
        try {
            fetchAndReset(dest, p.rev());
        } catch (IOException fetchFailed) {
            // Corrupt / auth-skewed cache: delete and clone clean.
            PathUtil.deleteRecursivelyOrThrow(dest);
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

    /**
     * Bring an existing cache clone up to date in place: shallow fetch, hard reset, no merge noise.
     *
     * <p>Package-private because the state worth testing is the one no caller can arrange — {@code
     * dest} having stopped being a repository between {@link #isRepositoryRoot} and here, which is what
     * a sandbox teardown or a sibling test JVM does. A test calls this directly to pin that the
     * invocations refuse rather than retarget.
     */
    static void fetchAndReset(Path dest, @Nullable String rev) throws IOException {
        List<String> fetch = new ArrayList<>(pinnedGit(dest, "fetch", "--depth", "1", "origin"));
        if (rev != null && !rev.isBlank()) {
            fetch.add(rev);
        }
        runGit(fetch, 60);
        runGit(pinnedGit(dest, "reset", "--hard", "FETCH_HEAD"), 30);
    }

    /**
     * A git command pinned to the repository at {@code dest}, never {@code -C dest}.
     *
     * <p>{@code git -C <dir>} does not mean "operate on the repository at {@code <dir>}"; it means "cd
     * there first", and git then searches upwards. So the moment {@code dest} stops being a repository
     * root — it was deleted, it never was one — the command retargets itself at whatever repository
     * encloses it, which for a cache under a source tree is the developer's checkout. {@code fetch
     * --depth 1} makes that checkout shallow and {@code reset --hard} discards their work.
     *
     * <p>{@link #isRepositoryRoot} cannot prevent it. That is a check and this is the use, and nothing
     * holds between them: a clone that passes the check and is then removed by a sandbox teardown or a
     * sibling test JVM leaves the calls pointing at the enclosing repository. Pinning is the property
     * that does not depend on timing — {@code --git-dir} names the repository outright, so a missing one
     * fails the command instead of choosing another. It accepts a {@code .git} gitfile as well as a
     * directory, so a linked worktree is pinned the same way.
     */
    private static List<String> pinnedGit(Path dest, String... args) {
        List<String> out = new ArrayList<>();
        out.add("git");
        out.add("--git-dir=" + dest.resolve(".git"));
        out.add("--work-tree=" + dest);
        out.addAll(List.of(args));
        return out;
    }

    /** Whether {@code dir} is a repository of its own — a {@code .git} directory or worktree file of its own. */
    static boolean isRepositoryRoot(Path dir) {
        return Files.exists(dir.resolve(".git"));
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    static void runGit(List<String> args, int timeoutSec) throws IOException {
        // Both streams are handled at the OS level — stdout discarded, stderr into a file — because
        // reading a pipe inline would block past the timeout on a stalled fetch, and the single
        // maintenance thread must never hang. The file is what lets a failure say why.
        Path stderr = Files.createTempFile("jk-git-", ".err");
        ProcessBuilder pb = new ProcessBuilder(args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(stderr.toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        try {
            Process proc;
            try {
                proc = pb.start();
            } catch (IOException e) {
                throw new IOException(failure("git not on PATH", args, stderr), e);
            }
            try {
                if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                    kill(proc);
                    throw new IOException(failure("git timed out after " + timeoutSec + "s", args, stderr));
                }
                if (proc.exitValue() != 0) {
                    throw new IOException(failure("git exit " + proc.exitValue(), args, stderr));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                kill(proc);
                throw new IOException(failure("git interrupted", args, stderr), e);
            }
        } finally {
            Files.deleteIfExists(stderr);
        }
    }

    /**
     * Force-kill {@code proc} and everything it spawned. Descendants first: {@code git} runs its
     * transport as a child ({@code git-remote-https}, {@code ssh}), and killing the parent alone
     * reparents that child to live on with the connection that stalled.
     */
    private static void kill(Process proc) {
        proc.descendants().forEach(ProcessHandle::destroyForcibly);
        proc.destroyForcibly();
    }

    /** Bytes of stderr a failure carries; git's last words, not its whole progress log. */
    private static final int STDERR_TAIL = 400;

    /**
     * Every way {@link #runGit} can fail, said the same way: what went wrong, which invocation, and
     * then the tail of what git wrote to stderr when it wrote anything. Wording matches {@code
     * GitCliExtension}, the other place jk shells out to git.
     */
    private static String failure(String what, List<String> args, Path stderr) {
        String tail = stderrTail(stderr);
        return what + ": " + String.join(" ", args) + (tail.isEmpty() ? "" : " — " + tail);
    }

    private static String stderrTail(Path stderr) {
        try {
            byte[] all = Files.readAllBytes(stderr);
            int from = Math.max(0, all.length - STDERR_TAIL);
            return new String(all, from, all.length - from, StandardCharsets.UTF_8)
                    .strip()
                    .replaceAll("\\s+", " ");
        } catch (IOException | RuntimeException unreadable) {
            return "";
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

    private static String cacheKeyForUrl(String url, @Nullable String rev) {
        String base = url.toLowerCase(Locale.ROOT)
                .replaceAll("^https?://", "")
                .replaceAll("^git@", "")
                .replaceAll("\\.git$", "")
                .replaceAll("[^a-z0-9._-]+", "_");
        if (rev != null && !rev.isBlank()) {
            base = base + "_" + rev.replaceAll("[^a-zA-Z0-9._-]+", "_");
        }
        String key = bound(base, rev == null ? url : url + "#" + rev);
        if (!CACHE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("templates source `" + url + "` does not name a cache directory"
                    + " (a source must reduce to a host and path, not to `" + key + "`)");
        }
        return key;
    }

    /**
     * The shape of a usable cache key: a plain directory name that starts with a letter or digit.
     * The sanitiser above keeps dots, so a source of {@code https://..} would otherwise key as
     * {@code ..} and name the store itself.
     */
    private static final Pattern CACHE_KEY = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    /**
     * The directory {@code cacheKey} names under {@code cacheRoot}. Refuses anything that is not a
     * direct child of the store: the refresh deletes this directory before it clones, and the store
     * also holds repositories, tools and the registry.
     */
    static Path destination(Path cacheRoot, String cacheKey) throws IOException {
        Path store = cacheRoot.toAbsolutePath().normalize();
        Path dest = store.resolve(cacheKey).normalize();
        if (cacheKey.isEmpty()
                || !store.equals(dest.getParent())
                || !cacheKey.equals(dest.getFileName().toString())) {
            throw new IOException(
                    "templates cache key `" + cacheKey + "` does not name a directory directly under " + store);
        }
        return dest;
    }

    /**
     * Longest directory name a cache key may produce. A real source is far shorter — the official
     * catalog keys as {@code github.com_jumpkickoss_jk-templates}, 35 characters — so the bound never
     * fires for one and no existing cache directory changes name.
     */
    private static final int MAX_CACHE_KEY = 60;

    /**
     * Keep a cache key inside {@link #MAX_CACHE_KEY}. The key is a sanitised copy of the whole source
     * URL and it names a directory that a clone then writes a repository underneath, so a
     * {@code file://} source under a deep path produced a directory name longer than the path it came
     * from — enough to cross Windows' MAX_PATH and fail the clone.
     *
     * <p>The prefix survives rather than the tail because the readable part of a URL is its host and
     * owner; a digest of the full identity is what keeps two long sources apart.
     */
    private static String bound(String key, String identity) {
        if (key.length() <= MAX_CACHE_KEY) return key;
        String digest = Hashing.sha256Hex(identity).substring(0, 12);
        return key.substring(0, MAX_CACHE_KEY - digest.length() - 1) + "_" + digest;
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
