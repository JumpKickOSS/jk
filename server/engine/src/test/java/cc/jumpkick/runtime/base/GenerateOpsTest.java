// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.GeneratedFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerateOpsTest {

    @TempDir
    Path dir;

    @Test
    void a_member_pom_names_its_sibling_by_coordinate() throws IOException {
        write("jk.toml", """
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["core", "app"]
                """);
        write("core/jk.toml", """
                group = "com.example"
                name  = "core"
                version = "1.0.0"
                java = 25
                """);
        write("app/jk.toml", """
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [dependencies]
                core = { workspace = true }
                """);

        GeneratedFiles out = GenerateOps.generate(dir, "export-maven");

        assertThat(out.error()).isNull();
        String appPom = out.contents()
                .get(out.paths().indexOf(dir.resolve("app/pom.xml").toString()));
        assertThat(appPom)
                .contains("      <groupId>com.example</groupId>\n      <artifactId>core</artifactId>\n"
                        + "      <version>${project.version}</version>\n")
                .doesNotContain("workspace");
    }

    private void write(String rel, String text) throws IOException {
        Path file = dir.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
