// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectBuildsTest {

    @Test
    void key_is_stable_and_path_sensitive() {
        String a = ProjectBuilds.key("g:n", Path.of("/proj"));
        String b = ProjectBuilds.key("g:n", Path.of("/proj"));
        String c = ProjectBuilds.key("g:n", Path.of("/other"));
        String d = ProjectBuilds.key("g:other", Path.of("/proj"));
        assertThat(a).isEqualTo(b).hasSize(24);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo(d);
    }

    @Test
    void openRun_uses_build_number_as_directory(@TempDir Path root) throws Exception {
        ProjectBuilds.RunDir run = ProjectBuilds.openRun(root, "g:demo", root.resolve("proj"));
        assertThat(run.buildNumber()).isEqualTo(1);
        assertThat(run.runDir().getFileName().toString()).isEqualTo("1");
        assertThat(Files.isDirectory(run.runDir())).isTrue();
        assertThat(Files.isRegularFile(run.projectHome().resolve(ProjectBuilds.IDENTITY))).isTrue();
        assertThat(Files.readString(run.projectHome().resolve(ProjectBuilds.RUN_NUMBER)).trim())
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
}
