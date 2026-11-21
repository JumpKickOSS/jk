// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A shadow's header goes stale when a file its import read changes, appears or disappears, and
 * lists its inputs on one line however many there are.
 */
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
        assertThat(Files.readString(shadow)).contains("# read pom.xml .mvn/maven.config\n");
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

    @Test
    void a_reactor_s_inputs_share_one_header_line(@TempDir Path root) throws Exception {
        Path module = Files.createDirectories(root.resolve("leaf-0"));
        List<Path> inputs = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            Path dir = Files.createDirectories(root.resolve("leaf-" + i));
            inputs.add(Files.writeString(dir.resolve("pom.xml"), POM));
        }
        Path shadow = module.resolve("shadow.toml");

        String header = ShadowStamp.header(module, inputs);
        Files.writeString(shadow, header + "[project]\n");

        assertThat(header.lines())
                .as("a run of comment lines makes the TOML parse grow faster than linearly")
                .hasSize(2);
        assertThat(ShadowStamp.isCurrent(shadow, module)).isTrue();
        Files.writeString(inputs.get(399), POM + "\n");
        assertThat(ShadowStamp.isCurrent(shadow, module))
                .as("the last input edited")
                .isFalse();
    }

    @Test
    void a_path_with_a_space_or_percent_reads_back(@TempDir Path root) throws Exception {
        Path module = Files.createDirectories(root.resolve("my app"));
        Path pom = Files.writeString(module.resolve("pom.xml"), POM);
        Path odd = Files.createDirectories(root.resolve("50% off"));
        Path parent = Files.writeString(odd.resolve("pom.xml"), POM);
        Path shadow = module.resolve("shadow.toml");

        Files.writeString(shadow, ShadowStamp.header(module, List.of(pom, parent)) + "[project]\n");

        assertThat(Files.readString(shadow)).contains("# read pom.xml ../50%25%20off/pom.xml\n");
        assertThat(ShadowStamp.isCurrent(shadow, module)).isTrue();
        Files.writeString(parent, POM + "\n");
        assertThat(ShadowStamp.isCurrent(shadow, module)).isFalse();
    }

    private static void render(Path shadow, Path module, Path pom) throws Exception {
        Files.writeString(shadow, ShadowStamp.header(module, ShadowStamp.inputs(pom)) + "[project]\n");
    }
}
