// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.EngineTransport;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Ctrl-C with stdout redirected — a pipe, a CI log, {@code jk build | tee}. Every one of those is a
 * context where a runaway build is hardest to see and, if the handler only worked under a pty,
 * impossible to stop from the keyboard.
 *
 * <p>Signals cannot be tested in-process: the handler ends in {@link Runtime#halt}, which would take
 * the test JVM with it. So each case spawns a real {@code jk} on this suite's classpath with its
 * stdout redirected to a <em>file</em> — genuinely not a TTY, no pty allocated anywhere — talks it
 * onto a live job against a stub engine, sends it a real {@code SIGINT}, and asserts on what the
 * process and the engine actually did. A test that only proved the handler was registered would
 * prove nothing about either.
 *
 * <p>Two routes out of a Ctrl-C, and they must agree:
 *
 * <ul>
 *   <li>the verb is wedged on the engine, so the handler's own {@code halt} ends the process;
 *   <li>the verb notices and unwinds first, so the entry point's {@code System.exit} does.
 * </ul>
 *
 * The second one exited {@code 1} until — the same Ctrl-C, a different number, decided by a
 * race no user can.
 */
@Tag("integration")
@DisabledOnOs(OS.WINDOWS) // SIGINT is a POSIX signal; the Windows console path is Ctrl-C events
class GlobalCancelNonTtyTest {

    /** Long enough for a JVM cold start plus the handler's own bounded waits, short enough to fail. */
    private static final int EXIT_WAIT_SECONDS = 45;

    /**
     * Under {@code /tmp}, not {@code @TempDir}: the stub engine below binds a real Unix domain
     * socket inside this home, and the JDK refuses to bind one past
     * {@code UnixSocketPaths.MAX_PATH_LENGTH} characters — the module's {@code build/tmp} JUnit
     * root is well past that once the socket name is appended.
     */
    private Path home;

    @AfterEach
    void removeHome() throws IOException {
        if (home == null || !Files.exists(home)) return;
        try (var walk = Files.walk(home)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        }
    }

    private Path newHome() throws IOException {
        home = Files.createTempDirectory(ShortTempDirs.root(), "jk-sigint-");
        return home;
    }

    /**
     * The wedged-verb route. The stub engine answers the job and then goes quiet: it never pushes a
     * terminal and never acknowledges the cancel, so nothing but the SIGINT handler can end this
     * process. If the handler no-ops when stdout is not a TTY — the report — the child
     * outlives the wait and this fails.
     *
     * <p>The load-bearing assertion is the {@code cancel-request} the engine received, not the exit
     * code: SIGINT's default disposition also terminates a process as {@code 128 + SIGINT}, so
     * {@code 130} alone cannot tell a handler that ran from one that was never installed. What only
     * a handler that ran to completion produces is the by-jid cancel on the engine socket.
     */
    @Test
    void ctrl_c_through_a_redirected_stdout_cancels_the_engine_job_and_exits_interrupted() throws Exception {
        Path tmp = newHome();
        try (StubEngine engine = StubEngine.start(tmp, StubEngine.OnCancel.STAY_SILENT)) {
            Path out = tmp.resolve("stdout.log");
            Process cli = spawnCli(tmp, out);
            try {
                assertThat(engine.jobStarted.await(EXIT_WAIT_SECONDS, TimeUnit.SECONDS))
                        .as(
                                "no lock request reached the stub; unexpected=%s\n%s",
                                engine.unexpectedRequests(), read(out))
                        .isTrue();

                interrupt(cli);

                assertThat(cli.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS))
                        .as("Ctrl-C did nothing: the process survived it with stdout on a file\n%s", read(out))
                        .isTrue();
                assertThat(cli.exitValue()).as("stdout: %s", read(out)).isEqualTo(Exit.INTERRUPTED);
                assertThat(engine.cancelledJids())
                        .as(
                                "Ctrl-C must reach the engine by jid, the only handle that works when"
                                        + " the client stays alive\n%s",
                                read(out))
                        .contains(engine.jid);
            } finally {
                cli.destroyForcibly();
            }
        }
    }

    /**
     * The unwinding-verb route, which is the common one on a TTY and reachable off one whenever the
     * engine settles the stream. The stub drops the job connection the moment the cancel arrives, so
     * the verb returns an ordinary failure and the entry point exits on the main thread — while the
     * handler is still parked on the unanswered cancel RPC and its {@code halt} is seconds away.
     * Before that race decided whether a Ctrl-C reported {@code 130} or {@code 1}.
     */
    @Test
    void a_verb_that_unwinds_before_the_handler_halts_still_exits_interrupted() throws Exception {
        Path tmp = newHome();
        try (StubEngine engine = StubEngine.start(tmp, StubEngine.OnCancel.DROP_THE_JOB_STREAM)) {
            Path out = tmp.resolve("stdout.log");
            Process cli = spawnCli(tmp, out);
            try {
                assertThat(engine.jobStarted.await(EXIT_WAIT_SECONDS, TimeUnit.SECONDS))
                        .as(
                                "no lock request reached the stub; unexpected=%s\n%s",
                                engine.unexpectedRequests(), read(out))
                        .isTrue();

                interrupt(cli);

                assertThat(cli.waitFor(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                assertThat(cli.exitValue())
                        .as("the verb unwound first and reported its own code instead of the interrupt\n%s", read(out))
                        .isEqualTo(Exit.INTERRUPTED);
            } finally {
                cli.destroyForcibly();
            }
        }
    }

    // --- harness ---------------------------------------------------------------------------

    /**
     * Spawn a real {@code jk} on this suite's classpath, stdout+stderr redirected to a file.
     *
     * <p>{@code jk lock --offline} because it is the shortest route to a live engine job: no
     * project-info preflight, and {@code --offline} skips the catalog freshen, so the very first
     * line the stub sees on a job connection is the request itself.
     */
    private static Process spawnCli(Path home, Path out) throws IOException {
        Path project = Files.createDirectories(home.resolve("project"));
        Files.writeString(project.resolve("jk.toml"), """
                name = "sigint-probe"
                group = "com.example"
                version = "0.1.0"
                """);
        ProcessBuilder pb = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "cc.jumpkick.cli.Jk",
                "lock",
                "--offline");
        pb.directory(project.toFile());
        pb.environment().put("JK_HOME", home.toString());
        // The integrationTest task exports JK_STATE_DIR/JK_STORE_DIR for the whole tier, and a
        // role-specific override beats JK_HOME outright — so the child would look for its engine
        // under the suite's shared home instead of this one. Pin both to the same pair the stub
        // hashed its socket name from.
        pb.environment().put("JK_STATE_DIR", stateDir(home).toString());
        pb.environment().put("JK_STORE_DIR", storeDir(home).toString());
        pb.environment().put("JK_JDKS_DIR", home.resolve("jdks").toString());
        pb.environment().put("JK_HTTP_ENABLED", "false");
        pb.environment().put("JK_AUTO_PRUNE", "false");
        // The stub below binds a Unix domain socket by hand, so this child must speak that lane
        // whatever the tier defaults to. The tier sets JK_ENGINE_TRANSPORT=tcp and the child
        // inherits it; without this pin the client reads the socket file expecting a port number.
        pb.environment().put(EngineTransport.TRANSPORT_ENV, "unix");
        pb.redirectErrorStream(true);
        pb.redirectOutput(out.toFile());
        pb.redirectInput(new File("/dev/null"));
        return pb.start();
    }

    /** The engine identity is hashed from this pair, so both sides must name it the same way. */
    private static Path stateDir(Path home) {
        return home.resolve("state");
    }

    private static Path storeDir(Path home) {
        return home.resolve("store");
    }

    /** A real {@code SIGINT}, delivered exactly as a shell's Ctrl-C would deliver it. */
    private static void interrupt(Process cli) throws Exception {
        int killed = new ProcessBuilder("kill", "-INT", Long.toString(cli.pid()))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
        assertThat(killed).as("could not deliver SIGINT to the spawned jk").isZero();
    }

    private static String read(Path out) {
        try {
            return Files.readString(out);
        } catch (IOException e) {
            return "<no output captured>";
        }
    }

    /**
     * The smallest engine the CLI will talk to: a handshake, one job that never finishes, and a
     * ledger of the {@code cancel-request} lines it was sent. Deliberately does <em>not</em>
     * acknowledge a cancel — that is what keeps the handler's {@code halt} parked behind its socket
     * watchdog long enough for the second test's race to be a decision rather than a coin flip.
     */
    private static final class StubEngine implements AutoCloseable {

        enum OnCancel {
            /** Never reply, never settle: only the SIGINT handler can end the client. */
            STAY_SILENT,
            /** Close the job stream so the verb unwinds and the entry point owns the exit. */
            DROP_THE_JOB_STREAM
        }

        private final ServerSocketChannel listener;
        private final OnCancel onCancel;
        private final ConcurrentLinkedQueue<String> cancels = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<String> unexpected = new ConcurrentLinkedQueue<>();
        private volatile SocketChannel jobConnection;
        final CountDownLatch jobStarted = new CountDownLatch(1);
        final long jid = 42L;

        private StubEngine(ServerSocketChannel listener, OnCancel onCancel) {
            this.listener = listener;
            this.onCancel = onCancel;
        }

        static StubEngine start(Path home, OnCancel onCancel) throws IOException {
            EnginePaths.Paths paths = EnginePaths.resolve(stateDir(home), storeDir(home));
            Files.createDirectories(paths.dir());
            ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            listener.bind(UnixDomainSocketAddress.of(paths.socket()));
            StubEngine engine = new StubEngine(listener, onCancel);
            Thread accept = new Thread(engine::acceptLoop, "stub-engine-accept");
            accept.setDaemon(true);
            accept.start();
            return engine;
        }

        List<String> unexpectedRequests() {
            return List.copyOf(unexpected);
        }

        List<Long> cancelledJids() {
            return cancels.stream().map(l -> Jsonl.longValue(l, "jid", -1)).toList();
        }

        private void acceptLoop() {
            while (listener.isOpen()) {
                SocketChannel ch;
                try {
                    ch = listener.accept();
                } catch (IOException e) {
                    return; // closed
                }
                Thread t = new Thread(() -> serve(ch), "stub-engine-conn");
                t.setDaemon(true);
                t.start();
            }
        }

        private void serve(SocketChannel ch) {
            try {
                BufferedReader in =
                        new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                BufferedWriter w = new BufferedWriter(
                        new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null) {
                    String type = EngineProtocol.typeOf(line);
                    if (EngineProtocol.HELLO.equals(type)) {
                        // Empty buildId: the client treats a same-version engine as primary and
                        // never tries to spawn or displace one.
                        send(
                                w,
                                ProtoLifecycle.helloAck(
                                        Jk.VERSION, ProcessHandle.current().pid(), 1L, false, ""));
                    } else if (EngineProtocol.PING.equals(type)) {
                        send(w, ProtoLifecycle.pong());
                    } else if (EngineProtocol.CANCEL_REQUEST.equals(type)) {
                        cancels.add(line);
                        if (onCancel == OnCancel.DROP_THE_JOB_STREAM) closeQuietly(jobConnection);
                        // No cancel-ack on purpose — see the class javadoc.
                    } else if (EngineProtocol.LOCK_REQUEST.equals(type)) {
                        jobConnection = ch;
                        String dir = Jsonl.str(line, "dir");
                        send(w, ProtoLifecycle.jobStart(jid, "lock", dir == null ? "" : dir, 1L));
                        jobStarted.countDown();
                        // …and nothing else, ever. The job never finishes.
                    } else {
                        // Anything else means the verb grew a preflight this stub does not model.
                        // Fail it loudly rather than parking the client on a read that never ends.
                        unexpected.add(String.valueOf(type));
                        send(w, ProtoLifecycle.requestFailed("stub engine does not serve " + type));
                    }
                }
            } catch (IOException ignored) {
                // client gone — every assertion is about what it did before that
            } finally {
                closeQuietly(ch);
            }
        }

        private static void send(BufferedWriter w, String line) throws IOException {
            w.write(line);
            w.write('\n');
            w.flush();
        }

        private static void closeQuietly(SocketChannel ch) {
            if (ch == null) return;
            try {
                ch.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }
}
