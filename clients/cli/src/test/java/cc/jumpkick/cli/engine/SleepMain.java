// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.Os;
import java.nio.file.Path;

/** Child JVM for wedged-engine tests: alive, answering nothing, until killed (or arg-ms elapse). */
public final class SleepMain {

    private SleepMain() {}

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(args.length > 0 ? Long.parseLong(args[0]) : 60_000L);
    }

    /** Spawn this main in a fresh JVM on the current test classpath. */
    public static Process spawn(long ms) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", Os.isWindows() ? "java.exe" : "java")
                .toString();
        return new ProcessBuilder(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        SleepMain.class.getName(),
                        Long.toString(ms))
                .start();
    }
}
