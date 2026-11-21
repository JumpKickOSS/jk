// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A shadow's header goes stale when a file its import read changes, appears or disappears. */
class ShadowStampTest {

    private static final String POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.demo</groupId>
              <artifactId>app</artifactId>
              <version>${revision}</version>
            </project>
            """;

    @Test
    void creating_editing_or_deleting_maven_config_stales_the_shadow(@TempDir Path module) throws Exception {
        Path pom = Files.writeString(module.resolve("pom.xml"), POM);
        Path shadow = module.resolve("shadow.toml");
        Path config = module.resolve(".mvn/maven.config");

        render(shadow, module, pom);
        assertThat(Files.readString(shadow)).contains("# read .mvn/maven.config\n");
        assertThat(ShadowStamp.isCurrent(shadow, module))
                .as("absent then, absent now")
                .isTrue();

        Files.createDirectories(config.getParent());
        Files.writeString(config, "-Drevision=1.0.0\n");
        assertThat(ShadowStamp.isCurrent(shadow, module)).as("created").isFalse();

        render(shadow, module, pom);
        assertThat(ShadowStamp.isCurrent(shadow, module)).isTrue();
        Files.writeString(config, "-Drevision=1.0.1\n");
        assertThat(ShadowStamp.isCurrent(shadow, module)).as("edited").isFalse();

        render(shadow, module, pom);
        Files.delete(config);
        assertThat(ShadowStamp.isCurrent(shadow, module)).as("deleted").isFalse();
    }

    @Test
    void a_deleted_pom_stales_the_shadow(@TempDir Path module) throws Exception {
        Path pom = Files.writeString(module.resolve("pom.xml"), POM);
        Path shadow = module.resolve("shadow.toml");

        render(shadow, module, pom);
        Files.delete(pom);

        assertThat(ShadowStamp.isCurrent(shadow, module)).isFalse();
    }

    private static void render(Path shadow, Path module, Path pom) throws Exception {
        Files.writeString(shadow, ShadowStamp.header(module, ShadowStamp.inputs(pom)) + "[project]\n");
    }
}
