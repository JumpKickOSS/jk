// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jdk.SupportedJdk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The streaming reader reads what every test JDK jk supports writes. For each JDK major from
 * {@link SupportedJdk#MIN_MAJOR} installed beside the test JDK or under {@link JkDirs#jdks()} (one build per
 * major), a JVM
 * of that JDK runs with jk's own suite recording flag ({@link SuiteReads#prepare}) and reads, writes,
 * starts a process and talks over a socket; the reader must give, event for event and field for
 * field, what the engine JDK's own parser gives. A JDK the fast reader cannot follow fails here, in
 * jk's gate, rather than turning into suites that never replay on a user's machine (the fallback
 * reads it, but at the cost of a child JVM per suite). jk refuses test JDKs below 17 anywhere.
 */
class RecordingCompatTest {

    /** The events and fields compared: everything the suite readers take, and the fields around them. */
    private static final Map<String, List<String>> FIELDS = Map.of(
            "jdk.FileRead", List.of("path", "bytesRead", "endOfFile"),
            "jdk.FileWrite", List.of("path", "bytesWritten"),
            "jdk.ProcessStart", List.of("pid", "directory", "command"),
            "jdk.SocketRead", List.of("host", "address", "port", "bytesRead", "endOfStream", "eventThread"),
            "jdk.SocketWrite", List.of("host", "address", "port", "bytesWritten", "eventThread"));

    /** The program each JDK runs, from source so one file serves every major. */
    private static final String PROGRAM = """
            import java.io.*;
            import java.net.*;
            import java.nio.file.*;

            public class Spread {
                public static void main(String[] args) throws Exception {
                    Path dir = Path.of(args[0]);
                    Path shortName = Files.writeString(dir.resolve("a.txt"), "x");
                    Path longName = Files.writeString(
                            dir.resolve("a-file-name-long-enough-for-the-string-pool-ünï.txt"), "y".repeat(5000));
                    for (int i = 0; i < 50; i++) {
                        Files.readString(shortName);
                        Files.readAllBytes(longName);
                        try (FileInputStream in = new FileInputStream(longName.toFile())) {
                            in.read(new byte[100]);
                        }
                        Files.writeString(dir.resolve("out-" + (i % 3) + ".txt"), "z" + i);
                    }
                    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                    new ProcessBuilder(java, "-version").redirectErrorStream(true)
                            .redirectOutput(dir.resolve("version.txt").toFile()).start().waitFor();
                    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                        Thread t = new Thread(() -> {
                            try (Socket s = server.accept()) {
                                s.getOutputStream().write(new byte[64]);
                                s.getInputStream().read(new byte[64]);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
                        t.start();
                        try (Socket c = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                            c.getInputStream().read(new byte[64]);
                            c.getOutputStream().write(new byte[32]);
                        }
                        t.join();
                    }
                }
            }
            """;

