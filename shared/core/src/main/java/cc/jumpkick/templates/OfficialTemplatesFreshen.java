// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.templates;

import cc.jumpkick.config.JkTemplatesConfig;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.config.StampedMemo.FileStamp;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.SearchPath;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.util.AttemptWindow;
import cc.jumpkick.util.GitProxy;
import cc.jumpkick.util.GitVersion;
import cc.jumpkick.util.StoreWriteGate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    private static final AttemptWindow ATTEMPTS = new AttemptWindow(Duration.ofMinutes(10));

    static final long ATTEMPT_TTL_NANOS = ATTEMPTS.window().toNanos();

    private OfficialTemplatesFreshen() {}

    /**
     * Best-effort freshen; never throws. Network / missing git → silent skip. Attempts are rate
     * limited to one per cache key per {@link #ATTEMPT_TTL_NANOS} (success <em>or</em> failure) —
     * for the 12 h maintenance cycle and engine-start warmup, both long-lived-process callers where
     * the guard actually protects against a hung run retrying itself. {@link #refreshNow} is the
     * on-demand counterpart for a real user action ({@code jk new}/{@code init}): every call
     * attempts a real fetch, no TTL.
     */
    public static void refreshQuiet(Consumer<String> log, GitProxy proxy) {
        if (log == null) log = s -> {};
        try {
            JkTemplatesConfig cfg = JkTemplatesConfig.resolve();
            if (!markAttempt(parse(officialRef(cfg)).cacheKey(), Clock.SYSTEM.nanos())) return;
            refresh(cfg, log, true, proxy);
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
    public static void refreshNow(Consumer<String> log, GitProxy proxy) {
        if (log == null) log = s -> {};
        try {
            refresh(JkTemplatesConfig.resolve(), log, false, proxy);
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
        return ATTEMPTS.tryAcquire(cacheKey, nowNanos);
    }

    static void resetAttemptGuardForTests() {
        ATTEMPTS.clear();
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
     *
     * @param hygiene a background freshen (maintenance cycle, engine-start warmup), which stands
     *     down once the store was wiped in this process: a clone landing after the wipe recreates
     *     the store the nuke reported gone. Judged under the gate, so a wipe that took the gate
     *     while this call was on its way is seen and not cloned over. A user action ({@code jk
     *     new}) passes {@code false} and repopulates on demand.
     * @param proxy the {@code http.proxy} each source's git runs with
     */
    static void refresh(JkTemplatesConfig config, Consumer<String> log, boolean hygiene, GitProxy proxy)
            throws IOException {
        // Clones land inside the store — a wipe must not overlap the git subprocess.
        try (var held = StoreWriteGate.write()) {
            if (hygiene && StoreWriteGate.wipedSinceStart()) return;
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
                    refreshRef(ref, cacheRoot, log, proxy);
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

    static void refreshRef(String ref, Path cacheRoot, Consumer<String> log, GitProxy proxy) throws IOException {
        Parsed p = parse(ref);
        Path dest = destination(cacheRoot, p.cacheKey());
        @Nullable String httpProxy = proxy.httpProxy(p.url()).orElse(null);
        // Incomplete clones (e.g. only a .git dir left from a failed private-repo attempt) must be
        // wiped and re-cloned — fetch/reset cannot recover them. So must a catalog-shaped directory
        // that is not a repository of its own: a fetch and reset there would act on whatever
        // repository encloses it.
        if (!Files.isDirectory(dest)
                || isEmptyDir(dest)
                || !isRepositoryRoot(dest)
                || !looksLikeTemplateMonorepo(dest)) {
            PathUtil.deleteRecursivelyOrThrow(dest);
            Files.createDirectories(dest.getParent());
            runGit(p.cloneArgs(dest), httpProxy, Duration.ofSeconds(120));
            log.accept("jk engine: cloned templates source (" + dest.getFileName() + ")");
            return;
        }
        // Existing shallow clone: cheap fetch + hard reset (no merge noise).
        try {
            fetchAndReset(dest, p.rev(), httpProxy);
        } catch (IOException fetchFailed) {
            // Corrupt / auth-skewed cache: delete and clone clean.
            PathUtil.deleteRecursivelyOrThrow(dest);
            Files.createDirectories(dest.getParent());
            runGit(p.cloneArgs(dest), httpProxy, Duration.ofSeconds(120));
            log.accept("jk engine: re-cloned templates source (" + dest.getFileName() + ")");
        }
    }

    /**
     * True when {@code dest} looks like a usable templates monorepo: a language directory holding
     * a {@code .g8} tree, directly or under a framework. A bare {@code .git} from a failed clone is
     * not.
     */
    static boolean looksLikeTemplateMonorepo(Path dest) {
        if (dest == null || !Files.isDirectory(dest)) return false;
        for (String lang : List.of("java", "kotlin", "groovy", "scala")) {
            Path langDir = dest.resolve(lang);
            if (!Files.isDirectory(langDir)) continue;
            if (hasG8Child(langDir)) return true;
            try (var frameworks = Files.list(langDir)) {
                if (frameworks.anyMatch(fw ->
                        Files.isDirectory(fw) && !fw.getFileName().toString().startsWith(".") && hasG8Child(fw))) {
                    return true;
                }
            } catch (IOException ignored) {
                // try next lang
            }
        }
        Path nested = dest.resolve("templates");
        return Files.isDirectory(nested) && looksLikeTemplateMonorepo(nested);
    }

    private static boolean hasG8Child(Path dir) {
        try (var tmpls = Files.list(dir)) {
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
    static void fetchAndReset(Path dest, @Nullable String rev, @Nullable String httpProxy) throws IOException {
        List<String> fetch = new ArrayList<>(pinnedGit(dest, "fetch", "--depth", "1", "origin"));
        if (rev != null && !rev.isBlank()) {
            fetch.add(rev);
        }
        runGit(fetch, httpProxy, Duration.ofSeconds(60));
        runGit(pinnedGit(dest, "reset", "--hard", "FETCH_HEAD"), null, Duration.ofSeconds(30));
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
        runGit(args, Duration.ofSeconds(timeoutSec));
    }

    /** As {@link #runGit(List, int)} with a finer timeout; tests prove the kill in milliseconds. */
    static void runGit(List<String> args, Duration timeout) throws IOException {
        runGit(args, null, timeout);
    }

    /**
     * Run {@code args}, with {@code httpProxy} as git's {@code http.proxy} when it is not null (an
     * empty value turns git's proxy off). The value goes through git's environment by {@link
     * GitProxy#route}, never the argv, because it can carry the proxy's credential; a git too old
     * to read it there is refused a proxy by name.
     */
    static void runGit(List<String> args, @Nullable String httpProxy, Duration timeout) throws IOException {
        // Both streams are handled at the OS level — stdout discarded, stderr into a file — because
        // reading a pipe inline would block past the timeout on a stalled fetch, and the single
        // maintenance thread must never hang. The file is what lets a failure say why.
        Path stderr = Files.createTempFile("jk-git-", ".err");
        ProcessBuilder pb = new ProcessBuilder(args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(stderr.toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        if (httpProxy != null) {
            GitProxy.route(pb.environment(), httpProxy, gitVersion(args.get(0)), remoteOf(args));
        }
        try {
            Process proc;
            try {
                proc = pb.start();
            } catch (IOException e) {
                throw new IOException(failure("git not on PATH", args, stderr), e);
            }
            try {
                if (!proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    kill(proc);
                    long millis = timeout.toMillis();
                    String budget = millis % 1000 == 0 ? millis / 1000 + "s" : millis + "ms";
                    throw new IOException(failure("git timed out after " + budget, args, stderr));
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
     * The version each git command answered, stamped by the file the command runs: a git upgraded
     * or replaced in place is asked again. Only an answer is remembered; a git that is missing,
     * hangs or prints no version is asked again next time.
     */
    private static final StampedMemo<String, FileStamp, Optional<GitVersion>> VERSIONS =
            StampedMemo.<String, FileStamp, Optional<GitVersion>>bounded(16).keeping(Optional::isPresent);

    /** The first git that reads {@code GIT_CONFIG_*}: what an unreadable answer is taken for. */
    private static final GitVersion READS_CONFIG_ENV = new GitVersion(2, 31);

    /**
     * {@code exe}'s version, from {@code exe --version} bounded to a few seconds. A git that does
     * not answer is taken to read {@code GIT_CONFIG_*}: the command itself then fails by its own
     * name, which says more than a version guess would.
     */
    static GitVersion gitVersion(String exe) {
        FileStamp stamp = FileStamp.of(locate(exe));
        if (stamp == null) return probeVersion(exe).orElse(READS_CONFIG_ENV);
        return VERSIONS.get(exe, stamp, () -> probeVersion(exe)).orElse(READS_CONFIG_ENV);
    }

    /** The file {@code exe} runs: itself when it names a path, else its first match on this process's PATH. */
    private static @Nullable Path locate(String exe) {
        Path named = SearchPath.path(exe);
        if (named != null && (named.isAbsolute() || named.getNameCount() > 1)) return named;
        List<String> names = Os.isWindows() ? List.of(exe + ".exe", exe) : List.of(exe);
        for (String dir : SearchPath.entries(ProcessEnv.get("PATH"))) {
            Path d = SearchPath.path(dir);
            if (d == null) continue;
            for (String name : names) {
                Path candidate = d.resolve(name);
                if (Files.isRegularFile(candidate) && PathUtil.isRunnable(candidate)) return candidate;
            }
        }
        return null;
    }

    private static Optional<GitVersion> probeVersion(String exe) {
        try {
            Path out = Files.createTempFile("jk-git-version-", ".txt");
            try {
                Process proc = new ProcessBuilder(exe, "--version")
                        .redirectErrorStream(true)
                        .redirectOutput(out.toFile())
                        .start();
                if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                    kill(proc);
                    return Optional.empty();
                }
                return GitVersion.parse(Files.readString(out));
            } finally {
                Files.deleteIfExists(out);
            }
        } catch (IOException notStarted) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** The remote URL among {@code args}, for a refusal to name; null when the command names none. */
    private static @Nullable String remoteOf(List<String> args) {
        for (String arg : args) {
            if (arg.contains("://")) return arg;
        }
        return null;
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
