// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.Processes;
import cc.jumpkick.task.FileHashMemo;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A suite JVM's reads are recorded, and those no key covers are what it is found to depend on. */
class SuiteReadsTest {

    @Test
    void a_recorded_jvm_reports_the_files_it_read(@TempDir Path tmp) throws Exception {
        Path data = Files.writeString(tmp.resolve("data.txt"), "payload");
        Path covered = Files.createDirectories(tmp.resolve("covered"));
        Files.writeString(covered.resolve("in.txt"), "keyed");
        Path probe = Files.writeString(tmp.resolve("Probe.java"), """
                import java.nio.file.*;
                class Probe {
                    public static void main(String[] a) throws Exception {
                        Files.readAllBytes(Path.of("data.txt"));
                        Files.readAllBytes(Path.of("covered/in.txt"));
                    }
                }
                """);
        Path recordings = tmp.resolve("rec");
        String flag = SuiteReads.prepare(recordings);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Processes.Captured run = Processes.capture(
                new ProcessBuilder(java.toString(), flag, probe.toString())
                        .directory(tmp.toFile())
                        .redirectErrorStream(true),
                Duration.ofMinutes(2));
        assertThat(run.exit()).as(run.output()).isZero();

        SuiteReads.Observed seen = SuiteReads.collect(recordings, tmp);
        assertThat(seen.files()).contains(data.toAbsolutePath().normalize());
        assertThat(seen.network()).isFalse();
        Set<Path> undeclared =
                SuiteReads.undeclared(seen, List.of(covered, probe, Path.of(System.getProperty("java.home"))));
        assertThat(undeclared).contains(data.toAbsolutePath().normalize());
        assertThat(undeclared)
                .doesNotContain(covered.resolve("in.txt").toAbsolutePath().normalize());
    }

    @Test
    void a_recorded_jvm_names_a_program_whose_path_holds_a_space_whole(@TempDir Path tmp) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path bin = Files.createDirectories(tmp.resolve("Program Files").resolve("bin"));
        // A program that exits 0, started by its path: a batch file on Windows, a script elsewhere.
        Path tool = Os.isWindows()
                ? Files.writeString(bin.resolve("tool.cmd"), "@exit /b 0\r\n")
                : Files.writeString(bin.resolve("tool"), "#!/bin/sh\nexit 0\n");
        if (!Os.isWindows()) Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path probe = Files.writeString(tmp.resolve("Probe.java"), """
                class Probe {
                    public static void main(String[] a) throws Exception {
                        Process p = new ProcessBuilder(a[0], "--flag", a[1])
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start();
                        System.exit(p.waitFor());
                    }
                }
                """);
        Path recordings = tmp.resolve("rec");
        String flag = SuiteReads.prepare(recordings);
        Processes.Captured run = Processes.capture(
                new ProcessBuilder(
                                java.toString(),
                                flag,
                                probe.toString(),
                                tool.toString(),
                                tmp.resolve("an arg").toString())
                        .directory(tmp.toFile())
                        .redirectErrorStream(true),
                Duration.ofMinutes(2));
        assertThat(run.exit()).as(run.output()).isZero();

