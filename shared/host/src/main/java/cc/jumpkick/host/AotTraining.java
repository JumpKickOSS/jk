// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Trains a JVM startup cache by starting the application, letting it settle, and asking it to stop:
 * a JEP 483/514 AOT cache (record, then create), or a dynamic CDS archive for a JDK older than 25.
 * Framework-agnostic, because the JVM writes its recording on SIGTERM as well as on a normal exit,
 * so a server is trained the same way as a command that ends by itself. Used by the image builder's
 * {@code [image] aot-cache} and the {@code [jlink]} runtime image.
 */
public final class AotTraining {

    /** No output for this long means startup has finished talking. */
    static final long QUIET_MILLIS = 4_000;

    /** Never stop an application before it has had a chance to get going. */
    static final long MIN_RUN_MILLIS = 3_000;

    private AotTraining() {}

    /**
     * How a run starts and stops: a JVM on this host, or one inside a container. {@code runName}
     * names each run, which a container needs to be addressed by.
     */
    public interface Launcher {
        /** The command up to and including the {@code java} executable. */
        List<String> java(String runName);

        /** The working directory of a run on this host; {@code null} for a container or the caller's. */
        default @Nullable Path cwd() {
            return null;
        }

        /** Variables laid over the environment a run on this host inherits. */
        default Map<String, String> env() {
            return Map.of();
        }

        /** Whether {@link #env()} is a run's whole environment rather than additions to an inherited one. */
        default boolean wholeEnv() {
            return false;
        }

        /** Start {@code pb}; a host that tracks its children starts it through its own registry. */
        default Process start(ProcessBuilder pb) throws IOException {
            return pb.start();
        }

        /** SIGTERM, and nothing harsher, waiting at most {@code grace}. */
        default void requestStop(Process process, String runName, Duration grace) {
            process.destroy();
        }

        /** An abandoned run: killed, not given time to write anything. */
        default void forceStop(String runName) {}
    }

    /** A file a run writes: {@code arg} as the JVM is told it, {@code file} where it lands on this host. */
    public record Target(String arg, Path file) {
        /** The same path both ways: a run on this host. */
        public static Target local(Path file) {
            return new Target(file.toString(), file);
        }
    }

    /** {@code stopped}: the run was asked to stop; otherwise it ended on its own. */
    public record Run(String text, int exit, boolean stopped) {
        /** Ended by itself with a failure: an application that crashed, not a server that was stopped. */
        public boolean diedOnItsOwn() {
            return !stopped && exit != 0;
        }
    }

    /**
     * Train a JEP 483/514 AOT cache into {@code cache}: a recording run, an assembling run, and a run
     * with the cache that proves the JVM maps it.
     *
     * <p>Two steps, deliberately. The one-step {@code -XX:AOTCacheOutput} assembles the cache from a
     * child JVM that the application spawns as it exits normally, and a server does not exit
     * normally, it is signalled. Under SIGTERM the recording is written and the child never runs.
     * Recording and assembling separately puts the assembly in the caller's hands.
     *
     * @param javaOpts the flags the application runs with; a cache trained under another collector
     *     or heap shape is one the production JVM silently declines
     * @param config where the recording goes; deleted once the cache is assembled
     */
    public static void trainAotCache(
            Launcher launcher,
            List<String> javaOpts,
            List<String> runArgs,
            Target cache,
            Target config,
            Duration timeout,
            Consumer<String> log)
            throws IOException, InterruptedException {
        Cmd record = command(
                launcher,
                "jk-aot-train-",
                javaOpts,
                runArgs,
                "-XX:AOTMode=record",
                "-XX:AOTConfiguration=" + config.arg());
        Run recorded = run(launcher, record, timeout, log);
        if (recorded.diedOnItsOwn()) {
            // The JVM still writes a recording at exit, so the file check below would pass and the
            // cache would describe a process that never started.
            throw new IOException("the training run exited " + recorded.exit() + " before it settled.\n"
                    + "  command: " + record.line() + "\n"
                    + tail(recorded.text()));
        }
        if (sizeOrZero(config.file()) == 0) {
            throw new IOException("the training run recorded nothing.\n"
                    + "  command: " + record.line() + "\n"
                    + tail(recorded.text()));
        }

        Cmd assemble = command(
                launcher,
                "jk-aot-create-",
                javaOpts,
                runArgs,
                "-XX:AOTMode=create",
                "-XX:AOTConfiguration=" + config.arg(),
                "-XX:AOTCache=" + cache.arg());
        Run assembled = run(launcher, assemble, timeout, log);
        if (assembled.diedOnItsOwn() || sizeOrZero(cache.file()) == 0) {
            throw new IOException("the recording could not be assembled into a cache.\n"
                    + "  command: " + assemble.line() + "\n"
                    + tail(assembled.text()));
        }
        Files.deleteIfExists(config.file());

        // Prove it loads. A rejected cache is silent at default log level, so an unverified one is
        // indistinguishable from a working one.
        Cmd verify =
                command(launcher, "jk-aot-verify-", javaOpts, runArgs, "-Xlog:aot=info", "-XX:AOTCache=" + cache.arg());
        Run verified = run(launcher, verify, timeout, log);
        if (verified.diedOnItsOwn()) {
            throw new IOException("the application does not start with the cache (exit " + verified.exit() + ").\n"
                    + "  command: " + verify.line() + "\n"
                    + tail(verified.text()));
        }
        String refusal = AotCacheFiles.refusal(verified.text());
        if (refusal != null) {
            throw new IOException("the AOT cache was trained but the JVM refused it:\n  " + refusal);
        }
    }

