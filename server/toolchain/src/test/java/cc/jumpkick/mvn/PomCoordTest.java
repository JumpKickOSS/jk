// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PomCoordTest {

    @TempDir
    Path tmp;

    @Test
    void own_group_wins_and_a_parent_group_fills_in() throws Exception {
        Files.writeString(
                tmp.resolve("pom.xml"),
                "<project><groupId>com.example</groupId><artifactId>app</artifactId></project>");
        assertThat(PomCoord.of(tmp)).isEqualTo("com.example:app");
        Files.writeString(
                tmp.resolve("pom.xml"),
                "<project><parent><groupId>org.parent</groupId></parent><artifactId>child</artifactId></project>");
        assertThat(PomCoord.of(tmp)).isEqualTo("org.parent:child");
    }

    @Test
    void a_pom_without_a_version_names_the_file_and_the_element_it_lacks() throws Exception {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, "<project><groupId>com.example</groupId><artifactId>app</artifactId></project>");
        assertThat(PomCoord.missingIdentity(tmp)).contains(pom + " has no <version> and no <parent><version>");
        Files.writeString(pom, "<project><artifactId>app</artifactId><version>1.0</version></project>");
        assertThat(PomCoord.missingIdentity(tmp)).contains(pom + " has no <groupId> and no <parent><groupId>");
        Files.writeString(
                pom,
                "<project><parent><groupId>org.parent</groupId><version>2.0</version></parent>"
                        + "<artifactId>child</artifactId></project>");
        assertThat(PomCoord.missingIdentity(tmp)).isEmpty();
        Files.writeString(
                pom,
                "<project><groupId>com.example</groupId><artifactId>app</artifactId><version>1.0</version></project>");
        assertThat(PomCoord.missingIdentity(tmp)).isEmpty();
    }

    @Test
    void no_pom_or_no_artifact_is_null() throws Exception {
        assertThat(PomCoord.of(tmp)).isNull();
        Files.writeString(tmp.resolve("pom.xml"), "<project><groupId>g</groupId></project>");
        assertThat(PomCoord.of(tmp)).isNull();
        Files.writeString(tmp.resolve("pom.xml"), "not xml");
        assertThat(PomCoord.of(tmp)).isNull();
    }
}
