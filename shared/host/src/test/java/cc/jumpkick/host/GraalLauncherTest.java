// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The layout, both directions. The load-bearing case is {@code lib/svm/bin}: the forward search knew
 * about it and the inverse — a hand-written parent-of-parent in {@code GraalResolver} — did not, so
 * a Windows GraalVM whose launcher is {@code lib/svm/bin/native-image.exe} was reported as having
 * its home at {@code <home>/lib/svm}. Both directions are asserted here because a mapping tested in
 * one direction only is how that gap survived.
 */
class GraalLauncherTest {

    @Test
    void home_of_a_bin_launcher_is_the_home(@TempDir Path dir) {
        Path home = dir.resolve("graalvm-25");
        assertThat(GraalLauncher.homeOf(home.resolve("bin").resolve(GraalLauncher.NAME)))
                .contains(home);
        assertThat(GraalLauncher.homeOf(home.resolve("bin").resolve(GraalLauncher.CMD)))
                .contains(home);
    }

    @Test
    void home_of_an_svm_launcher_is_the_home_not_the_lib_dir(@TempDir Path dir) {
        Path home = dir.resolve("graalvm-25");
        Path svm = home.resolve("lib").resolve("svm").resolve("bin").resolve(GraalLauncher.EXE);

        assertThat(GraalLauncher.homeOf(svm)).contains(home);
        // The defect this replaced: two levels up from the launcher is not a GraalVM home.
        assertThat(GraalLauncher.homeOf(svm)).get().isNotEqualTo(svm.getParent().getParent());
        assertThat(GraalLauncher.homeOf(svm)).get().isNotEqualTo(home.resolve("lib"));
    }

    @Test
    void a_launcher_somewhere_else_has_no_home_we_can_name(@TempDir Path dir) {
        assertThat(GraalLauncher.homeOf(dir.resolve("tools").resolve(GraalLauncher.NAME)))
                .isEmpty();
        assertThat(GraalLauncher.homeOf(Path.of(GraalLauncher.NAME))).isEmpty();
        assertThat(GraalLauncher.homeOf(null)).isEmpty();
    }

    @Test
    void finds_an_svm_launcher_and_round_trips_to_its_home(@TempDir Path home) throws Exception {
        Path bin = home.resolve("lib").resolve("svm").resolve("bin");
        Files.createDirectories(bin);
        Path launcher = Files.writeString(bin.resolve(GraalLauncher.EXE), "MZ\n");

        assertThat(GraalLauncher.in(home)).contains(launcher);
        assertThat(GraalLauncher.in(home).flatMap(GraalLauncher::homeOf)).contains(home);
    }

    @Test
    void finds_a_bin_launcher(@TempDir Path home) throws Exception {
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path launcher = Files.writeString(bin.resolve(GraalLauncher.NAME), "#!/bin/sh\n");

        assertThat(GraalLauncher.in(home)).contains(launcher);
        assertThat(GraalLauncher.in(home).flatMap(GraalLauncher::homeOf)).contains(home);
    }

    @Test
    void a_home_with_no_launcher_is_empty(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve("bin"));
        assertThat(GraalLauncher.in(home)).isEmpty();
        assertThat(GraalLauncher.in(null)).isEmpty();
    }

    @Test
    void a_path_entry_reaches_the_svm_launcher_beside_it(@TempDir Path home) throws Exception {
        Path svmBin = home.resolve("lib").resolve("svm").resolve("bin");
        Files.createDirectories(svmBin);
        Path launcher = Files.writeString(svmBin.resolve(GraalLauncher.EXE), "MZ\n");
        Path binOnPath = Files.createDirectories(home.resolve("bin"));

        // The bin/ entry itself holds no launcher, but it identifies the home that does.
        assertThat(GraalLauncher.onPathEntry(binOnPath)).contains(launcher);
        assertThat(GraalLauncher.onPathEntry(home.resolve("nowhere"))).isEmpty();
        assertThat(GraalLauncher.onPathEntry(null)).isEmpty();
    }

    @Test
    void every_spelling_is_probed_on_every_host() {
        // TrainRunner missed .exe-only GraalVMs by probing two of the three names; the OS decides
        // the ORDER here, never the membership.
        assertThat(GraalLauncher.filenames(true))
                .containsExactly(GraalLauncher.EXE, GraalLauncher.CMD, GraalLauncher.NAME);
        assertThat(GraalLauncher.filenames(false))
                .containsExactly(GraalLauncher.NAME, GraalLauncher.EXE, GraalLauncher.CMD);
    }

    @Test
    void windows_prefers_the_unshimmed_launcher(@TempDir Path home) {
        // The bin/ entry on Windows is a .cmd bound by cmd.exe's command-line limit, so the
        // lib/svm/bin launcher must be probed first — not merely be reachable.
        assertThat(GraalLauncher.candidatesIn(home, true).get(0))
                .isEqualTo(home.resolve("lib").resolve("svm").resolve("bin").resolve(GraalLauncher.EXE));
        assertThat(GraalLauncher.candidatesIn(home, false).get(0))
                .isEqualTo(home.resolve("bin").resolve(GraalLauncher.NAME));
        assertThat(GraalLauncher.candidatesIn(home, true))
                .containsExactlyInAnyOrderElementsOf(GraalLauncher.candidatesIn(home, false));
    }

    @Test
    void searched_dirs_lists_what_is_actually_probed(@TempDir Path home) {
        for (String rel : GraalLauncher.searchedDirs().split(", ")) {
            Path dir = home;
            for (String segment : rel.split("/")) dir = dir.resolve(segment);
            Path probed = dir;
            assertThat(GraalLauncher.candidatesIn(home)).anyMatch(candidate -> probed.equals(candidate.getParent()));
        }
    }
}
