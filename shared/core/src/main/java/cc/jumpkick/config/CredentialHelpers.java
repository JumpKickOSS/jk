// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.SearchPath;
import cc.jumpkick.host.time.Clock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Runs the helper command a {@code ${cmd:...}} reference names, and caches what it printed.
 *
 * <p><b>Execution.</b> The body is split into argv the way {@code [scripts]} commands are ({@link
 * ShellWords}); no shell runs unless the user writes {@code sh -c '...'} themselves. The child gets
 * the request's environment — the shell that ran {@code jk}, not the one that started the engine —
 * so {@code gcloud}'s configuration and {@code PATH} match the user's terminal, and the executable
 * is looked up on that {@code PATH}. stdin is closed. Its trimmed stdout is the value. A non-zero
 * exit, empty output, or no answer within {@link #TIMEOUT} is an error naming the position and the
 * command, with the tail of its stderr. Both pipes are drained on platform threads: a virtual thread
 * blocked reading a pipe pins its carrier.
 *
 * <p><b>Caching.</b> A value is reused for its time to live — {@link #DEFAULT_TTL}, or the {@code
 * #ttl=5m} suffix of the reference ({@code s}, {@code m} or {@code h}) — keyed by the argv and a
 * fingerprint of the environment it ran under. Concurrent resolves of one key share one run. A
 * repository that refuses a helper's value asks for it to be {@linkplain #forget forgotten}, so the
 * next resolve runs the helper again.
 *
 * <p><b>One re-run per request.</b> A value a re-run printed that is refused again is not dropped a
 * second time within the same request, or, off a request, within {@link #RERUN_WINDOW} of that run:
 * {@link #forget} says so, and the repository fails at once. Every module of a lock builds its own
 * repositories, so without this an always-refused helper (an expired SSO login) would run twice per
 * module. The next request runs it again, so a user who logs in again is not kept waiting.
 */
public final class CredentialHelpers {

    /** How long a helper's value is reused when the reference names no {@code #ttl=}. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    /** How long a helper may take before it is killed and the resolve fails. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** {@code #ttl=15m} at the end of a body: the suffix, not part of the command. */
    private static final Pattern TTL_SUFFIX = Pattern.compile("\\s*#ttl=(\\d+)([smh])\\s*$");

    /** stdout or stderr kept past this many bytes is dropped: a credential is short, and stderr is a hint. */
    private static final int MAX_OUTPUT = 64 * 1024;

    /** The stderr tail an error message quotes. */
    private static final int STDERR_TAIL = 600;

    /** A machine has a handful of helpers; an over-full table is dropped whole. */
    private static final int MAX_SLOTS = 256;

    /** Off a request, how long a re-run's refused value stays refused without running the helper again. */
    static final Duration RERUN_WINDOW = Duration.ofMinutes(1);

    /** The request-scope key of the helpers re-run during the request. */
    private static final Object RERUNS = new Object();

    /** One helper's argv and its time to live, as a reference body spells them. */
    public record Command(List<String> argv, Duration ttl) {
        public Command {
            argv = List.copyOf(argv);
            Objects.requireNonNull(ttl, "ttl");
        }
    }

    /** One cache key's state; its lock is the single flight. */
    private static final class Slot {
        final ReentrantLock lock = new ReentrantLock();
        volatile @Nullable String value;
        volatile long expiresAtNanos;
        /** A refusal dropped the value: the next run is a re-run. */
        volatile boolean rerunAsked;
        /** {@link #value} is what a re-run printed, at {@link #ranAtNanos}. */
        volatile boolean rerun;

        volatile long ranAtNanos;
    }

    private record Key(List<String> argv, String envFingerprint) {}

    private static final ConcurrentHashMap<Key, Slot> SLOTS = new ConcurrentHashMap<>();

    /** The clock the time to live is read from; a field so a test can move it. */
    private static volatile LongSupplier clock = Clock.SYSTEM::nanos;

    /** What runs a command; a field so a test can count runs without forking. */
    private static volatile Runner runner = CredentialHelpers::run;

    /** Runs one command under one environment and returns its trimmed stdout, or throws naming {@code where}. */
    @FunctionalInterface
    interface Runner {
        String run(List<String> argv, Map<String, String> env, Duration timeout, String where);
    }

    private CredentialHelpers() {}

    /** {@code body} as argv and a time to live; an empty command or a malformed suffix is an error naming {@code where}. */
    public static Command parse(String body, String where) {
        Duration ttl = DEFAULT_TTL;
        String command = body;
        Matcher m = TTL_SUFFIX.matcher(body);
        if (m.find()) {
            long n = Long.parseLong(m.group(1));
            ttl = switch (m.group(2)) {
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                default -> Duration.ofHours(n);
            };
            command = body.substring(0, m.start());
        } else if (body.contains("#ttl=")) {
            throw new JkBuildParseException(where + " has a malformed #ttl= in ${cmd:" + body
                    + "}: write a whole number of s, m or h, e.g. #ttl=5m");
        }
        List<String> argv = ShellWords.split(command.strip());
        if (argv.isEmpty()) throw new JkBuildParseException(where + " has a ${cmd:} reference with no command");
        return new Command(argv, ttl);
    }

    /**
     * The helper's value for the reference body {@code body}, from the cache while it is fresh, else
     * by running it under the request's environment.
     */
    public static String value(String body, String where) {
        return value(parse(body, where), BuildEnv.callerEnv(), where);
    }

    /** {@link #value(String, String)} for a parsed command and an explicit environment. */
    static String value(Command command, Map<String, String> env, String where) {
        Key key = new Key(command.argv(), fingerprint(env));
        if (SLOTS.size() >= MAX_SLOTS) SLOTS.clear();
        Slot slot = SLOTS.computeIfAbsent(key, k -> new Slot());
        slot.lock.lock();
        try {
            String value = slot.value;
            if (value == null || clock.getAsLong() - slot.expiresAtNanos >= 0) {
                value = runner.run(command.argv(), env, TIMEOUT, where);
                long now = clock.getAsLong();
                boolean rerun = slot.rerunAsked;
                slot.value = value;
                slot.expiresAtNanos = now + command.ttl().toNanos();
                slot.rerunAsked = false;
                slot.rerun = rerun;
                slot.ranAtNanos = now;
                if (rerun && RequestScope.hasRequest()) rerunsThisRequest().add(key);
            }
            // Filed on every answer, cached or not: each workspace that uses it masks it.
            ResolvedSecrets.record(value);
            return value;
        } finally {
            slot.lock.unlock();
        }
    }

    /** True when {@code value} is what a cached helper printed — a credential that a re-run could replace. */
    public static boolean produced(@Nullable String value) {
        if (value == null || value.isEmpty()) return false;
        for (Slot slot : SLOTS.values()) {
            if (value.equals(slot.value)) return true;
        }
        return false;
    }

    /**
     * Drop every cached value equal to {@code value}, so the next resolve runs its helper again, and
     * say whether that run may happen. A value already replaced by a newer run is left alone, so
     * concurrent refusals of one stale credential run the helper once. False when every helper that
     * printed {@code value} printed it on a re-run that this request (off a request: the last {@link
     * #RERUN_WINDOW}) already made: the fresh value was refused too, and running again would only
     * repeat the refusal.
     */
    public static boolean forget(@Nullable String value) {
        if (value == null) return true;
        boolean spent = false;
        boolean dropped = false;
        for (Map.Entry<Key, Slot> entry : SLOTS.entrySet()) {
            Slot slot = entry.getValue();
            slot.lock.lock();
            try {
                if (!value.equals(slot.value)) continue;
                if (slot.rerun && rerunThisRequest(entry.getKey(), slot)) {
                    spent = true;
                } else {
                    slot.value = null;
                    slot.rerunAsked = true;
                    dropped = true;
                }
            } finally {
                slot.lock.unlock();
            }
        }
        return dropped || !spent;
    }

    /** True when {@code slot}'s re-run happened in the current request, or off a request within the window. */
    private static boolean rerunThisRequest(Key key, Slot slot) {
        if (RequestScope.hasRequest()) return rerunsThisRequest().contains(key);
        return clock.getAsLong() - slot.ranAtNanos < RERUN_WINDOW.toNanos();
    }

    /** The helpers re-run during the current request; the set ends with the request. */
    private static Set<Key> rerunsThisRequest() {
        return RequestScope.current().get(RERUNS, k -> ConcurrentHashMap.newKeySet());
    }

    /** A digest of {@code env} in name order: two shells with the same variables share a value. */
    private static String fingerprint(Map<String, String> env) {
        StringBuilder out = new StringBuilder();
        new TreeMap<>(env).forEach((k, v) -> out.append(k).append('=').append(v).append('\0'));
        return Hashing.sha256Hex(out.toString());
    }

    /** The process runner: argv under exactly {@code env}, stdin closed, both pipes on platform threads. */
    static String run(List<String> argv, Map<String, String> env, Duration timeout, String where) {
        List<String> command = new ArrayList<>(argv);
        command.set(0, executable(argv.getFirst(), env, where));
        ProcessBuilder pb =
                new ProcessBuilder(command).directory(PathUtil.userHome().toFile());
        // The child sees the request's shell and nothing of the engine's own: cleared, then set.
        Map<String, String> childEnv = pb.environment();
        childEnv.clear();
        childEnv.putAll(env);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new JkBuildParseException(where + " could not run `" + argv.getFirst() + "`: " + e.getMessage());
        }
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread out = drain(process.getInputStream(), stdout, "jk-cmd-stdout");
        Thread err = drain(process.getErrorStream(), stderr, "jk-cmd-stderr");
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // a child that exited before reading stdin
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new JkBuildParseException(where + " ran `" + String.join(" ", argv)
                        + "`, which did not finish within "
                        + (timeout.toMillis() % 1000 == 0 ? timeout.toSeconds() + "s" : timeout.toMillis() + "ms"));
            }
            out.join(timeout.toMillis());
            err.join(timeout.toMillis());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new JkBuildParseException(where + " was interrupted waiting for `" + argv.getFirst() + "`");
        }
        String value = stdout.toString(StandardCharsets.UTF_8).strip();
        int exit = process.exitValue();
        if (exit != 0 || value.isEmpty()) {
            String why = exit != 0 ? "exited " + exit : "printed nothing";
            String tail = stderrTail(stderr.toString(StandardCharsets.UTF_8), value);
            throw new JkBuildParseException(where + " ran `" + String.join(" ", argv) + "`, which " + why
                    + (tail.isEmpty() ? "" : ": " + tail));
        }
        return value;
    }

    /**
     * The end of {@code stderr}, with {@code stdout} masked in case the helper echoed its secret
     * there before failing.
     */
    private static String stderrTail(String stderr, String stdout) {
        String s = stderr.strip();
        if (!stdout.isEmpty()) s = s.replace(stdout, SecretRedactor.MASK);
        return s.length() <= STDERR_TAIL ? s : "…" + s.substring(s.length() - STDERR_TAIL);
    }

    private static Thread drain(InputStream in, ByteArrayOutputStream sink, String name) {
        return Thread.ofPlatform().daemon().name(name).start(() -> {
            byte[] buf = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    synchronized (sink) {
                        if (sink.size() < MAX_OUTPUT) sink.write(buf, 0, Math.min(n, MAX_OUTPUT - sink.size()));
                    }
                }
            } catch (IOException ignored) {
                // the process was killed; what was read is what there is
            }
        });
    }

    /**
     * {@code name} as a path: as written when it names a directory, else found on the {@code PATH}
     * of {@code env} — the request's, which the process launcher would not consult on its own.
     */
    static String executable(String name, Map<String, String> env, String where) {
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return name;
        String path = lookup(env, "PATH");
        List<String> suffixes = Os.isWindows() ? windowsSuffixes(lookup(env, "PATHEXT")) : List.of("");
        for (String entry : SearchPath.entries(path)) {
            Path dir = SearchPath.path(entry);
            if (dir == null) continue;
            for (String suffix : suffixes) {
                Path candidate = dir.resolve(name + suffix);
                if (Files.isRegularFile(candidate) && PathUtil.isRunnable(candidate)) return candidate.toString();
            }
        }
        throw new JkBuildParseException(where + " runs `" + name + "`, which is not on your PATH");
    }

    /** {@code env}'s value for {@code name}, case-insensitively on Windows where the shell keys {@code Path}. */
    private static @Nullable String lookup(Map<String, String> env, String name) {
        String exact = env.get(name);
        if (exact != null || !Os.isWindows()) return exact;
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static List<String> windowsSuffixes(@Nullable String pathext) {
        List<String> out = new ArrayList<>();
        out.add("");
        String exts = pathext == null || pathext.isBlank() ? ".COM;.EXE;.BAT;.CMD" : pathext;
        for (String ext : exts.split(";")) {
            if (!ext.isBlank()) out.add(ext.strip().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Swap the runner; tests. Returns the previous one. */
    static Runner runner(Runner replacement) {
        Runner previous = runner;
        runner = Objects.requireNonNull(replacement, "replacement");
        return previous;
    }

    /** Swap the clock; tests. */
    static void clock(LongSupplier replacement) {
        clock = Objects.requireNonNull(replacement, "replacement");
    }

    /** Forget every cached value and restore the clock. Nothing on the build path needs this; tests do. */
    static void clear() {
        SLOTS.clear();
        clock = Clock.SYSTEM::nanos;
    }
}
