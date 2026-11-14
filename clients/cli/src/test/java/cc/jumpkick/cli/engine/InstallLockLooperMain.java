// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Child JVM standing in for a session that runs builds back to back: takes the install lock at
 * {@code args[0]} shared for {@code args[1]} ms, releases it, and takes it again at once, until
 * {@code args[2]} ms have passed. Prints {@code LOOPING} after its first hold and {@code DONE n}
 * with the number of holds when it stops.
 */
public final class InstallLockLooperMain {

    private InstallLockLooperMain() {}

    public static void main(String[] args) throws Exception {
        Path lock = Path.of(args[0]);
        long holdMs = Long.parseLong(args[1]);
        long until = System.nanoTime() + Long.parseLong(args[2]) * 1_000_000L;
        InstallLock.notices(line -> {});
        int holds = 0;
        while (System.nanoTime() < until) {
            try (InstallLock.Hold hold = InstallLock.shared(lock, "jk test (looper)")) {
                if (holds++ == 0) {
                    System.out.println("LOOPING");
                    System.out.flush();
                }
                Thread.sleep(holdMs);
            }
        }
        System.out.println("DONE " + holds);
        System.out.flush();
    }

    /** Start a looper JVM on the current test classpath. */
    public static Process start(Path lock, long holdMs, long runMs) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", Os.isWindows() ? "java.exe" : "java")
                .toString();
        return new ProcessBuilder(List.of(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        InstallLockLooperMain.class.getName(),
                        lock.toString(),
                        Long.toString(holdMs),
                        Long.toString(runMs)))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
