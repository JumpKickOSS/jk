// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Processes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
    void a_discovered_set_covers_a_run_that_read_nothing_new() {
        Path a = Path.of("/w/a.txt");
        Path b = Path.of("/w/b.txt");
        SuiteReads.Discovered d = SuiteReads.Discovered.NONE.with(Set.of(a), Set.of(), false);
        assertThat(d.covers(Set.of(a), Set.of(), false)).isTrue();
        assertThat(d.covers(Set.of(a, b), Set.of(), false)).isFalse();
        assertThat(d.covers(Set.of(), Set.of("git status"), false)).isFalse();
        assertThat(d.covers(Set.of(), Set.of(), true)).isFalse();
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
