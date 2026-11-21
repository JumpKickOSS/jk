// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.Os;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Child JVM for wedged-engine tests: alive, answering nothing, until killed (or arg-ms elapse). */
public final class SleepMain {

    private SleepMain() {}

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(args.length > 0 ? Long.parseLong(args[0]) : 60_000L);
    }

    /**
     * Spawn this main in a fresh JVM on the current test classpath. {@code trailingArgs} land on the
     * command line and are ignored by {@link #main} — how a test builds a process that a
     * command-line predicate recognizes (an engine marker, say) without running an engine.
     */
    /** The marker {@link EngineFleet#isEnginePid} reads on a command line as a resident engine. */
    public static final String ENGINE_MAIN = "cc.jumpkick.engine.EngineMain";

    /**
     * A sleeper that reads as an engine: a stop or a displacement acts only on a pid-file process
     * that looks like one, so a test's holder standing in for an engine must carry the marker.
     */
    public static Process spawnEngine(long ms) throws Exception {
        return spawn(ms, ENGINE_MAIN);
    }

    public static Process spawn(long ms, String... trailingArgs) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", Os.isWindows() ? "java.exe" : "java")
                .toString();
        List<String> cmd = new ArrayList<>(List.of(
                java, "-cp", System.getProperty("java.class.path"), SleepMain.class.getName(), Long.toString(ms)));
        cmd.addAll(List.of(trailingArgs));
        return new ProcessBuilder(cmd).start();
    }

    /**
     * Wait until {@code jvm} has finished starting up: two consecutive readings {@code 100 ms} apart
     * show its CPU time standing still, so a later pair of readings sees no start-up step.
     */
    public static void awaitIdle(Process jvm) throws InterruptedException {
        Duration last = cpu(jvm);
        int quiet = 0;
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (quiet < 2 && System.nanoTime() < deadline) {
            Thread.sleep(100);
            Duration now = cpu(jvm);
            quiet = now.minus(last).compareTo(Duration.ofMillis(5)) < 0 ? quiet + 1 : 0;
            last = now;
        }
        if (quiet < 2) throw new AssertionError("pid " + jvm.pid() + " never went quiet within 60s");
    }

    private static Duration cpu(Process jvm) {
        return jvm.info().totalCpuDuration().orElse(Duration.ZERO);
    }
}
