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
    void openRun_allocates_number_and_creates_layout(@TempDir Path root) throws Exception {
        ProjectBuilds.RunDir run = ProjectBuilds.openRun(root, "g:demo", root.resolve("proj"));
        assertThat(run.runNumber()).isEqualTo(1);
        assertThat(Files.isDirectory(run.runDir())).isTrue();
        assertThat(Files.isRegularFile(run.projectHome().resolve(ProjectBuilds.IDENTITY))).isTrue();
        assertThat(Files.isRegularFile(run.projectHome().resolve(ProjectBuilds.RUN_NUMBER))).isTrue();
        assertThat(Files.readString(run.projectHome().resolve(ProjectBuilds.RUN_NUMBER)).trim())
                .isEqualTo("1");

        ProjectBuilds.RunDir run2 = ProjectBuilds.openRun(root, "g:demo", root.resolve("proj"));
        assertThat(run2.runNumber()).isEqualTo(2);
        assertThat(run2.runId()).isNotEqualTo(run.runId());
    }

    @Test
    void findRunDir_locates_across_projects(@TempDir Path root) throws Exception {
        ProjectBuilds.RunDir run = ProjectBuilds.openRun(root, "g:a", root.resolve("a"));
        assertThat(ProjectBuilds.findRunDir(root, run.runId())).isPresent().contains(run.runDir());
        assertThat(ProjectBuilds.findRunDir(root, "../escape")).isEmpty();
        assertThat(ProjectBuilds.findRunDir(root, "nope")).isEmpty();
    }

    @Test
    void listAllRuns_newest_first(@TempDir Path root) throws Exception {
        ProjectBuilds.RunDir older = ProjectBuilds.openRun(root, "g:a", root.resolve("a"));
        Thread.sleep(2);
        ProjectBuilds.RunDir newer = ProjectBuilds.openRun(root, "g:a", root.resolve("a"));
        var all = ProjectBuilds.listAllRuns(root);
        assertThat(all.get(0)).isEqualTo(newer.runDir());
        assertThat(all).contains(older.runDir());
    }
}
