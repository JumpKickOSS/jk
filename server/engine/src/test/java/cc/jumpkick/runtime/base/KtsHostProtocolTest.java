// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.HostPressure;
import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.engine.plugin.WorkerCommands;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The request protocol between {@link KtsSession} and the {@code .kts} host stays in step when a
 * request ends before its reply: the reply a script produces answers that script's request and no
 * other. Tagged {@code integration}: each test provisions Kotlin and starts a host.
 */
@Tag("integration")
class KtsHostProtocolTest {

    @BeforeEach
    void normalHostPressure() {
        HostPressure.forTests(HostPressure.Level.NORMAL);
    }

    @AfterEach
    void hostPressureFromTheHost() {
        HostPressure.forTests(null);
    }

    @TempDir
    Path cacheDir;

    @BeforeEach
    void isolateCompiledScriptCache() {
        System.setProperty("jk.env.JK_CACHE_DIR", cacheDir.toString());
        KtsSession.shutdown();
    }

    @AfterEach
    void restoreCache() {
        KtsSession.shutdown();
        KtsSession.cancelGraceForTests(null);
    }

    /**
     * A request thread interrupted while it waits for its reply — a job torn down, a watch that
     * gave up on it — leaves with the script cancelled in the host, not still running. Left in
     * flight, its reply would be the next request's, which the host would otherwise have dropped
     * unread: the next build's script would be answered with this one's result and its own file
     * never written.
     */
    @Test
    void an_interrupted_request_cancels_its_script_and_the_next_script_gets_its_own_reply(@TempDir Path dir)
            throws Exception {
        Path slow = dir.resolve("slow.kts");
        Files.writeString(slow, """
                Files.writeString(outDir.resolve("started"), "1")
                Thread.sleep(60_000)
                Files.writeString(outDir.resolve("done"), "1")
                """);
        Path quick = dir.resolve("quick.kts");
        Files.writeString(quick, "Files.writeString(outDir.resolve(\"quick.txt\"), \"1\")\n");
        Path project = Files.createDirectories(dir.resolve("p"));
        Path outA = Files.createDirectories(dir.resolve("a"));
        Path outB = Files.createDirectories(dir.resolve("b"));
        // Compiled ahead, so the run after the interrupt measures the host's readiness, not kotlinc.
        BuildLogicKtsHost.evaluate(quick, project, outB);
        Files.delete(outB.resolve("quick.txt"));
        long pid = KtsSession.hostPid();

        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread request = new Thread(() -> {
            try {
                KtsSession.run(slow, project, outA, () -> false);
            } catch (Throwable t) {
                outcome.set(t);
            }
        });
        request.start();
        awaitFile(outA.resolve("started"));

        KtsSession.cancelGraceForTests(Duration.ofSeconds(30));
        request.interrupt();
        request.join(TimeUnit.SECONDS.toMillis(30));
        BuildLogicKtsHost.evaluate(quick, project, outB);

        assertThat(outcome.get())
                .as("the interrupt leaves the request as an interrupt")
                .isInstanceOf(InterruptedException.class);
        assertThat(outB.resolve("quick.txt"))
                .as("the next script's reply is its own: its file exists")
                .exists();
        assertThat(outA.resolve("done"))
                .as("the interrupted script stopped where the cancel found it")
                .doesNotExist();
        assertThat(KtsSession.hostPid())
                .as("a script that honours the interrupt leaves the host standing")
                .isEqualTo(pid);
    }

