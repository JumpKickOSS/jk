// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.command.system.DoctorCommand;
import cc.jumpkick.model.JkVersion;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code jk --version -v}, {@code jk engine status} and {@code jk doctor} name the commit the
 * client and the running engine were built from, and call out an engine of another build; a side
 * with no stamp is no evidence of a mismatch.
 */
class BuildStampsTest {

    private static final String A = "4ee07400a592deadbeef4ee07400a592deadbeef";
    private static final String B = "5ff18511b6a3deadbeef5ff18511b6a3deadbeef";

    @Test
    void version_lines_name_both_commits_and_flag_another_build() {
        assertThat(BuildStamps.versionLines(own(A), Optional.of(engine(A))))
                .containsExactly(
                        "  client  " + JkVersion.VERSION + " · commit 4ee07400a592",
                        "  engine  0.51.0 · commit 4ee07400a592 · pid 4242");
        assertThat(BuildStamps.versionLines(own(A), Optional.of(engine(B + "-dirty"))))
                .last()
                .isEqualTo("  engine  0.51.0 · commit 5ff18511b6a3 (dirty) · pid 4242 (not the client's build)");
        assertThat(BuildStamps.versionLines(new BuildStamps.Client(null, false), Optional.empty()))
                .containsExactly("  client  " + JkVersion.VERSION + " · commit unknown", "  engine  not running");
    }

    @Test
    void a_commit_the_install_recorded_is_labelled_and_still_flags_another_build() {
        BuildStamps.Client recorded = new BuildStamps.Client(A, true);
        assertThat(BuildStamps.versionLines(recorded, Optional.of(engine(B))))
                .containsExactly(
                        "  client  " + JkVersion.VERSION + " · commit 4ee07400a592 (recorded at install)",
                        "  engine  0.51.0 · commit 5ff18511b6a3 · pid 4242 (not the client's build)");
        assertThat(DoctorCommand.checkClient(recorded).detail()).endsWith("(recorded at install)");
    }

    @Test
    void a_test_jvm_is_no_installed_client_and_has_no_commit() {
        assertThat(BuildStamps.client()).isEqualTo(new BuildStamps.Client(null, false));
    }

    private static BuildStamps.Client own(String commit) {
        return new BuildStamps.Client(commit, false);
    }

    @Test
    void an_unstamped_side_is_no_mismatch() {
        assertThat(BuildStamps.differ(A, B)).isTrue();
        assertThat(BuildStamps.differ(A, A)).isFalse();
        assertThat(BuildStamps.differ(A, A + "-dirty")).isTrue();
        assertThat(BuildStamps.differ(null, B)).isFalse();
        assertThat(BuildStamps.differ(A, "")).isFalse();
    }

    @Test
    void the_doctor_client_row_names_the_commit() {
        assertThat(DoctorCommand.checkClient(own(A)))
                .isEqualTo(new DoctorCommand.Check(
                        DoctorCommand.Status.OK, "client", JkVersion.VERSION + " · commit 4ee07400a592"));
    }

    private static EngineProbe.Status engine(String commit) {
        return new EngineProbe.Status(
                "0.51.0",
                4242,
                1_700_000_000_000L,
                0,
                0,
                false,
                -1,
                -1,
                -1,
                -1,
                null,
                null,
                null,
                null,
                -1,
                -1,
                -1,
                -1,
                -1,
                "epoch",
                -1,
                -1,
                -1,
                null,
                0,
                null,
                null,
                null,
                -1L,
                -1L,
                -1L,
                -1L,
                0L,
                0,
                null,
                -1,
                -1,
                false,
                null,
                null,
                0,
                0,
                0,
                -1L,
                List.of(),
                null,
                null,
                commit,
                null);
    }
}
