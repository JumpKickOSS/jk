// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogLockParityTest {

    private static final String CATALOG = """
            [versions]
            asm = "9.7"
            junit = "5.11.0"

            [libraries]
            asm = { module = "org.ow2.asm:asm", version.ref = "asm" }
            asm-tree = { module = "org.ow2.asm:asm-tree", version.ref = "asm" }
            tomlj = { module = "org.tomlj:tomlj", version = "1.1.1" }
            junit = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
            """;

    private static void lock(Path root, String asm, String tomlj) throws IOException {
        Files.writeString(root.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "jk 0.13.0"
                resolution-algorithm = "pubgrub-v1"

                [[artifact]]
                name     = "org.ow2.asm:asm:jar:"
                version  = "%s"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:00"

                [[artifact]]
                name     = "org.tomlj:tomlj:jar:"
                version  = "%s"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:01"
                """.formatted(asm, tomlj));
    }

    @Test
    void catalog_parses_literal_and_referenced_versions() {
        assertThat(CatalogLockParity.catalog(CATALOG))
                .containsEntry("org.ow2.asm:asm", "9.7")
                .containsEntry("org.ow2.asm:asm-tree", "9.7")
                .containsEntry("org.tomlj:tomlj", "1.1.1")
                .containsEntry("org.junit.jupiter:junit-jupiter", "5.11.0");
    }

    @Test
    void shared_modules_must_agree_and_unshared_ones_are_nobodys_business(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("gradle"));
        Files.writeString(root.resolve(CatalogLockParity.CATALOG), CATALOG);
        lock(root, "9.7", "1.1.1");
        assertThat(CatalogLockParity.validate(root)).isEmpty();
        lock(root, "9.8", "1.1.1");
        List<Fault> faults = CatalogLockParity.validate(root);
        assertThat(faults).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("catalog-lock");
            assertThat(f.observed())
                    .contains("org.ow2.asm:asm: catalog=9.7 lock=[9.8]")
                    .doesNotContain("tomlj")
                    .doesNotContain("junit");
        });
    }

    @Test
    void without_a_catalog_there_is_nothing_to_compare(@TempDir Path root) throws Exception {
        lock(root, "9.7", "1.1.1");
        assertThat(CatalogLockParity.validate(root)).isEmpty();
        assertThat(EngineValidations.model(root)).isEmpty();
    }
}