    @Test
    @Timeout(600)
    void every_installed_test_jdk_writes_what_the_fast_reader_reads(@TempDir Path tmp) throws Exception {
        Map<Integer, Path> jdks = installedByMajor();
        assumeTrue(!jdks.isEmpty(), "no JDK installed under " + JkDirs.jdks());
        Path program = Files.writeString(tmp.resolve("Spread.java"), PROGRAM);
        List<String> checked = new ArrayList<>();
        for (Map.Entry<Integer, Path> jdk : jdks.entrySet()) {
            Path dir = Files.createDirectories(tmp.resolve("jdk-" + jdk.getKey()));
            Path recordings = Files.createDirectories(dir.resolve("rec"));
            String flag = SuiteReads.prepare(recordings);
            Path log = dir.resolve("run.log");
            Process p = new ProcessBuilder(
                            JdkFingerprint.java(jdk.getValue()).toString(), flag, program.toString(), dir.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            assertThat(p.waitFor(180, TimeUnit.SECONDS))
                    .as("JDK " + jdk.getKey())
                    .isTrue();
            assertThat(p.exitValue())
                    .as("JDK %s: %s", jdk.getKey(), Files.readString(log))
                    .isZero();
            List<Path> jfrs;
            try (Stream<Path> s = Files.list(recordings)) {
                jfrs = s.filter(f -> f.toString().endsWith(".jfr")).toList();
            }
            assertThat(jfrs).as("JDK " + jdk.getKey() + " wrote its recording").hasSize(1);
            Path jfr = jfrs.getFirst();

            List<String> baseline = baseline(jfr);
            List<String> fast = new ArrayList<>();
            RecordingReader.read(jfr, FIELDS, (name, at, end, v) -> fast.add(line(name, v)));
            assertThat(fast)
                    .as("JDK " + jdk.getKey() + " (" + jdk.getValue() + ")")
                    .isEqualTo(baseline);
            assertThat(baseline)
                    .as("JDK " + jdk.getKey() + " recorded every kind of event")
                    .anyMatch(l -> l.startsWith("jdk.FileRead ") && l.contains("ünï"))
                    .anyMatch(l -> l.startsWith("jdk.FileWrite "))
                    .anyMatch(l -> l.startsWith("jdk.ProcessStart "))
                    .anyMatch(l -> l.startsWith("jdk.SocketRead "))
                    .anyMatch(l -> l.startsWith("jdk.SocketWrite "));
            checked.add(jdk.getKey() + " " + jdk.getValue().getFileName());
        }
        Files.writeString(tmp.resolve("checked.txt"), String.join("\n", checked));
        assertThat(checked).as("checked " + checked).isNotEmpty();
    }

    /** What the engine JDK's own parser takes from {@code jfr}, rendered as the fast reader renders. */
    private static List<String> baseline(Path jfr) throws IOException {
        List<String> out = new ArrayList<>();
        try (RecordingFile in = new RecordingFile(jfr)) {
            while (in.hasMoreEvents()) {
                RecordedEvent e = in.readEvent();
                List<String> fields = FIELDS.get(e.getEventType().getName());
                if (fields == null) continue;
                @Nullable String[] values = new String[fields.size()];
                for (int i = 0; i < values.length; i++) values[i] = RecordingDump.render(e, fields.get(i));
                out.add(line(e.getEventType().getName(), values));
            }
        }
        return out;
    }

    private static String line(String name, @Nullable String[] values) {
        StringBuilder sb = new StringBuilder(name);
        for (String v : values) sb.append(' ').append(Objects.toString(v));
        return sb.toString();
    }

    /**
     * One installed JDK per supported major: the first in name order that declares that major, from
     * the JDK root jk installs into and the directory holding this test JVM's own JDK (a test home
     * has a JDK root of its own, usually empty).
     */
    private static Map<Integer, Path> installedByMajor() throws IOException {
        Map<Integer, Path> out = new TreeMap<>();
        List<Path> homes = new ArrayList<>();
        Path own = Path.of(System.getProperty("java.home")).toAbsolutePath().getParent();
        for (Path root : own == null ? List.of(JkDirs.jdks()) : List.of(JkDirs.jdks(), own)) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.list(root)) {
                homes.addAll(s.sorted().toList());
            }
        }
        for (Path home : homes) {
            Path release = home.resolve("release");
            if (!Files.isRegularFile(release) || !Files.isRegularFile(JdkFingerprint.java(home))) continue;
            int major = major(Files.readString(release));
            if (major >= SupportedJdk.MIN_MAJOR) out.putIfAbsent(major, home);
        }
        return out;
    }

    private static int major(String release) {
        for (String line : release.split("\n")) {
            if (!line.startsWith("JAVA_VERSION=")) continue;
            String v =
                    line.substring("JAVA_VERSION=".length()).replace("\"", "").strip();
            int dot = v.indexOf('.');
            try {
                return Integer.parseInt(dot < 0 ? v : v.substring(0, dot));
            } catch (NumberFormatException odd) {
                return -1;
            }
        }
        return -1;
    }
}
