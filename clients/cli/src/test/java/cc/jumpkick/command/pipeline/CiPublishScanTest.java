// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CiPublishScanTest {

    @TempDir
    Path root;

    private void write(String file, String text) throws IOException {
        Path path = root.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    @Test
    void a_workflow_step_that_runs_jk_publish_counts() throws IOException {
        write(".github/workflows/publish.yml", "jobs:\n  p:\n    steps:\n      - run: jk publish\n");
        assertThat(CiPublishScan.runsPublish(root)).isTrue();
    }

    @Test
    void a_pipeline_that_only_tests_does_not_count() throws IOException {
        write(
                ".github/workflows/ci.yml",
                "jobs:\n  t:\n    steps:\n      - run: jk test\n      - run: jk release --dry-run\n");
        write(".gitlab-ci.yml", "script:\n  - jk build\n");
        assertThat(CiPublishScan.runsPublish(root)).isFalse();
    }

    @Test
    void a_commented_line_does_not_count() throws IOException {
        write("Jenkinsfile", "// sh 'jk publish'\n");
        write(".circleci/config.yml", "# - run: jk publish\n");
        assertThat(CiPublishScan.runsPublish(root)).isFalse();
    }

    @Test
    void the_other_ci_systems_files_are_read() throws IOException {
        write(".gitlab-ci.yml", "release:\n  script:\n    - jk publish\n");
        assertThat(CiPublishScan.runsPublish(root)).isTrue();
    }

    @Test
    void a_word_that_only_ends_in_jk_is_not_the_command() throws IOException {
        write(".drone.yml", "commands:\n  - mjk publish\n  - jk publisher\n");
        assertThat(CiPublishScan.runsPublish(root)).isFalse();
    }

    @Test
    void a_repository_with_no_ci_files_publishes_nowhere() {
        assertThat(CiPublishScan.runsPublish(root)).isFalse();
    }
}
