// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cc.jumpkick.engine.plugin.JobWorkers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The guarantees that only hold because {@code .kts} scripts share one JVM and one compiled-script
 * cache. Tagged {@code integration}: each of these provisions Kotlin and compiles a script.
 *
 * <p>{@link BuildLogicScriptLanguageTest} covers a script running at all. This covers what the
 * shared session buys and what it costs.
 */
@Tag("integration")
class KtsSessionTest {

    @TempDir
    Path cacheDir;

    private String prevCacheDir;

    /**
     * Point the compiled-script cache at a temp dir. Counting jars in the developer's real cache
     * would be both a pollution of it and a race against whatever else is building.
     */
    @BeforeEach
    void isolateCompiledScriptCache() {
        prevCacheDir = System.getProperty("jk.env.JK_CACHE_DIR");
        System.setProperty("jk.env.JK_CACHE_DIR", cacheDir.toString());
        KtsSession.shutdown(); // a session started under a different cache must not be reused
    }

    @AfterEach
    void restoreCache() {
        KtsSession.shutdown();
        if (prevCacheDir == null) System.clearProperty("jk.env.JK_CACHE_DIR");
        else System.setProperty("jk.env.JK_CACHE_DIR", prevCacheDir);
    }

    /**
     * The point of declaring bindings instead of injecting them. The same script text run against
     * two projects must compile once: its content hash cannot vary by project, because the paths
     * arrive at evaluation time. The forked host it replaced wrote the absolute {@code projectDir}
     * into the source, so this produced two compilations of two different files.
     */
    @Test
    void one_compiled_jar_serves_two_projects(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("shared.kts");
        Files.writeString(script, """
                Files.writeString(outDir.resolve("who.txt"), projectDir.fileName.toString())
                """);
        Path cache = KtsSession.compiledScriptCache();
        Files.createDirectories(cache);
        long before = jarCount(cache);

        for (String name : List.of("alpha", "beta")) {
            Path project = Files.createDirectories(dir.resolve(name));
            Path out = Files.createDirectories(dir.resolve("out-" + name));
            BuildLogicKtsHost.evaluate(script, project, out);
            assertEquals(name, Files.readString(out.resolve("who.txt")).trim());
        }

        assertEquals(before + 1, jarCount(cache), "two projects, one script, one compilation");
    }

    /** A second run of an unchanged script must not compile it again. */
    @Test
    void an_unchanged_script_is_not_recompiled(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("stable.kts");
        Files.writeString(script, "Files.writeString(outDir.resolve(\"a.txt\"), \"1\")\n");
        Path project = Files.createDirectories(dir.resolve("p"));
        Path out = Files.createDirectories(dir.resolve("o"));
        Path cache = KtsSession.compiledScriptCache();
        Files.createDirectories(cache);

        BuildLogicKtsHost.evaluate(script, project, out);
        long after = jarCount(cache);
        BuildLogicKtsHost.evaluate(script, project, out);
        assertEquals(after, jarCount(cache));
    }

    /**
     * {@code @file:Import} is how a large script splits across files without paying to compile
     * several: the imports become one compilation unit and one cached jar. It is also the reason
     * jk's Kotlin floor is 2.4.10 — 2.4.0's K2 frontend cannot compile it at all.
     */
    @Test
    void file_import_pulls_in_a_sibling_script(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("helper.kts"), "fun greet(who: String) = \"hello, \" + who\n");
        Path script = dir.resolve("uses-import.kts");
        Files.writeString(script, """
                @file:Import("helper.kts")
                Files.writeString(outDir.resolve("greeting.txt"), greet(projectDir.fileName.toString()))
                """);
        Path project = Files.createDirectories(dir.resolve("world"));
        Path out = Files.createDirectories(dir.resolve("o"));

