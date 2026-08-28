// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.engine.JobWorkers;
import cc.jumpkick.host.Classpaths;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
 */
final class KtsSession {

    private static final Object LOCK = new Object();
    private static KtsSession current;
    private static boolean hookRegistered;

    private final Process process;
    private final BufferedWriter toChild;
    private final BufferedReader fromChild;

    private KtsSession(Process process) {
        this.process = process;
        this.toChild = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.fromChild = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /**
     * Evaluate one script. Returns its captured stdout/stderr on success; throws with that output
     * attached on failure.
     */
    static String run(Path script, Path projectDir, Path outDir) throws IOException, InterruptedException {
        synchronized (LOCK) {
            if (current != null && !current.process.isAlive()) current = null;
            if (current == null) current = start();
            try {
                return current.request(script, projectDir, outDir);
            } catch (SessionDied e) {
                // The child is gone; the next script gets a new one rather than inheriting a corpse.
                current = null;
                throw new IllegalStateException(
                        "[build] logic: the .kts host died running " + script.getFileName()
                                + " (a script calling System.exit, or an out-of-memory, takes the shared host with it)"
                                + (e.tail().isEmpty() ? "" : ":\n" + e.tail()),
                        e);
            }
        }
    }

    /** Shut the session down, if one is running. Called at the end of a build. */
    static void shutdown() {
        synchronized (LOCK) {
            if (current == null) return;
            KtsSession s = current;
            current = null;
            try {
                s.toChild.write("EXIT\n");
                s.toChild.flush();
                if (!s.process.waitFor(5, TimeUnit.SECONDS)) s.process.destroyForcibly();
            } catch (IOException | InterruptedException e) {
                s.process.destroyForcibly();
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
    }

    private String request(Path script, Path projectDir, Path outDir) throws IOException, SessionDied {
        toChild.write("RUN\t" + script + "\t" + projectDir + "\t" + outDir + "\n");
        toChild.flush();
        String reply = fromChild.readLine();
        if (reply == null) throw new SessionDied(drain());
        if (reply.startsWith("OK ")) return decode(reply.substring(3));
        if (reply.startsWith("FAIL ")) {
            throw new IllegalStateException(
                    "[build] logic: " + script.getFileName() + " failed:\n" + decode(reply.substring(5)).strip());
        }
        throw new SessionDied("unrecognised reply from the .kts host: " + reply);
    }

    /** Whatever the child managed to say before dying — usually the JVM's own error. */
    private String drain() {
        StringBuilder sb = new StringBuilder();
        try {
            String l;
            while ((l = fromChild.readLine()) != null) sb.append(l).append('\n');
        } catch (IOException ignored) {
            // The pipe is already gone; report what was read.
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

        Process p = JobWorkers.start(pb);
        registerShutdownHook();
        KtsSession session = new KtsSession(p);
        String ready = session.fromChild.readLine();
        if (!"READY".equals(ready)) {
            p.destroyForcibly();
            throw new IllegalStateException(
                    "[build] logic: the .kts host did not start" + (ready == null ? "" : " (said: " + ready + ")"));
        }
        return session;
    }

    /**
     * The child outlives any single script, so nothing else would reap it if the build exits early
     * — a cancelled build would leave a JVM holding the compiler open.
     */
    private static void registerShutdownHook() {
        if (hookRegistered) return;
        hookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(KtsSession::shutdown, "jk-kts-host-shutdown"));
    }

    /**
     * Where compiled scripts live. Under the cache tier, not the store: a compiled script is
     * derived from a source file that is still on disk, so losing it costs one recompile.
     */
    static Path compiledScriptCache() {
        return JkDirs.cache().resolve("kts");
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
