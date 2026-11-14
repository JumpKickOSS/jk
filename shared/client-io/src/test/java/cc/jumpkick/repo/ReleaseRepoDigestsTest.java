// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReleaseRepoDigestsTest {

    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);

    private static ReleaseRepoDigests parse(String text) throws IOException {
        return ReleaseRepoDigests.parse("1.0.0", text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void each_line_names_a_repository_path_and_its_digest() throws IOException {
        ReleaseRepoDigests digests = parse(A + "  cc/jumpkick/jk-publisher/1.0.0/jk-publisher-1.0.0.jar\n" + B
                + "  cc/jumpkick/guards/spring/1.0.0/spring-1.0.0.pom\n");

        assertThat(digests.sha256("cc/jumpkick/jk-publisher/1.0.0/jk-publisher-1.0.0.jar"))
                .contains(A);
        assertThat(digests.sha256("cc/jumpkick/guards/spring/1.0.0/spring-1.0.0.pom"))
                .contains(B);
        assertThat(digests.sha256("cc/jumpkick/jk-other/1.0.0/jk-other-1.0.0.jar"))
                .isEmpty();
        assertThat(ReleaseRepoDigests.fileName("1.0.0")).isEqualTo("jk-repo-1.0.0.sha256");
    }

    @Test
    void a_path_that_climbs_or_is_absolute_is_refused() {
        assertThatThrownBy(() -> parse(A + "  cc/jumpkick/../evil.jar\n")).hasMessageContaining("malformed");
        assertThatThrownBy(() -> parse(A + "  /etc/evil.jar\n")).hasMessageContaining("malformed");
        assertThatThrownBy(() -> parse(A + "  ./evil.jar\n")).hasMessageContaining("malformed");
    }

    @Test
    void a_malformed_line_or_a_path_listed_twice_is_refused() {
        assertThatThrownBy(() -> parse(A.toUpperCase() + "  a.jar\n")).hasMessageContaining("malformed");
        assertThatThrownBy(() -> parse(A + " a.jar\n")).hasMessageContaining("malformed");
        assertThatThrownBy(() -> parse(A + "  a.jar\r\n")).hasMessageContaining("malformed");
        assertThatThrownBy(() -> parse(A + "  a.jar\n" + B + "  a.jar\n")).hasMessageContaining("twice");
    }
}