        assertThat(SuiteReads.collect(recordings, tmp).commands())
                .as("JFR joins the words with spaces; the program is the words that name its file")
                .contains(tool.toString());
    }

    @Test
    void a_program_whose_path_holds_a_space_is_kept_and_keyed_whole(@TempDir Path tmp) throws Exception {
        Path tool = Files.writeString(
                Files.createDirectories(tmp.resolve("Program Files").resolve("bin"))
                        .resolve("tool"),
                "#!/bin/sh\n");
        assertThat(SuiteReads.executable(tool + " --flag " + tmp.resolve("an arg")))
                .isEqualTo(tool.toString());
        assertThat(SuiteReads.executable("git status --short")).isEqualTo("git");

        Path module = Files.createDirectories(tmp.resolve("module"));
        SuiteReads.Discovered d = SuiteReads.Discovered.NONE.with(Set.of(), Set.of(tool.toString()), Set.of());
        SuiteReads.save(module, TestSelection.DEFAULT, d);
        assertThat(SuiteReads.load(module, TestSelection.DEFAULT).commands()).containsExactly(tool.toString());
        assertThat(SuiteReads.tokens(module, TestSelection.DEFAULT, tmp))
                .containsExactly("proc:" + tool + "=" + FileHashMemo.contentHash(tool));
    }

    @Test
    void a_discovered_set_covers_a_run_that_read_nothing_new() {
        Path a = Path.of("/w/a.txt");
        Path b = Path.of("/w/b.txt");
        SuiteReads.Discovered d = SuiteReads.Discovered.NONE.with(Set.of(a), Set.of(), Set.of());
        assertThat(d.covers(Set.of(a), Set.of(), Set.of())).isTrue();
        assertThat(d.covers(Set.of(a, b), Set.of(), Set.of())).isFalse();
        assertThat(d.covers(Set.of(), Set.of("git status"), Set.of())).isFalse();
        assertThat(d.covers(Set.of(), Set.of(), Set.of("repo.example.com"))).isFalse();
    }

    @Test
    void a_recorded_jvm_names_the_host_past_this_one_it_talked_to(@TempDir Path tmp) throws Exception {
        String address = nonLoopbackAddress();
        Assumptions.assumeTrue(address != null, "this host has no address but loopback");
        Path probe = Files.writeString(tmp.resolve("Probe.java"), """
                import java.net.*;
                class Probe {
                    public static void main(String[] a) throws Exception {
                        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName(a[0]));
                                Socket client = new Socket(InetAddress.getByName(a[0]), server.getLocalPort());
                                Socket accepted = server.accept()) {
                            client.getOutputStream().write(1);
                            accepted.getInputStream().read();
                            accepted.getOutputStream().write(2);
                            client.getInputStream().read();
                        }
                    }
                }
                """);
        Path recordings = tmp.resolve("rec");
        String flag = SuiteReads.prepare(recordings);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Processes.Captured run = Processes.capture(
                new ProcessBuilder(java.toString(), flag, probe.toString(), address)
                        .directory(tmp.toFile())
                        .redirectErrorStream(true),
                Duration.ofMinutes(2));
        assertThat(run.exit()).as(run.output()).isZero();

        SuiteReads.Observed seen = SuiteReads.collect(recordings, tmp);
        assertThat(seen.network()).isTrue();
        assertThat(seen.hosts()).contains(address);
    }

    /** An address of an interface that is up and not loopback, or null when there is none. */
    private static @Nullable String nonLoopbackAddress() throws Exception {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) continue;
            for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress())
                    return a.getHostAddress();
            }
        }
        return null;
    }

    /**
     * A directory made outside the workspace half a second before the run is not the run's own. The
     * JDK reports a macOS birth time cut to the second; the directory's modification time, which
     * keeps the file system's precision, says it was born before the run began.
     */
    @Test
    void a_directory_made_just_before_the_run_is_not_taken_for_the_runs_own() {
        long started = 1_794_165_723_818L;
        long made = started - 492;
        FileTime cut = FileTime.from(made / 1_000, TimeUnit.SECONDS);

        assertThat(SuiteReads.bornDuringRun(attrs(FileTime.fromMillis(made), FileTime.fromMillis(made)), started))
                .as("a birth time the file system stamped finely")
                .isFalse();
        assertThat(SuiteReads.bornDuringRun(attrs(cut, FileTime.fromMillis(made + 3)), started))
                .as("a birth time cut to the second, bounded by the modification")
                .isFalse();
        assertThat(SuiteReads.bornDuringRun(attrs(cut, cut), started))
                .as("a file system that stamps whole seconds leaves the birth anywhere in that second")
                .isTrue();
    }

    /** A directory the run made, whose birth time is cut to the second, is still the run's own. */
    @Test
    void a_directory_the_run_made_is_its_own_whatever_its_birth_time_was_cut_to() {
        long started = 1_794_165_723_818L;
        FileTime cut = FileTime.from(started / 1_000, TimeUnit.SECONDS);

        assertThat(SuiteReads.bornDuringRun(attrs(cut, FileTime.fromMillis(started + 40)), started))
                .isTrue();
        assertThat(SuiteReads.bornDuringRun(attrs(cut, FileTime.fromMillis(started - 60_000)), started))
                .as("a modification older than the birth, a copy keeping its source's time, bounds nothing")
                .isTrue();
    }

    private static BasicFileAttributes attrs(FileTime born, FileTime modified) {
        return new BasicFileAttributes() {
            @Override
            public FileTime lastModifiedTime() {
                return modified;
            }

            @Override
            public FileTime lastAccessTime() {
                return modified;
            }

            @Override
            public FileTime creationTime() {
                return born;
            }

            @Override
            public boolean isRegularFile() {
                return false;
            }

            @Override
            public boolean isDirectory() {
                return true;
            }

            @Override
            public boolean isSymbolicLink() {
                return false;
            }

            @Override
            public boolean isOther() {
                return false;
            }

            @Override
            public long size() {
                return 0;
            }

            @Override
            public Object fileKey() {
                return born;
            }
        };
    }

    @Test
    void only_a_comma_quotes_a_recording_path() {
        // A quoted space would split the argument on Windows, where the launcher wraps it in quotes again.
        Path spaced = Path.of("/a b/reads.jfc");
        Path comma = Path.of("/a,b/reads.jfc");
        assertThat(SuiteReads.jfrPath(spaced)).isEqualTo(spaced.toString());
        assertThat(SuiteReads.jfrPath(comma)).isEqualTo("\"" + comma + "\"");
    }
}
