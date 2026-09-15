// SPDX-License-Identifier: Apache-2.0

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JkTreeVersionTest {

    @Test
    fun reads_the_root_table_version_and_ignores_a_workspace_member_s(@TempDir root: Path) {
        Files.writeString(
            root.resolve("jk.toml"),
            """
            group = "cc.jumpkick"
            name = "jk"
            version = "1.4.0"
            java = 25

            [workspace]
            version = "9.9.9"
            """
                .trimIndent(),
        )

        assertThat(JkTreeVersion.of(root.toFile())).isEqualTo("1.4.0")
    }

    @Test
    fun a_manifest_without_a_version_is_refused_rather_than_defaulted(@TempDir root: Path) {
        Files.writeString(root.resolve("jk.toml"), "name = \"jk\"\n[workspace]\nversion = \"1.0.0\"\n")

        assertThatThrownBy { JkTreeVersion.of(root.toFile()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("declares no `version")
    }

    @Test
    fun a_missing_manifest_names_where_the_version_lives(@TempDir root: Path) {
        assertThatThrownBy { JkTreeVersion.of(root.toFile()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("jk.toml")
    }
}
