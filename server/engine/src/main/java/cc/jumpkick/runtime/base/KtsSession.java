// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.util.JkDirs;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * One child JVM that runs every {@code .kts} of a build, reusing compiled scripts across runs.
 *
 * <p>It replaces a fork of {@code kotlinc -script} per script. That fork paid ~2.2 s of JVM and
 * compiler startup plus a full recompile of the script every time, even when the script's bytes had
 * not changed — measured at 8.5 s for this repo's own 2,413-line gate, of which 5.1 s was startup
 * and compilation. Here the first run of a script compiles it and caches the jar under {@code
 * <store>/cache/kts/}; later runs, in this build or a later one, load the jar. A second script in an
 * already-running session costs milliseconds.
 *
 * <h2>Ownership</h2>
 *
 * The host belongs to the engine, not to the build that happened to start it. It is forked outside
 * the request's worker scope — a request's end kills the workers it registered, and a host that
 * outlives builds must not be one of them — and two owners end it: a reaper that shuts it down once
 * it has sat {@link #IDLE_TIMEOUT} without a script, and the JVM shutdown hook that drains it when
 * the engine stops. Nothing else does, so a build finishing while another build's script is running
 * is not an event the host notices.
 *
 * <h2>What this trades away</h2>
 *
 * A forked-per-script host made each script its own process, so a script calling {@code System.exit}
 * or exhausting the heap took down only itself. Sharing one JVM means such a script takes the
 * session with it. The engine is still insulated — the session is a child process — and {@link
 * #run} detects the death, reports it against the script that caused it, and drops the session so
 * the next script starts a fresh one. What cannot be recovered is the run that died.
 *
 * <h2>Concurrency</h2>
 *
 * Requests are serialised on {@link #LOCK}. jk builds modules in parallel, so two modules' scripts
 * can arrive at once; they queue rather than run together. That is a deliberate simplification —
 * with per-script cost down from seconds to milliseconds, queueing costs far less than the
 * per-script JVM it replaces — and it is why the protocol needs no request ids.
 *
 * <h2>Cancellation</h2>
 *
 * A build cancelled while its script runs cancels the script: the request watches the build's
 * cancel probe while it waits for the reply and, once it flips, sends {@code CANCEL}. The host
 * interrupts the script and answers {@code CANCELLED} when it has stopped. A script that ignores
 * the interrupt would otherwise hold the lock for every later build, so a host that has not
 * answered within {@link #CANCEL_GRACE} is killed and the next script starts a fresh one. Either
 * way the run reports as {@link Cancelled}, not as a script failure.
 */
final class KtsSession {

    /** How long the host may sit without a script before the reaper shuts it down. */
    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);

    /** How long a cancelled script may take to stop before its host is killed instead. */
    static final Duration CANCEL_GRACE = Duration.ofSeconds(2);

    /** How often a waiting request looks at its build's cancel probe. */
    private static final Duration CANCEL_POLL = Duration.ofMillis(50);

    /** Queued in place of a reply when the host's stdout ends. */
    private static final String EOF = "\u0000eof";

    private static final Object LOCK = new Object();
    private static @Nullable KtsSession current;
    private static boolean hookRegistered;
    private static long idleTimeoutNanos = IDLE_TIMEOUT.toNanos();
    private static volatile Clock clock = Clock.SYSTEM;

    private final Process process;
    private final BufferedWriter toChild;

    /**
     * Replies, one line each, moved off the pipe by a reader thread so a request can wait for the
     * next one with a timeout and look at its cancel probe in between. {@link #EOF} marks the end.
     */
    private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();

    /** When the last script finished, on the monotonic clock; the reaper measures idleness from it. */
    private long lastUsedNanos = clock.nanos();

    private KtsSession(Process process) {
        this.process = process;
        this.toChild = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader fromChild =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        // The .kts host outlives any one build, so its reader must not carry a request's session.
        Thread.ofVirtual().name("jk-kts-host-reader").start(() -> {
            try {
                String line;
                while ((line = fromChild.readLine()) != null) replies.put(line);
            } catch (IOException | InterruptedException e) {
                // The pipe is gone or the engine is stopping; EOF below tells the waiter either way.
            } finally {
                replies.add(EOF);
            }
        });
    }

    /**
     * Evaluate one script. Returns its captured stdout/stderr on success; throws with that output
     * attached on failure, and {@link Cancelled} when {@code cancelled} flips while it runs.
     *
     * @param cancelled the owning build's cancel probe, watched while the script runs
     */
    static String run(Path script, Path projectDir, Path outDir, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        synchronized (LOCK) {
            if (current != null && !current.process.isAlive()) current = null;
            if (current == null) current = start();
            KtsSession session = current;
            try {
                return session.request(script, projectDir, outDir, cancelled);
            } catch (SessionDied e) {
                // The child is gone; the next script gets a new one rather than inheriting a corpse.
                current = null;
                throw new IllegalStateException(
                        "[build] logic: the .kts host died running " + script.getFileName()
                                + " (a script calling System.exit, or an out-of-memory, takes the shared host with it)"
                                + (e.tail().isEmpty() ? "" : ":\n" + e.tail()),
                        e);
            } catch (Cancelled e) {
                // A host that would not stop its script was killed; the next script starts fresh.
                if (!session.process.isAlive()) current = null;
                throw e;
            } finally {
                session.lastUsedNanos = clock.nanos();
            }
        }
    }

    /** Shut the session down, if one is running: the engine-stop drain, and the test seam. */
    static void shutdown() {
        synchronized (LOCK) {
            if (current == null) return;
            KtsSession s = current;
            current = null;
            s.exit();
        }
    }

    /** Test seam: whether a host is running. */
    static boolean hostAlive() {
        synchronized (LOCK) {
            return current != null && current.process.isAlive();
        }
    }

    /** Test seam: the running host's pid, or -1; two runs served by one host see one pid. */
    static long hostPid() {
        synchronized (LOCK) {
            return current != null && current.process.isAlive() ? current.process.pid() : -1;
        }
    }

    /** Test seam: shorten the idle timeout; applies to hosts started afterwards. */
    static void idleTimeoutForTests(Duration timeout) {
        synchronized (LOCK) {
            idleTimeoutNanos = timeout.toNanos();
        }
    }

    /** Test seam: the clock idleness is measured on. */
    static void clockForTests(Clock c) {
        clock = c;
    }

    /** Ask the child to exit and wait briefly; a child that does not go is killed. */
    private void exit() {
        try {
            toChild.write("EXIT\n");
            toChild.flush();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (IOException | InterruptedException e) {
            process.destroyForcibly();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private String request(Path script, Path projectDir, Path outDir, BooleanSupplier cancelled)
            throws IOException, InterruptedException, SessionDied {
        toChild.write("RUN\t" + script + "\t" + projectDir + "\t" + outDir + "\n");
        toChild.flush();
        String reply;
        while ((reply = replies.poll(CANCEL_POLL.toMillis(), TimeUnit.MILLISECONDS)) == null) {
            if (cancelled.getAsBoolean()) {
                cancelInFlight();
                throw new Cancelled(script);
            }
        }
        if (EOF.equals(reply)) throw new SessionDied(drain());
        if (reply.startsWith("OK ")) return decode(reply.substring(3));
        if (reply.startsWith("FAIL ")) {
            // Verbatim compiler/runtime output — its first line is the script's own file:line.
            // The one jk-authored prefix is registerScripts', which names the file once.
            throw new IllegalStateException(decode(reply.substring(5)).strip());
        }
        throw new SessionDied("unrecognised reply from the .kts host: " + reply);
    }

    /**
     * Ask the host to stop the script in flight and wait for its answer. Any reply within the grace
     * means the host is free again — the script stopped, or finished just as the cancel arrived.
     * No reply means the script ignores the interrupt; the host is killed so the next build does
     * not wait on it.
     */
    private void cancelInFlight() throws InterruptedException {
        try {
            toChild.write("CANCEL\n");
            toChild.flush();
        } catch (IOException e) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return;
        }
        String reply = replies.poll(CANCEL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        if (reply == null || EOF.equals(reply)) {
            process.destroyForcibly();
            // Waited for, so the caller's liveness check sees the kill and drops the session.
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /** Whatever the child managed to say before dying — usually the JVM's own error. */
    private String drain() {
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = replies.poll()) != null) {
            if (EOF.equals(l)) break;
            sb.append(l).append('\n');
        }
        return sb.toString().strip();
    }

    private static String decode(String base64) {
        return new String(Base64.getDecoder().decode(base64.strip()), StandardCharsets.UTF_8);
    }

    private static KtsSession start() throws IOException, InterruptedException {
        Path kotlinHome = CompileToolchain.resolveKotlinHome(JkDirs.cache(), null, msg -> {
            // Silent: engine labels surface the task, not toolchain chatter.
        });
        String kotlinVersion = kotlinHome.getFileName().toString();
        Path hostJar = KtsHostJar.ensure(kotlinHome, kotlinVersion);

        List<Path> cp = new ArrayList<>();
        cp.add(hostJar);
        cp.addAll(KtsHostJar.kotlinClasspath(kotlinHome));

        List<String> cmd = new ArrayList<>();
        cmd.add(JdkFingerprint.java(JavaHomes.runningJavaHome()).toString());
        cmd.add("-cp");
        cmd.add(Classpaths.join(cp));
        cmd.add("cc.jumpkick.kts.JkKtsHostKt");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // Not redirectErrorStream: the child's stdout carries the protocol, and a JVM warning on
        // stderr merged into it would be read as a reply.
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Path cacheDir = compiledScriptCache();
        Files.createDirectories(cacheDir);
        pb.environment().put("JK_KTS_CACHE", cacheDir.toString());

        // Started directly, not through JobWorkers: that registry belongs to the request on this
        // thread and kills its members when the request ends, and this host is the engine's —
        // reused by later builds, and possibly mid-script for another build right now. Its own
        // owners are the idle reaper below and the shutdown hook.
        Process p = pb.start();
        registerShutdownHook();
        KtsSession session = new KtsSession(p);
        String ready = session.replies.take();
        if (!"READY".equals(ready)) {
            p.destroyForcibly();
            throw new IllegalStateException(
                    "[build] logic: the .kts host did not start" + (EOF.equals(ready) ? "" : " (said: " + ready + ")"));
        }
        // Idle reaper of the shared .kts host; reads no session.
        Thread.ofVirtual().name("jk-kts-host-reaper").start(() -> reap(session));
        return session;
    }

    /**
     * Shut {@code session} down once it has been idle for the timeout. Sleeps until the earliest
     * moment that could be true and re-checks: a script that ran in between moves the deadline.
     * Taking {@link #LOCK} means a script in flight is waited for, never cut off.
     */
    private static void reap(KtsSession session) {
        while (true) {
            long wait;
            synchronized (LOCK) {
                if (current != session) return; // replaced or shut down by someone else
                if (!session.process.isAlive()) {
                    current = null;
                    return;
                }
                long idle = clock.nanos() - session.lastUsedNanos;
                if (idle >= idleTimeoutNanos) {
                    current = null;
                    session.exit();
                    return;
                }
                wait = idleTimeoutNanos - idle;
            }
            try {
                Thread.sleep(Duration.ofNanos(wait));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * The child outlives any single script, so nothing else would reap it if the engine exits
     * before the idle timeout — a stopped engine would leave a JVM holding the compiler open.
     */
    private static void registerShutdownHook() {
        if (hookRegistered) return;
        hookRegistered = true;
        // Shutdown hook; reads no session.
        Runtime.getRuntime().addShutdownHook(new Thread(KtsSession::shutdown, "jk-kts-host-shutdown"));
    }

    /**
     * Where compiled scripts live. Under the cache tier, not the store: a compiled script is
     * derived from a source file that is still on disk, so losing it costs one recompile.
     */
    static Path compiledScriptCache() {
        return JkDirs.cache().resolve("kts");
    }

    /**
     * The script was cancelled with the build that ran it. Carries the jk prefix so the build-logic
     * step reports it as it is, not as a script failure.
     */
    static final class Cancelled extends RuntimeException {
        Cancelled(Path script) {
            super("[build] logic: " + script.getFileName() + " cancelled with its build");
        }
    }

    /** The child exited rather than replying. Carries whatever it said on the way out. */
    private static final class SessionDied extends Exception {
        private final String tail;

        SessionDied(String tail) {
            super(tail);
            this.tail = tail == null ? "" : tail;
        }

        String tail() {
            return tail;
        }
    }
}
