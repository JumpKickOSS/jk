// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** One coordinate, one class's bytes: {@link MockMavenServer#claim} across the classes of one JVM. */
class MockMavenServerClaimTest {

    private static final String POM = "/claim/test/leaf/1.0/leaf-1.0.pom";

    private static Map<String, byte[]> serving(String path, String body) {
        return Map.of(path, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void another_class_serving_other_bytes_at_a_path_fails_naming_both() {
        String jar = "/claim/other/leaf/1.0/leaf-1.0.jar";
        MockMavenServer.claim("a.First", serving(jar, "first"));

        assertThatThrownBy(() -> MockMavenServer.claim("b.Second", serving(jar, "second")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("b.Second")
                .hasMessageContaining("a.First")
                .hasMessageContaining(jar);
    }

    @Test
    void the_same_bytes_from_another_class_and_other_bytes_from_the_same_class_pass() {
        MockMavenServer.claim("a.First", serving(POM, "<project/>"));

        assertThatCode(() -> MockMavenServer.claim("b.Second", serving(POM, "<project/>")))
                .doesNotThrowAnyException();
        assertThatCode(() -> MockMavenServer.claim("a.First", serving(POM, "<project>other</project>")))
                .doesNotThrowAnyException();
    }

    @Test
    void version_lists_and_checksum_sidecars_are_not_claimed() {
        String metadata = "/claim/meta/leaf/maven-metadata.xml";
        String sidecar = "/claim/meta/leaf/1.0/leaf-1.0.jar.sha1";
        MockMavenServer.claim("a.First", Map.of(metadata, new byte[] {1}, sidecar, new byte[] {1}));

        assertThatCode(() ->
                        MockMavenServer.claim("b.Second", Map.of(metadata, new byte[] {2}, sidecar, new byte[] {2})))
                .doesNotThrowAnyException();
    }
}
