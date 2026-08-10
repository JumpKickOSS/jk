// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    void listRuns_newest_number_first(@TempDir Path root) throws Exception {
        ProjectBuilds.RunDir older = ProjectBuilds.openRun(root, "g:a", root.resolve("a"));
        ProjectBuilds.RunDir newer = ProjectBuilds.openRun(root, "g:a", root.resolve("a"));
        var runs = ProjectBuilds.listRuns(newer.projectHome());
        assertThat(runs.get(0)).isEqualTo(newer.runDir());
        assertThat(runs).contains(older.runDir());
    }

    /**
     * JK-1472: two engines are routinely alive at once (a draining predecessor plus its successor,
     * or {@code --job} children). Every allocation must be unique across processes, not just
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
