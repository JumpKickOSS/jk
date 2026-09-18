// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
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

    @TempDir
    Path cacheDir;

    private String prevCacheDir;

    @BeforeEach
    void isolateCompiledScriptCache() {
        prevCacheDir = System.getProperty("jk.env.JK_CACHE_DIR");
        System.setProperty("jk.env.JK_CACHE_DIR", cacheDir.toString());
        KtsSession.shutdown();
    }

    @AfterEach
    void restoreCache() {
        KtsSession.shutdown();
        if (prevCacheDir == null) System.clearProperty("jk.env.JK_CACHE_DIR");
        else System.setProperty("jk.env.JK_CACHE_DIR", prevCacheDir);
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
                Thread.sleep(3_000)
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

        long interruptedAt = System.nanoTime();
        request.interrupt();
        request.join(TimeUnit.SECONDS.toMillis(30));
        BuildLogicKtsHost.evaluate(quick, project, outB);
        Duration untilNextScript = Duration.ofNanos(System.nanoTime() - interruptedAt);

        assertThat(outcome.get())
                .as("the interrupt leaves the request as an interrupt")
                .isInstanceOf(InterruptedException.class);
        assertThat(outB.resolve("quick.txt"))
                .as("the next script's reply is its own: its file exists")
                .exists();
        assertThat(outA.resolve("done"))
                .as("the interrupted script stopped where the cancel found it")
                .doesNotExist();
        assertThat(untilNextScript)
                .as("the next script ran within the cancel grace")
                .isLessThan(KtsSession.CANCEL_GRACE);
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
                Thread.sleep(5_000)
                Files.writeString(outDir.resolve("done"), "1")
                """);
        Path quick = dir.resolve("quick.kts");
        Files.writeString(quick, "Files.writeString(outDir.resolve(\"quick.txt\"), \"1\")\n");
        Path project = Files.createDirectories(dir.resolve("p"));
        Path outA = Files.createDirectories(dir.resolve("a"));
        Path outB = Files.createDirectories(dir.resolve("b"));

        Process host = KtsSession.hostProcess().start();
        try (BufferedWriter toHost =
                        new BufferedWriter(new OutputStreamWriter(host.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader fromHost =
                        new BufferedReader(new InputStreamReader(host.getInputStream(), StandardCharsets.UTF_8))) {
            assertThat(fromHost.readLine()).isEqualTo("READY");
            toHost.write("RUN\t" + slow + "\t" + project + "\t" + outA + "\n");
            toHost.flush();
            awaitFile(outA.resolve("started"));

            toHost.write("RUN\t" + quick + "\t" + project + "\t" + outB + "\n");
            toHost.flush();
            String reply = fromHost.readLine();
            assertThat(reply).as("the second RUN is refused, not dropped").startsWith("BUSY ");
            String detail =
                    new String(Base64.getDecoder().decode(reply.substring(5).strip()), StandardCharsets.UTF_8);
            assertThat(detail).contains("slow.kts").contains("quick.kts");
            assertThat(outB.resolve("quick.txt"))
                    .as("the refused script did not run")
                    .doesNotExist();

            toHost.write("CANCEL\n");
            toHost.flush();
            assertThat(fromHost.readLine())
                    .as("the running script is still the one in flight")
                    .isEqualTo("CANCELLED");
            assertThat(outA.resolve("done")).doesNotExist();
            toHost.write("EXIT\n");
            toHost.flush();
            assertThat(host.waitFor(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            host.destroyForcibly();
        }
    }

    private static void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(file).as("the script is running").exists();
    }
}
