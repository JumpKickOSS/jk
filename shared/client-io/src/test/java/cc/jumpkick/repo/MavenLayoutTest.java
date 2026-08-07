// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Coordinate;
import org.junit.jupiter.api.Test;

/**
 * Repository paths must name files Maven actually publishes. A packaging type is not an
 * extension: {@code test-jar} publishes as {@code -tests.jar}, so asking for {@code .test-jar}
 * 404s and the dependency silently drops off the classpath (JK-1601).
 */
class MavenLayoutTest {

    private static Coordinate coord(String type, String classifier) {
        return new Coordinate("com.acme", "helpers", "1.2.3", classifier, type);
    }

    @Test
    void plain_jar_path() {
        assertThat(MavenLayout.artifactPath(coord("jar", null)))
                .isEqualTo("com/acme/helpers/1.2.3/helpers-1.2.3.jar");
    }

    @Test
    void test_jar_packaging_publishes_a_jar_with_the_tests_classifier() {
        assertThat(MavenLayout.artifactPath(coord("test-jar", "tests")))
                .isEqualTo("com/acme/helpers/1.2.3/helpers-1.2.3-tests.jar");
    }

    @Test
    void bundle_and_maven_plugin_packaging_are_jars() {
        assertThat(MavenLayout.artifactPath(coord("bundle", null)))
                .endsWith("helpers-1.2.3.jar");
        assertThat(MavenLayout.artifactPath(coord("maven-plugin", null)))
                .endsWith("helpers-1.2.3.jar");
    }

    @Test
    void aar_and_war_are_their_own_extension() {
        assertThat(MavenLayout.artifactPath(coord("aar", null))).endsWith("helpers-1.2.3.aar");
        assertThat(MavenLayout.artifactPath(coord("war", null))).endsWith("helpers-1.2.3.war");
    }

    @Test
    void a_classifier_rides_the_name_but_never_the_pom() {
        assertThat(MavenLayout.artifactPath(coord("jar", "linux-x86_64")))
                .endsWith("helpers-1.2.3-linux-x86_64.jar");
        // Maven POMs are never classified — secondary artifacts share the main GAV's pom.
        assertThat(MavenLayout.pomPath(coord("test-jar", "tests")))
                .isEqualTo("com/acme/helpers/1.2.3/helpers-1.2.3.pom");
    }

    @Test
    void metadata_path_ignores_version_and_classifier() {
        assertThat(MavenLayout.metadataPath(coord("test-jar", "tests")))
                .isEqualTo("com/acme/helpers/maven-metadata.xml");
    }
}
