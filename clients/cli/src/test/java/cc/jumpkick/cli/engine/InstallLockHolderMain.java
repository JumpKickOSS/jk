// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.Os;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Child JVM standing in for another session: takes the install lock at {@code args[0]} {@code
 * shared} or {@code exclusive} as {@code args[2]}, prints {@code HELD}, holds it until a line (or
 * end of input) arrives on stdin, then releases it and prints {@code RELEASED}.
 */
public final class InstallLockHolderMain {

    private InstallLockHolderMain() {}

    public static void main(String[] args) throws IOException {
        Path lock = Path.of(args[0]);
        boolean exclusive = "exclusive".equals(args[1]);
        InstallLock.notices(line -> System.out.println("WAIT " + line));
        try (InstallLock.Hold hold =
                exclusive ? InstallLock.exclusive(lock, args[2]) : InstallLock.shared(lock, args[2])) {
            System.out.println("HELD");
            System.out.flush();
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        }
        System.out.println("RELEASED");
        System.out.flush();
    }

    /** A running holder: its process, its stdout lines and the pipe that releases it. */
    public record Holder(Process process, BufferedReader out) implements AutoCloseable {

        /** The next stdout line, failing after {@code timeout}. */
        public String nextLine(Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!out.ready()) {
                if (System.nanoTime() > deadline) throw new AssertionError("holder printed nothing in " + timeout);
                if (!process.isAlive() && !out.ready())
                    throw new AssertionError("holder exited " + process.exitValue());
                Thread.sleep(20);
            }
            return out.readLine();
        }

        /** True when the holder prints a line within {@code window}. */
        public boolean printsWithin(Duration window) throws Exception {
            long deadline = System.nanoTime() + window.toNanos();
            while (System.nanoTime() < deadline) {
                if (out.ready()) return true;
                Thread.sleep(20);
            }
            return out.ready();
        }

        /** Wait for {@code HELD}, skipping wait notices. */
        public void awaitHeld() throws Exception {
            String line;
            do {
                line = nextLine(Duration.ofSeconds(60));
            } while (line != null && line.startsWith("WAIT "));
            if (!"HELD".equals(line)) throw new AssertionError("expected HELD, got " + line);
        }

        /** Ask the holder to release, and wait for it to say so. */
        public void release() throws Exception {
            Writer in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            in.write("go\n");
            in.flush();
            String line;
            do {
                line = nextLine(Duration.ofSeconds(60));
            } while (line != null && !"RELEASED".equals(line));
        }

        @Override
        public void close() {
            process.destroyForcibly();
        }
    }

    /** Start a holder JVM on the current test classpath; it may still be waiting for the lock. */
    public static Holder start(Path lock, String mode, String purpose) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", Os.isWindows() ? "java.exe" : "java")
                .toString();
        Process p = new ProcessBuilder(List.of(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        InstallLockHolderMain.class.getName(),
                        lock.toString(),
                        mode,
                        purpose))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        return new Holder(p, new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)));
    }
}
