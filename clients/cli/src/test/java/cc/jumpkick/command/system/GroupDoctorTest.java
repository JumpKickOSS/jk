// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk doctor} at a group root names a listed member that is gone and a project no entry lists. */
class GroupDoctorTest {

    @TempDir
    Path root;

    private void write(String rel, String text) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void a_missing_member_fails_and_an_unlisted_project_warns() throws IOException {
        write("cache/jk.toml", "name = \"cache\"\n");
        write("cache/core/jk.toml", "name = \"core\"\n"); // the member's own module: not the group's business
        write("tools/stray/jk.toml", "name = \"stray\"\n");
        write("cache/target/x/jk.toml", "name = \"built\"\n");
        write("jk.toml", "[workspaces]\nmembers = [\"cache\", \"gone\"]\n");

        GroupDoctor.Report report = Objects.requireNonNull(GroupDoctor.of(root));

        assertThat(report.missing()).containsExactly("gone");
        assertThat(report.unlisted()).containsExactly("tools/stray");
        DoctorCommand.Check check = GroupDoctor.check(report);
        assertThat(check.status()).isEqualTo(DoctorCommand.Status.FAIL);
        assertThat(check.detail())
                .contains("listed with no project: gone")
                .contains("not listed in [workspaces] members: tools/stray");
    }

    @Test
    void a_complete_group_is_ok_and_a_project_is_no_group(@TempDir Path project) throws IOException {
        write("cache/jk.toml", "name = \"cache\"\n");
        write("jk.toml", "[workspaces]\nmembers = [\"cache\"]\n");
        Files.writeString(project.resolve("jk.toml"), "name = \"p\"\n");

        assertThat(GroupDoctor.check(Objects.requireNonNull(GroupDoctor.of(root)))
                        .status())
                .isEqualTo(DoctorCommand.Status.OK);
        assertThat(GroupDoctor.of(project)).isNull();
    }
}
