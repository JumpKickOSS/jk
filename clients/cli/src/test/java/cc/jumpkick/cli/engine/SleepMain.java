// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.Os;
import java.nio.file.Path;
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
    public static Process spawn(long ms, String... trailingArgs) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", Os.isWindows() ? "java.exe" : "java")
                .toString();
        List<String> cmd = new ArrayList<>(List.of(
                java, "-cp", System.getProperty("java.class.path"), SleepMain.class.getName(), Long.toString(ms)));
        cmd.addAll(List.of(trailingArgs));
        return new ProcessBuilder(cmd).start();
    }
}
