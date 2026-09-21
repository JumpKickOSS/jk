// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.testing.Symlinks;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectBuildsTest {

    @Test
    void key_is_stable_per_checkout_path(@TempDir Path root) throws Exception {
        Path aDir = root.resolve("a");
        Path bDir = root.resolve("b");
        Files.createDirectories(aDir);
        Files.createDirectories(bDir);
        String a = ProjectBuilds.key(aDir);
        String a2 = ProjectBuilds.key(aDir);
        String b = ProjectBuilds.key(bDir);
        assertThat(a).isEqualTo(a2);
        assertThat(ProjectIdentity.isValidId(a)).isTrue();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void openRun_uses_build_number_as_directory(@TempDir Path root) throws Exception {
        ProjectBuilds.RunDir run = ProjectBuilds.openRun(root, "g:demo", root.resolve("proj"));
        assertThat(run.buildNumber()).isEqualTo(1);
        assertThat(run.runDir().getFileName().toString()).isEqualTo("1");
        assertThat(Files.isDirectory(run.runDir())).isTrue();
        assertThat(Files.isRegularFile(run.projectHome().resolve(ProjectBuilds.IDENTITY)))
                .isTrue();
        assertThat(Files.readString(run.projectHome().resolve(ProjectBuilds.RUN_NUMBER))
                        .trim())
                .isEqualTo("1");

        ProjectBuilds.RunDir run2 = ProjectBuilds.openRun(root, "g:demo", root.resolve("proj"));
        assertThat(run2.buildNumber()).isEqualTo(2);
        assertThat(run2.runDir().getFileName().toString()).isEqualTo("2");
    }

    @Test
    void findRunDir_by_number(@TempDir Path root) throws Exception {
        ProjectBuilds.RunDir run = ProjectBuilds.openRun(root, "g:a", root.resolve("a"));
        assertThat(ProjectBuilds.findRunDir(run.projectHome(), run.buildNumber()))
                .isPresent()
                .contains(run.runDir());
        assertThat(ProjectBuilds.findRunDirByNumber(root, run.buildNumber()))
                .isPresent()
                .contains(run.runDir());
        assertThat(ProjectBuilds.validRunDirName("../escape")).isFalse();
        assertThat(ProjectBuilds.validRunDirName("nope")).isFalse();
        assertThat(ProjectBuilds.validRunDirName("27")).isTrue();
    }

    @Test
    void latestRunFile_skips_newer_runs_missing_the_file(@TempDir Path root) throws Exception {
        Path proj = checkout(root, "proj", "latest-run-file");
        Path builds = root.resolve("builds");
        ProjectBuilds.RunDir older = openRecorded(builds, proj);
        Files.writeString(older.resultsFile(), "older\n");
        Files.writeString(older.detailsFile(), "{\"type\":\"error\"}\n");
        ProjectBuilds.RunDir newer = openRecorded(builds, proj);
        Files.writeString(newer.detailsFile(), "{\"type\":\"task-finish\"}\n");
        assertThat(ProjectBuilds.latestRunFile(builds, proj, ProjectBuilds.RESULTS))
                .contains(older.resultsFile());
        assertThat(ProjectBuilds.latestRunFile(builds, proj, ProjectBuilds.DETAILS))
                .contains(newer.detailsFile());
        assertThat(ProjectBuilds.latestRunFile(builds, proj, "../escape")).isEmpty();
    }

    /**
     * Two worktrees of one repository share the id their lock carries, so they share a project
     * home and one build-number sequence. The latest run of each is the newest run whose record
     * names that checkout, never the sibling's; a run without a record belongs to neither.
     */
    @Test
    void latestRunFile_answers_for_the_callers_checkout(@TempDir Path root) throws Exception {
        Path a = checkout(root, "wt-a", "shared-lock-id");
        Path b = checkout(root, "wt-b", "shared-lock-id");
        Path builds = root.resolve("builds");
        ProjectBuilds.RunDir first = openRecorded(builds, a);
        Files.writeString(first.resultsFile(), "a #1\n");
        ProjectBuilds.RunDir second = openRecorded(builds, b);
        Files.writeString(second.resultsFile(), "b #2\n");
        ProjectBuilds.RunDir third = openRecorded(builds, a);
        Files.writeString(third.resultsFile(), "a #3\n");
        // A run the client opened without the engine: no record.json, but the checkout marker.
        ProjectBuilds.RunDir unrecorded = ProjectBuilds.openRun(builds, "g:n", b);
        Files.writeString(unrecorded.resultsFile(), "b #4 (client-opened)\n");

        assertThat(first.projectHome()).isEqualTo(second.projectHome());
        assertThat(List.of(first.buildNumber(), second.buildNumber(), third.buildNumber(), unrecorded.buildNumber()))
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(ProjectBuilds.latestRunFile(builds, a, ProjectBuilds.RESULTS))
                .contains(third.resultsFile());
        assertThat(ProjectBuilds.latestRunFile(builds, b, ProjectBuilds.RESULTS))
                .contains(unrecorded.resultsFile());
        assertThat(ProjectBuilds.runCheckout(unrecorded.runDir()))
                .isEqualTo(b.toAbsolutePath().normalize());

        var identity = ProjectIdentity.IdentityFile.read(first.projectHome()).orElseThrow();
        assertThat(identity.checkouts().stream().map(ProjectIdentity.Checkout::path))
                .containsExactly(
                        a.toAbsolutePath().normalize(), b.toAbsolutePath().normalize());
    }

    @Test
    void sameCheckout_compares_real_paths(@TempDir Path root) throws Exception {
        Path real = Files.createDirectories(root.resolve("real"));
        Path link = Symlinks.create(root.resolve("link"), real);
        assertThat(ProjectBuilds.sameCheckout(link, real)).isTrue();
        assertThat(ProjectBuilds.sameCheckout(root.resolve("real/../real"), real))
                .isTrue();
        assertThat(ProjectBuilds.sameCheckout(root.resolve("gone-a"), root.resolve("gone-b")))
                .isFalse();
        assertThat(ProjectBuilds.sameCheckout(root.resolve("gone"), root.resolve("gone")))
                .isTrue();
    }

    private static Path checkout(Path root, String name, String id) throws Exception {
        Path proj = Files.createDirectories(root.resolve(name));
        Files.writeString(proj.resolve("jk.toml"), """
                id = "%s"
                group = "g"
                name = "n"
                version = "1"
                """.formatted(id));
        return proj;
    }

    /** A run the engine would have journaled: its {@code record.json} names the checkout. */
    private static ProjectBuilds.RunDir openRecorded(Path builds, Path proj) throws Exception {
        ProjectBuilds.RunDir run = ProjectBuilds.openRun(builds, "g:n", proj);
        Files.writeString(
                run.recordFile(),
                MiniJson.write(
                        Map.of("dir", proj.toAbsolutePath().normalize().toString(), "buildNumber", run.buildNumber())));
        return run;
    }

    @Test
    void listRuns_newest_number_first(@TempDir Path root) throws Exception {
        ProjectBuilds.RunDir older = ProjectBuilds.openRun(root, "g:a", root.resolve("a"));
        ProjectBuilds.RunDir newer = ProjectBuilds.openRun(root, "g:a", root.resolve("a"));
        var runs = ProjectBuilds.listRuns(newer.projectHome());
        assertThat(runs.get(0)).isEqualTo(newer.runDir());
        assertThat(runs).contains(older.runDir());
    }

    @Test
    void listRuns_orders_unnumbered_job_dirs_by_their_stamp(@TempDir Path root) throws Exception {
        ProjectBuilds.RunDir numbered = ProjectBuilds.openRun(root, "g:a", root.resolve("a"));
        Path runs = numbered.projectHome().resolve(ProjectBuilds.RUNS);
        Path later = Files.createDirectories(runs.resolve("j-20260916T040909688-2"));
        Path earlier = Files.createDirectories(runs.resolve("j-20260916T040909598-1"));
        assertThat(ProjectBuilds.listRuns(numbered.projectHome())).containsExactly(numbered.runDir(), later, earlier);
    }

    /**
     * Two engines are routinely alive at once (a draining predecessor plus its successor,
     * successor). Every allocation must be unique across processes, not just
     * threads, or two runs collide on one {@code runs/<n>} directory and one is destroyed.
     */
    @Test
    void run_numbers_are_unique_across_concurrent_processes(@TempDir Path root) throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        int processes = 3;
        int perProcess = 25;
        String classpath = System.getProperty("java.class.path");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");

        List<Process> running = new ArrayList<>();
        for (int i = 0; i < processes; i++) {
            running.add(new ProcessBuilder(
                            java.toString(),
                            "-cp",
                            classpath,
                            AllocMain.class.getName(),
                            home.toString(),
                            Integer.toString(perProcess))
                    .redirectErrorStream(true)
                    .start());
        }
        List<String> allocated = new ArrayList<>();
        for (Process p : running) {
            try (var in = p.getInputStream()) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    if (!line.isBlank()) allocated.add(line.trim());
                }
            }
            assertThat(p.waitFor()).as("allocator subprocess exit").isZero();
        }
        assertThat(allocated).hasSize(processes * perProcess);
        assertThat(allocated).as("no build number handed out twice").doesNotHaveDuplicates();
    }

    /** Subprocess body for {@link #run_numbers_are_unique_across_concurrent_processes}. */
    public static final class AllocMain {
        public static void main(String[] args) throws Exception {
            Path home = Path.of(args[0]);
            int n = Integer.parseInt(args[1]);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < n; i++) {
                out.append(ProjectBuilds.allocateRunNumber(home)).append('\n');
            }
            System.out.print(out);
        }
    }
}