        BuildLogicKtsHost.evaluate(script, project, out);
        assertEquals(
                "hello, world", Files.readString(out.resolve("greeting.txt")).trim());
    }

    /** A compile error names the script and reaches the caller, rather than a bare non-zero exit. */
    @Test
    void a_compile_error_is_reported_against_the_script(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("broken.kts");
        Files.writeString(script, "this is not kotlin\n");
        Path project = Files.createDirectories(dir.resolve("p"));
        Path out = Files.createDirectories(dir.resolve("o"));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> BuildLogicKtsHost.evaluate(script, project, out));
        assertThat(ex.getMessage()).contains("broken.kts");
    }

    /**
     * The cost of sharing a JVM: a script calling {@code System.exit} takes the host with it, where
     * a forked-per-script host would have lost only that script. What must not happen is the engine
     * dying too, or every later script inheriting the corpse — so the failure is reported against
     * the script that caused it and the next script gets a fresh session.
     */
    @Test
    void a_script_that_exits_kills_only_its_own_run(@TempDir Path dir) throws Exception {
        Path suicide = dir.resolve("suicide.kts");
        Files.writeString(suicide, "kotlin.system.exitProcess(0)\n");
        Path survivor = dir.resolve("survivor.kts");
        Files.writeString(survivor, "Files.writeString(outDir.resolve(\"alive.txt\"), \"yes\")\n");
        Path project = Files.createDirectories(dir.resolve("p"));
        Path out = Files.createDirectories(dir.resolve("o"));

        assertThrows(IllegalStateException.class, () -> BuildLogicKtsHost.evaluate(suicide, project, out));

        // The engine is still here, and the next script gets a working session.
        BuildLogicKtsHost.evaluate(survivor, project, out);
        assertEquals("yes", Files.readString(out.resolve("alive.txt")).trim());
    }

    /**
     * The host is the engine's, not the build's. A build registers the workers it forks and kills
     * them when it ends; the host must not be among them, or every build pays a fresh JVM and a
     * cold compiler, and a build ending while another's script is mid-flight kills that script.
     */
    @Test
    void a_finished_build_does_not_take_the_host_with_it(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("first.kts");
        Files.writeString(script, "Files.writeString(outDir.resolve(\"a.txt\"), \"1\")\n");
        Path project = Files.createDirectories(dir.resolve("p"));
        Path out = Files.createDirectories(dir.resolve("o"));

        JobWorkers.open(9101L);
        try {
            BuildLogicKtsHost.evaluate(script, project, out);
        } finally {
            // What JobEnvelope's teardown does when the build ends: kill the request's workers.
            JobWorkers.shutdownForRequest(9101L, 0L);
            JobWorkers.close();
        }
        long pid = KtsSession.hostPid();
        assertThat(KtsSession.hostAlive())
                .as("the host outlives the build that started it")
                .isTrue();

        JobWorkers.open(9102L);
        try {
            BuildLogicKtsHost.evaluate(script, project, out);
        } finally {
            JobWorkers.shutdownForRequest(9102L, 0L);
            JobWorkers.close();
        }
        assertThat(KtsSession.hostPid())
                .as("the next build reuses the same host")
                .isEqualTo(pid);
    }

    /** Two builds overlap: the first finishing while the second's script runs does not fail the second. */
    @Test
    void the_first_build_finishing_does_not_kill_the_second_builds_running_script(@TempDir Path dir) throws Exception {
        Path quick = dir.resolve("quick.kts");
        Files.writeString(quick, "Files.writeString(outDir.resolve(\"quick.txt\"), \"1\")\n");
        Path slow = dir.resolve("slow.kts");
        Files.writeString(slow, """
                Files.writeString(outDir.resolve("started"), "1")
                Thread.sleep(2500)
                Files.writeString(outDir.resolve("done"), "1")
                """);
        Path project = Files.createDirectories(dir.resolve("p"));
        Path outA = Files.createDirectories(dir.resolve("a"));
        Path outB = Files.createDirectories(dir.resolve("b"));

        // Build A runs its script and is about to end.
        JobWorkers.open(9201L);
        BuildLogicKtsHost.evaluate(quick, project, outA);

        // Build B's script is in flight on another thread when A ends.
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread b = new Thread(() -> {
            JobWorkers.open(9202L);
            try {
                BuildLogicKtsHost.evaluate(slow, project, outB);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                JobWorkers.shutdownForRequest(9202L, 0L);
                JobWorkers.close();
            }
        });
        b.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(outB.resolve("started")) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(outB.resolve("started")).as("B's script is running").exists();

        JobWorkers.shutdownForRequest(9201L, 0L);
        JobWorkers.close();

        b.join(TimeUnit.SECONDS.toMillis(60));
        assertThat(failure.get())
                .as("B's run must not see 'the .kts host died'")
                .isNull();
        assertThat(outB.resolve("done")).exists();
    }

    /** With no script for the idle timeout the host is shut down; the next script starts a fresh one. */
    @Test
    void an_idle_host_is_shut_down_and_a_later_script_starts_a_fresh_one(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("idle.kts");
        Files.writeString(script, "Files.writeString(outDir.resolve(\"a.txt\"), \"1\")\n");
        Path project = Files.createDirectories(dir.resolve("p"));
        Path out = Files.createDirectories(dir.resolve("o"));

        KtsSession.idleTimeoutForTests(Duration.ofMillis(300));
        try {
            BuildLogicKtsHost.evaluate(script, project, out);
            long first = KtsSession.hostPid();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (KtsSession.hostAlive() && System.nanoTime() < deadline) Thread.sleep(50);
            assertThat(KtsSession.hostAlive())
                    .as("the reaper ends an idle host")
                    .isFalse();

            BuildLogicKtsHost.evaluate(script, project, out);
            assertThat(KtsSession.hostPid()).isNotEqualTo(first);
        } finally {
            KtsSession.idleTimeoutForTests(KtsSession.IDLE_TIMEOUT);
        }
    }

    /**
     * A build cancelled mid-script cancels the script, not just the build waiting on it. The host
     * interrupts the script, answers, and stays: the next build's script runs on the same host at
     * once instead of waiting out a sleep nobody asked for.
     */
    @Test
    void a_cancelled_build_cancels_its_running_script_and_the_next_script_starts_within_the_grace(@TempDir Path dir)
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
        // Compiled ahead, so the run after the cancel measures the host's readiness, not kotlinc.
        BuildLogicKtsHost.evaluate(quick, project, outB);
        long pid = KtsSession.hostPid();

        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread build = new Thread(() -> {
            try {
                KtsSession.run(slow, project, outA, cancelled::get);
            } catch (Throwable t) {
                outcome.set(t);
            }
        });
        build.start();
        awaitFile(outA.resolve("started"));

        long cancelledAt = System.nanoTime();
        cancelled.set(true);
        build.join(TimeUnit.SECONDS.toMillis(30));
        Files.deleteIfExists(outB.resolve("quick.txt"));
        BuildLogicKtsHost.evaluate(quick, project, outB);
        Duration untilNextScript = Duration.ofNanos(System.nanoTime() - cancelledAt);

        assertThat(outcome.get())
                .as("the run reports as cancelled, not as a script failure")
                .isInstanceOf(KtsSession.Cancelled.class)
                .hasMessageContaining("slow.kts")
                .hasMessageContaining("cancelled");
        assertThat(outA.resolve("done"))
                .as("the script stopped where the interrupt found it")
                .doesNotExist();
        assertThat(outB.resolve("quick.txt")).exists();
        assertThat(untilNextScript)
                .as("the next script ran within the cancel grace")
                .isLessThan(KtsSession.CANCEL_GRACE);
        assertThat(KtsSession.hostPid())
                .as("a script that honours the interrupt leaves the host standing")
                .isEqualTo(pid);
    }

    /**
     * A script that swallows the interrupt would hold the host, and its lock, for every later build.
     * The engine gives it the cancel grace and then kills the host; the next script starts a fresh
     * one rather than waiting on a script nobody can stop.
     */
    @Test
    void a_script_that_ignores_the_interrupt_is_killed_with_its_host_and_the_next_script_starts_fresh(@TempDir Path dir)
            throws Exception {
        Path stubborn = dir.resolve("stubborn.kts");
        Files.writeString(stubborn, """
                Files.writeString(outDir.resolve("started"), "1")
                while (true) {
                    try {
                        Thread.sleep(100)
                    } catch (e: InterruptedException) {
                        // ignored: the loop is the point
                    }
                }
                """);
        Path quick = dir.resolve("quick.kts");
        Files.writeString(quick, "Files.writeString(outDir.resolve(\"quick.txt\"), \"1\")\n");
        Path project = Files.createDirectories(dir.resolve("p"));
        Path outA = Files.createDirectories(dir.resolve("a"));
        Path outB = Files.createDirectories(dir.resolve("b"));
        // Starts the host whose pid the assertion below compares against; the seams take the
        // session lock, which the run in flight holds, so the pid is read before it starts.
        BuildLogicKtsHost.evaluate(quick, project, outB);
        long pid = KtsSession.hostPid();

        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread build = new Thread(() -> {
            try {
                KtsSession.run(stubborn, project, outA, cancelled::get);
            } catch (Throwable t) {
                outcome.set(t);
            }
        });
        build.start();
        awaitFile(outA.resolve("started"));

        long cancelledAt = System.nanoTime();
        cancelled.set(true);
        build.join(TimeUnit.SECONDS.toMillis(30));
        Duration untilCancelled = Duration.ofNanos(System.nanoTime() - cancelledAt);

        assertThat(outcome.get()).isInstanceOf(KtsSession.Cancelled.class);
        assertThat(untilCancelled)
                .as("the build is released once the grace runs out")
                .isLessThan(KtsSession.CANCEL_GRACE.plusSeconds(10));
        assertThat(KtsSession.hostAlive())
                .as("the host that would not stop its script is gone")
                .isFalse();

        Files.deleteIfExists(outB.resolve("quick.txt"));
        BuildLogicKtsHost.evaluate(quick, project, outB);
        assertThat(outB.resolve("quick.txt")).exists();
        assertThat(KtsSession.hostPid()).as("the next script got a fresh host").isNotEqualTo(pid);
    }

    private static void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(file).as("the script is running").exists();
    }

    private static long jarCount(Path cache) throws Exception {
        try (var s = Files.list(cache)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jar")).count();
        }
    }
}
