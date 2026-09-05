// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Identity keys: where they must sit, and which root keys the parser actually reserves. */
class ManifestIdentityKeysTest {

    private static Path manifest(Path dir, String toml) throws Exception {
        Path f = dir.resolve("jk.toml");
        Files.writeString(f, toml);
        return f;
    }

    @Test
    void a_name_stranded_under_a_table_is_named_with_the_table_it_fell_into(@TempDir Path dir) throws Exception {
        Path f = manifest(dir, """
                group = "com.acme"
                version = "1.0.0"

                [dependencies]
                name = "app"
                """);
        assertThatThrownBy(() -> JkBuildParser.parse(f))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("missing required key `name`")
                .hasMessageContaining("found `name` under [dependencies]")
                .hasMessageContaining("above the first table");
    }

    @Test
    void a_version_stranded_under_a_table_is_reported_the_same_way(@TempDir Path dir) throws Exception {
        Path f = manifest(dir, """
                group = "com.acme"
                name = "app"

                [dependencies]
                version = "2.0"
                """);
        assertThatThrownBy(() -> JkBuildParser.parse(f))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("missing required key `version`")
                .hasMessageContaining("found `version` under [dependencies]");
    }

    @Test
    void a_plainly_missing_key_carries_no_hint(@TempDir Path dir) throws Exception {
        Path f = manifest(dir, """
                group = "com.acme"
                version = "1.0.0"
                """);
        assertThatThrownBy(() -> JkBuildParser.parse(f))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("missing required key `name`")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("found `name`"));
    }

    @Test
    void id_and_module_are_not_reserved_root_keys() {
        assertThat(ManifestProject.PROJECT_KEYS).doesNotContain("id", "module");
    }

    @Test
    void an_id_or_module_table_is_diagnosed_as_unowned_rather_than_accepted(@TempDir Path dir) throws Exception {
        Path f = manifest(dir, """
                group = "com.acme"
                name = "app"
                version = "1.0.0"

                [module]
                kind = "library"
                """);
        assertThatThrownBy(() -> JkBuildParser.parse(f))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[module]");
    }
}
