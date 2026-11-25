// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.ToolJars;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Child JVM for wedged-engine tests: alive, answering nothing, until killed (or arg-ms elapse). */
public final class SleepMain {

    private SleepMain() {}

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(args.length > 0 ? Long.parseLong(args[0]) : 60_000L);
    }

    private static final String ENGINE_MAIN = "cc.jumpkick.engine.EngineMain";

    /** Spawn a sleeper with the engine entry point for tests of engine pid files. */
    public static Process spawnEngine(long ms, String... jvmArgs) throws Exception {
        return spawnMain(ToolJars.codeSource(SleepMain.class).toString(), ENGINE_MAIN, ms, List.of(jvmArgs), List.of());
    }

    /** Spawn a sleeper with the layout of an installed engine. */
    public static Process spawnInstalledEngine(Path jar, long ms) throws Exception {
        Files.createDirectories(jar.getParent());
        Path fixture = ToolJars.codeSource(SleepMain.class);
        if (Files.isDirectory(fixture)) {
            try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
                for (String main : List.of(ENGINE_MAIN, SleepMain.class.getName())) {
                    String entry = main.replace('.', '/') + ".class";
                    output.putNextEntry(new JarEntry(entry));
                    Files.copy(fixture.resolve(entry), output);
                    output.closeEntry();
                }
            }
        } else {
            Files.copy(fixture, jar);
        }
        return spawnMain(jar.toString(), ENGINE_MAIN, ms, List.of(), List.of());
    }

    public static Process spawn(long ms, String... trailingArgs) throws Exception {
        return spawnMain(
                ToolJars.codeSource(SleepMain.class).toString(),
                SleepMain.class.getName(),
                ms,
                List.of(),
                List.of(trailingArgs));
    }

    private static Process spawnMain(
            String classpath, String main, long ms, List<String> jvmArgs, List<String> trailingArgs) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", Os.isWindows() ? "java.exe" : "java")
                .toString();
        List<String> cmd = new ArrayList<>(List.of(java));
        cmd.addAll(jvmArgs);
        cmd.addAll(List.of("-cp", classpath, main, Long.toString(ms)));
        cmd.addAll(trailingArgs);
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
