// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

/**
 * The application's own coordinate is the augment's to answer: the jar goes into the private
 * repository with a POM beside it, so the descriptor Aether reads for it is a local read and no
 * request for it reaches a remote that could refuse it.
 */
class BootstrapRepoTest {

    private record Installed(String group, String artifact, String version, String type, Path file) {}

    @Test
    void the_jar_and_its_pom_are_installed_under_the_one_coordinate(@TempDir Path dir) throws Exception {
        List<Installed> installed = new ArrayList<>();
        Path jar = Files.writeString(dir.resolve("app.jar"), "not really a jar");

        BootstrapRepo.install(
                (g, a, v, type, file) -> installed.add(new Installed(g, a, v, type, file)),
                "io.example",
                "app",
                "1.0",
                jar,
                dir.resolve("poms"));

        assertThat(installed).extracting(Installed::type).containsExactly("jar", "pom");
        assertThat(installed).allSatisfy(i -> {
            assertThat(i.group()).isEqualTo("io.example");
            assertThat(i.artifact()).isEqualTo("app");
            assertThat(i.version()).isEqualTo("1.0");
        });
        assertThat(installed.get(0).file()).isEqualTo(jar);
        Document pom = DocumentBuilderFactory.newDefaultInstance()
                .newDocumentBuilder()
                .parse(installed.get(1).file().toFile());
        assertThat(pom.getDocumentElement().getTagName()).isEqualTo("project");
        assertThat(pom.getElementsByTagName("groupId").item(0).getTextContent()).isEqualTo("io.example");
        assertThat(pom.getElementsByTagName("artifactId").item(0).getTextContent())
                .isEqualTo("app");
        assertThat(pom.getElementsByTagName("version").item(0).getTextContent()).isEqualTo("1.0");
        assertThat(pom.getElementsByTagName("dependencies").getLength()).isZero();
    }

    @Test
    void the_pom_says_only_what_the_coordinate_is() {
        String pom = BootstrapRepo.pom("jk.workspace", "domain", "0.1.0");

        assertThat(pom)
                .contains("<modelVersion>4.0.0</modelVersion>")
                .contains("<groupId>jk.workspace</groupId>")
                .contains("<artifactId>domain</artifactId>")
                .contains("<version>0.1.0</version>")
                .doesNotContain("<dependencies>");
    }
}
