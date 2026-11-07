// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChangeSummaryTest {

    @Test
    void a_long_note_wraps_at_78_columns_under_its_first_word() {
        var lines = ChangeSummary.wrap(
                " 1) ",
                "gitlab: credential moved to ~/.jk/config.toml. On GitLab CI, export"
                        + " JK_REPO_GITLAB_USERNAME=gitlab-ci-token, JK_REPO_GITLAB_PASSWORD=$CI_JOB_TOKEN,"
                        + " JK_REPO_GITLAB_HOST=gitlab.com");
        assertThat(lines)
                .containsExactly(
                        " 1) gitlab: credential moved to ~/.jk/config.toml. On GitLab CI, export",
                        "    JK_REPO_GITLAB_USERNAME=gitlab-ci-token,",
                        "    JK_REPO_GITLAB_PASSWORD=$CI_JOB_TOKEN, JK_REPO_GITLAB_HOST=gitlab.com");
        assertThat(lines).allMatch(l -> l.length() <= ChangeSummary.WIDTH);
    }

    @Test
    void a_short_note_is_one_line_and_counts_are_lazy_plurals() {
        assertThat(ChangeSummary.wrap(" 2) ", "short")).containsExactly(" 2) short");
        assertThat(ChangeSummary.count(1, "issue")).isEqualTo("1 issue");
        assertThat(ChangeSummary.count(3, "issue")).isEqualTo("3 issues");
    }
}
