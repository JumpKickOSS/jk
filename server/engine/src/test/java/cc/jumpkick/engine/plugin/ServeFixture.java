// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.plugin.process.CommandChannel;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A served worker for {@link WarmWorkersTest}: the {@code --serve} protocol over the command channel,
 * with no plugin behind it. A spec's first line says what the run does: {@code ok}, {@code retire},
 * {@code oom} (dies the way {@code ExitOnOutOfMemoryError} does), {@code hang}, {@code stdin} (reads
 * {@code System.in} to its end), {@code child} (runs a child JVM that inherits this one's stdin
 * and reads it to its end), {@code spawn} (starts a sleeping child JVM, then hangs) or {@code late}
 * (prints a line after it has answered {@code exited}).
 */
public final class ServeFixture {

    static final String PREFIX = "##FIX:";

    private ServeFixture() {}

    public static void main(String[] args) throws Exception {
        BufferedReader in = CommandChannel.connect(List.of(args));
        while (true) {
            emit("{\"t\":\"ready\"}");
            String line = in.readLine();
            if (line == null || line.equals("DONE")) return;
            String what =
                    Files.readAllLines(Path.of(line.substring(4))).getFirst().strip();
            emit("{\"t\":\"pid\",\"pid\":" + ProcessHandle.current().pid() + "}");
            System.out.println("chatter for " + what);
            switch (what) {
                case "oom" -> {
                    System.out.println("java.lang.OutOfMemoryError: Java heap space");
                    System.out.flush();
                    System.exit(3);
                }
                case "hang" -> Thread.sleep(Long.MAX_VALUE);
                case "spawn" -> {
                    Process child = new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    Sleeper.class.getName())
                            .start();
                    System.out.println("child pid " + child.pid());
                    System.out.flush();
                    Thread.sleep(Long.MAX_VALUE);
                }
                case "stdin" -> System.out.println("stdin gave " + drainStdin() + " bytes");
                case "child" -> {
                    Process child = new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    StdinChild.class.getName())
                            .inheritIO()
                            .start();
                    System.out.println("child exited " + child.waitFor());
                }
                default -> {}
            }
            emit("{\"t\":\"exited\",\"code\":" + what.length() + ",\"retire\":" + what.equals("retire") + "}");
            if (what.equals("late")) System.out.println("late words of " + what);
        }
    }

    private static long drainStdin() throws IOException {
        long n = 0;
        while (System.in.read() >= 0) n++;
        return n;
    }

    /** A child that sleeps until it is killed. */
    public static final class Sleeper {
        private Sleeper() {}

        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    /** A child that reads the stdin it inherited to its end and says how much it got. */
    public static final class StdinChild {
        private StdinChild() {}

        public static void main(String[] args) throws IOException {
            System.out.println("child stdin gave " + drainStdin() + " bytes");
        }
    }

    private static void emit(String json) {
        System.out.println(PREFIX + json);
        System.out.flush();
    }
}
