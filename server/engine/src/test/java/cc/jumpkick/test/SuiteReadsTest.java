// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
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
        Process p = new ProcessBuilder(java.toString(), flag, probe.toString())
                .directory(tmp.toFile())
                .redirectErrorStream(true)
                .start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();

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

    @Test
    void only_a_comma_quotes_a_recording_path() {
        // A quoted space would split the argument on Windows, where the launcher wraps it in quotes again.
        assertThat(SuiteReads.jfrPath(Path.of("/a b/reads.jfc"))).isEqualTo("/a b/reads.jfc");
        assertThat(SuiteReads.jfrPath(Path.of("/a,b/reads.jfc"))).isEqualTo("\"/a,b/reads.jfc\"");
    }
}
