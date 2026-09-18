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
 * <h2>Liveness</h2>
 *
 * The host's stdout is read on a platform thread. A pipe read is a native read that blocks the
 * thread making it, and the host is silent for as long as it sits between scripts — up to the idle
 * timeout. A reader on a virtual thread would hold its carrier for all of that time and rely on the
 * scheduler lending a spare, which a JVM under load can be refused; a test fork runs with one
 * carrier, so one held carrier is every carrier, and every other virtual thread of the JVM — a
 * plan's step estimates, this class's own reaper — waits for the host to speak. The reaper stays
 * virtual: a sleeping virtual thread holds nothing.
 *
 * <p>No wait on the host is open-ended. A host that has not said {@code READY} within {@link
 * #START_TIMEOUT} is killed and the run fails naming the timeout; a request whose host exits fails
 * naming the script once the reader's EOF arrives, or after {@link #EXIT_GRACE} if a grandchild
 * holding the pipe keeps the EOF from coming. A script that is running is bounded by its build's
 * cancel, as a running script should be.
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
 *
 * <p>A request thread interrupted while it waits — a job torn down, a watch that gave up on it —
 * sends the same {@code CANCEL} before the interrupt goes on, so the script it leaves is not still
 * running when the next request writes its {@code RUN}. The host keeps its side of that: a {@code
 * RUN} arriving while a script is in flight is answered {@code BUSY}, naming both scripts, rather
 * than dropped; a request refused that way kills the host, so the running script's reply cannot
 * land on a later request, and fails naming the script.
 */
final class KtsSession {

    /** How long the host may sit without a script before the reaper shuts it down. */
    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);

    /** How long a cancelled script may take to stop before its host is killed instead. */
    static final Duration CANCEL_GRACE = Duration.ofSeconds(2);

    /** How long a starting host may take to say {@code READY} before it is killed instead. */
    static final Duration START_TIMEOUT = Duration.ofMinutes(2);

    /** How long a request waits for the reader's EOF once the host has exited under it. */
    static final Duration EXIT_GRACE = Duration.ofSeconds(5);

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

    /** The idle reaper, woken to leave when the session ends before its timeout. */
    private @Nullable Thread reaper;

    private KtsSession(Process process) {
        this.process = process;
        this.toChild = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader fromChild =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        // A platform thread, not a virtual one: the read parks natively on the pipe for as long as
        // the host is silent, and a carrier held that long starves every other virtual thread of a
        // one-carrier JVM (see the class comment). The .kts host outlives any one build, so the
        // reader must not carry a request's session either.
        Thread.ofPlatform().daemon().name("jk-kts-host-reader").start(() -> {
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
                // The child is gone — its stdout ended before the OS has necessarily reaped it, so
                // the liveness check below could still see it; the next script gets a new one
                // rather than inheriting a corpse.
                drop(session);
                throw new IllegalStateException(
                        "[build] logic: the .kts host died running " + script.getFileName()
                                + " (a script calling System.exit, or an out-of-memory, takes the shared host with it)"
                                + (e.tail().isEmpty() ? "" : ":\n" + e.tail()),
                        e);
            } catch (Refused e) {
                // The host was running another script when this RUN reached it: the protocol is
                // out of step, and the running script's reply would land on a later request. The
                // host goes; the next script starts a fresh one.
                session.process.destroyForcibly();
                session.process.waitFor(5, TimeUnit.SECONDS);
                drop(session);
                throw new IllegalStateException(
                        "[build] logic: the .kts host refused " + script.getFileName() + " — " + e.getMessage()
                                + "; the host was replaced",
                        e);
            } finally {
                // A host killed for ignoring a cancel — the probe's or an interrupt's — is dropped
                // whichever way the request left, so the next script does not inherit a corpse.
                if (!session.process.isAlive()) drop(session);
                session.lastUsedNanos = clock.nanos();
            }
        }
    }

    /** Forget {@code session} as the shared host and let its reaper go. Under {@link #LOCK}. */
    private static void drop(KtsSession session) {
        if (current == session) current = null;
        session.dismissReaper();
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

    /**
     * Test seam: a session on {@code process} with its reader running and no handshake made — for
     * measuring what the reader costs the JVM around it against a child that says nothing.
     */
    static void attachForTests(Process process) {
        new KtsSession(process);
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
        dismissReaper();
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
            throws IOException, InterruptedException, SessionDied, Refused {
        toChild.write("RUN\t" + script + "\t" + projectDir + "\t" + outDir + "\n");
        toChild.flush();
        String reply = awaitReply(script, cancelled);
        if (EOF.equals(reply)) throw new SessionDied(drain());
        if (reply.startsWith("BUSY "))
            throw new Refused(decode(reply.substring(5)).strip());
        if (reply.startsWith("OK ")) return decode(reply.substring(3));
        if (reply.startsWith("FAIL ")) {
            // Verbatim compiler/runtime output — its first line is the script's own file:line.
            // The one jk-authored prefix is registerScripts', which names the file once.
            throw new IllegalStateException(decode(reply.substring(5)).strip());
        }
        throw new SessionDied("unrecognised reply from the .kts host: " + reply);
    }

    /**
     * The next reply, watching the build's cancel probe and the host's life while it waits. An
     * interrupt while waiting cancels the script in the host first: a request that left with its
     * script running would have the next request's {@code RUN} answered by this script's reply.
     */
    private String awaitReply(Path script, BooleanSupplier cancelled) throws InterruptedException, SessionDied {
        String reply;
        try {
            while ((reply = replies.poll(CANCEL_POLL.toMillis(), TimeUnit.MILLISECONDS)) == null) {
                if (cancelled.getAsBoolean()) {
                    cancelInFlight();
                    throw new Cancelled(script);
                }
                if (!process.isAlive()) {
                    // The reader's EOF follows the exit at once, unless a grandchild the script
                    // started still holds the pipe's write end; either way the request ends here.
                    reply = replies.poll(EXIT_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                    if (reply == null) throw new SessionDied("exited " + process.exitValue() + " without replying");
                    break;
                }
            }
        } catch (InterruptedException e) {
            // The thrown interrupt cleared the flag, so the cancel exchange below can wait; the
            // interrupt is put back once the host is free again, or killed.
            cancelInFlight();
            Thread.currentThread().interrupt();
            throw e;
        }
        return reply;
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

    /**
     * The host process, ready to start: the provisioned Kotlin, the compiled host jar, and the
     * compiled-script cache in its environment. Its stdout carries the protocol; stderr is dropped,
     * since a JVM warning merged into stdout would be read as a reply.
     */
    static ProcessBuilder hostProcess() throws IOException, InterruptedException {
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
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Path cacheDir = compiledScriptCache();
        Files.createDirectories(cacheDir);
        pb.environment().put("JK_KTS_CACHE", cacheDir.toString());
        return pb;
    }

    private static KtsSession start() throws IOException, InterruptedException {
        // Started directly, not through JobWorkers: that registry belongs to the request on this
        // thread and kills its members when the request ends, and this host is the engine's —
        // reused by later builds, and possibly mid-script for another build right now. Its own
        // owners are the idle reaper below and the shutdown hook.
        Process p = hostProcess().start();
        registerShutdownHook();
        KtsSession session = new KtsSession(p);
        String ready = session.replies.poll(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!"READY".equals(ready)) {
            p.destroyForcibly();
            String said = ready == null
                    ? " within " + START_TIMEOUT.toSeconds() + " s"
                    : EOF.equals(ready) ? "" : " (said: " + ready + ")";
            throw new IllegalStateException("[build] logic: the .kts host did not start" + said);
        }
        // Idle reaper of the shared .kts host; reads no session.
        session.reaper = Thread.ofVirtual().name("jk-kts-host-reaper").start(() -> reap(session));
        return session;
    }

    /**
     * Shut {@code session} down once it has been idle for the timeout. Sleeps until the earliest
     * moment that could be true and re-checks: a script that ran in between moves the deadline.
     * Taking {@link #LOCK} means a script in flight is waited for, never cut off. A session ended
     * by someone else — the shutdown hook, a dead host — interrupts the sleep so the thread leaves
     * with the host rather than at the timeout.
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

    /** Wake the reaper to find the session ended; it leaves instead of sleeping out the timeout. */
    private void dismissReaper() {
        Thread t = reaper;
        if (t != null && t != Thread.currentThread()) t.interrupt();
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

    /** The host answered {@code BUSY}: another script was in flight when the request reached it. */
    private static final class Refused extends Exception {
        Refused(String detail) {
            super(detail);
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