    /**
     * Train a dynamic CDS archive into {@code archive} ({@code -XX:ArchiveClassesAtExit}), then prove
     * the JVM maps it: the proving run insists on the archive ({@code -Xshare:on}), so a JVM that
     * cannot use it fails to start. The JVM needs a base CDS archive to build on.
     */
    public static void trainCdsArchive(
            Launcher launcher,
            List<String> javaOpts,
            List<String> runArgs,
            Target archive,
            Duration timeout,
            Consumer<String> log)
            throws IOException, InterruptedException {
        Cmd record = command(launcher, "jk-cds-train-", javaOpts, runArgs, "-XX:ArchiveClassesAtExit=" + archive.arg());
        Run recorded = run(launcher, record, timeout, log);
        if (recorded.diedOnItsOwn()) {
            throw new IOException("the training run exited " + recorded.exit() + " before it settled.\n"
                    + "  command: " + record.line() + "\n"
                    + tail(recorded.text()));
        }
        if (sizeOrZero(archive.file()) == 0) {
            throw new IOException("the training run wrote no CDS archive.\n"
                    + "  command: " + record.line() + "\n"
                    + tail(recorded.text()));
        }
        Cmd verify = command(
                launcher, "jk-cds-verify-", javaOpts, runArgs, "-Xshare:on", "-XX:SharedArchiveFile=" + archive.arg());
        Run verified = run(launcher, verify, timeout, log);
        if (verified.diedOnItsOwn()) {
            throw new IOException("the CDS archive was trained but the JVM refused it (exit " + verified.exit()
                    + ").\n  command: " + verify.line() + "\n"
                    + tail(verified.text()));
        }
    }

    /** One run: its name and its command line. */
    private record Cmd(String name, List<String> argv) {
        String line() {
            return String.join(" ", argv);
        }
    }

    private static Cmd command(
            Launcher launcher, String runPrefix, List<String> javaOpts, List<String> runArgs, String... flags) {
        String name = runPrefix + UUID.randomUUID();
        List<String> argv = new ArrayList<>(launcher.java(name));
        argv.addAll(javaOpts);
        argv.addAll(List.of(flags));
        argv.addAll(runArgs);
        return new Cmd(name, List.copyOf(argv));
    }

    private static Run run(Launcher launcher, Cmd cmd, Duration timeout, Consumer<String> log)
            throws IOException, InterruptedException {
        return runUntilSettled(launcher, cmd.argv(), cmd.name(), timeout, log);
    }

    /**
     * Start {@code command}, wait for it to settle, then ask it to stop, and return everything it
     * said. "Settled" is {@value #QUIET_MILLIS} ms with no output after at least {@value
     * #MIN_RUN_MILLIS} ms, which is a startup that has stopped logging; a run that has not settled
     * by {@code timeout} is asked to stop as well. A command that exits by itself is finished, and
     * its output is returned as it is.
     */
    public static Run runUntilSettled(
            Launcher launcher, List<String> command, String runName, Duration timeout, Consumer<String> log)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Path cwd = launcher.cwd();
        if (cwd != null) pb.directory(cwd.toFile());
        Map<String, String> environment = pb.environment();
        if (launcher.wholeEnv()) environment.clear();
        environment.putAll(launcher.env());
        Process process = launcher.start(pb);
        StringBuilder out = new StringBuilder();
        AtomicLong lastOutput = new AtomicLong(Clock.SYSTEM.nanos());
        // A platform thread: a virtual thread blocked on a pipe read pins its carrier.
        Thread reader = new Thread(() -> {
            try (var in = process.inputReader()) {
                in.lines().forEach(line -> {
                    synchronized (out) {
                        out.append(line).append('\n');
                    }
                    lastOutput.set(Clock.SYSTEM.nanos());
                });
            } catch (IOException ignored) {
            }
        });
        reader.setDaemon(true);
        reader.start();

        long timeoutMs = timeout.toMillis();
        long started = Clock.SYSTEM.nanos();
        boolean asked = false;
        boolean killed = false;
        try {
            while (process.isAlive()) {
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) break;
                long elapsedMs = (Clock.SYSTEM.nanos() - started) / 1_000_000L;
                long quietMs = (Clock.SYSTEM.nanos() - lastOutput.get()) / 1_000_000L;
                if (elapsedMs > timeoutMs) {
                    log.accept("the run did not settle within " + timeout.toSeconds() + "s — stopping it");
                    asked = true;
                } else if (elapsedMs >= MIN_RUN_MILLIS && quietMs >= QUIET_MILLIS) {
                    asked = true;
                }
                if (asked) {
                    synchronized (out) {
                        out.append("[jk] settled after ").append(elapsedMs).append("ms — asking it to stop\n");
                    }
                    launcher.requestStop(process, runName, timeout);
                    // The JVM writes its recording from a shutdown hook; give it room to finish.
                    process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                    break;
                }
            }
            killed = process.isAlive();
            if (killed) process.destroyForcibly();
            reader.join(5_000);
        } finally {
            if (process.isAlive()) {
                launcher.forceStop(runName);
                process.destroyForcibly();
            }
        }
        synchronized (out) {
            out.append("[jk] ")
                    .append(killed ? "did not stop when asked; killed" : "exited " + process.exitValue())
                    .append('\n');
            return new Run(out.toString(), process.isAlive() ? -1 : process.exitValue(), asked);
        }
    }

    /** The last lines of a run's output, the JVM's own AOT and CDS logging left out. */
    public static String tail(String text) {
        return AotCacheFiles.appTail(text, 20);
    }

    private static long sizeOrZero(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.size(p) : 0;
        } catch (IOException e) {
            return 0;
        }
    }
}
