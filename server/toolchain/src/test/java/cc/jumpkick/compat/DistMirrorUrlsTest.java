// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.gradle.GradleResolver;
import cc.jumpkick.mvn.MavenResolver;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The Gradle and Maven distributions resolve under their configured mirrors. */
class DistMirrorUrlsTest {

    private static final List<String> NAMES = List.of("JK_GRADLE_DIST_MIRROR", "JK_MAVEN_DIST_MIRROR");

    @AfterEach
    void clear() {
        NAMES.forEach(name -> System.clearProperty("jk.env." + name));
    }

    @Test
    void gradle_and_maven_distributions_come_from_their_mirrors() {
        System.setProperty("jk.env.JK_GRADLE_DIST_MIRROR", "https://nexus.corp/gradle");
        System.setProperty("jk.env.JK_MAVEN_DIST_MIRROR", "https://nexus.corp/maven2/");

        assertThat(GradleResolver.distributionFor("9.8.0").downloadUri())
                .hasToString("https://nexus.corp/gradle/gradle-9.8.0-bin.zip");
        assertThat(MavenResolver.distributionFor("3.9.16").downloadUri())
                .hasToString(
                        "https://nexus.corp/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip");
    }
}