    /**
     * The host runs one script at a time and says so. A {@code RUN} that arrives while one is in
     * flight is answered at once with {@code BUSY} naming both scripts, rather than dropped and left
     * to take the running script's reply as its own; the running script goes on and {@code CANCEL}
     * still reaches it.
     */
    @Test
    void a_run_arriving_while_one_is_in_flight_is_refused_naming_both_scripts(@TempDir Path dir) throws Exception {
        Path slow = dir.resolve("slow.kts");
        Files.writeString(slow, """
                Files.writeString(outDir.resolve("started"), "1")
                Thread.sleep(60_000)
                Files.writeString(outDir.resolve("done"), "1")
                """);
        Path quick = dir.resolve("quick.kts");
        Files.writeString(quick, "Files.writeString(outDir.resolve(\"quick.txt\"), \"1\")\n");
        Path project = Files.createDirectories(dir.resolve("p"));
        Path outA = Files.createDirectories(dir.resolve("a"));
        Path outB = Files.createDirectories(dir.resolve("b"));

        ProcessBuilder pb = KtsSession.hostProcess();
        try (WorkerCommands toHost = WorkerCommands.open()) {
            List<String> command = new ArrayList<>(pb.command());
            command.addAll(toHost.args());
            Process host = pb.command(command).start();
            PluginProcess.closeStdin(host);
            try {
                BufferedReader fromHost =
                        new BufferedReader(new InputStreamReader(host.getInputStream(), StandardCharsets.UTF_8));
                assertThat(fromHost.readLine()).isEqualTo("READY");
                toHost.send("RUN\t" + slow + "\t" + project + "\t" + outA);
                awaitFile(outA.resolve("started"));

                toHost.send("RUN\t" + quick + "\t" + project + "\t" + outB);
                String reply = fromHost.readLine();
                assertThat(reply).as("the second RUN is refused, not dropped").startsWith("BUSY ");
                String detail =
                        new String(Base64.getDecoder().decode(reply.substring(5).strip()), StandardCharsets.UTF_8);
                assertThat(detail).contains("slow.kts").contains("quick.kts");
                assertThat(outB.resolve("quick.txt"))
                        .as("the refused script did not run")
                        .doesNotExist();

                toHost.send("CANCEL");
                assertThat(fromHost.readLine())
                        .as("the running script is still the one in flight")
                        .isEqualTo("CANCELLED");
                assertThat(outA.resolve("done")).doesNotExist();
                toHost.send("EXIT");
                assertThat(host.waitFor(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                host.destroyForcibly();
            }
        }
    }

    /**
     * The host's requests come over its command channel and its stdin is closed: a script that reads
     * {@code System.in}, and one whose {@code inheritIO()} child reads it, both read end-of-input and
     * finish, and the next script's request reaches the same host intact.
     */
    @Test
    void scripts_that_read_stdin_finish_and_the_next_request_reaches_the_host(@TempDir Path dir) throws Exception {
        Path reads = dir.resolve("reads.kts");
        Files.writeString(
                reads, "Files.writeString(outDir.resolve(\"read.txt\"), System.`in`.readBytes().size.toString())\n");
        Path child = dir.resolve("StdinChild.java");
        Files.writeString(child, """
                class StdinChild {
                    public static void main(String[] args) throws Exception {
                        System.exit(System.in.readAllBytes().length);
                    }
                }
                """);
        String java = Path.of(System.getProperty("java.home"), "bin", "java")
                .toString()
                .replace("\\", "/");
        Path forks = dir.resolve("forks.kts");
        Files.writeString(forks, """
                val child = ProcessBuilder("%s", "%s").inheritIO().start()
                Files.writeString(outDir.resolve("child.txt"), child.waitFor().toString())
                """.formatted(java, child.toString().replace("\\", "/")));
        Path quick = dir.resolve("quick.kts");
        Files.writeString(quick, "Files.writeString(outDir.resolve(\"quick.txt\"), \"1\")\n");
        Path project = Files.createDirectories(dir.resolve("p"));
        Path out = Files.createDirectories(dir.resolve("out"));

        KtsSession.run(reads, project, out, () -> false);
        long pid = KtsSession.hostPid();
        KtsSession.run(forks, project, out, () -> false);
        KtsSession.run(quick, project, out, () -> false);

        assertThat(out.resolve("read.txt")).as("System.in was at its end").hasContent("0");
        assertThat(out.resolve("child.txt"))
                .as("the child's inherited stdin was at its end")
                .hasContent("0");
        assertThat(out.resolve("quick.txt"))
                .as("the next request reached the host")
                .exists();
        assertThat(KtsSession.hostPid()).as("one host served all three").isEqualTo(pid);
    }

    private static void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(file).as("the script is running").exists();
    }
}
