// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pure parse / path disambiguation for {@code jk add} / {@code jk remove} (no engine). */
class AddRemoveParseTest {

    @Test
    void isLocalPathArg_bare_name_depends_on_directory(@TempDir Path tmp) throws Exception {
        assertThat(AddCommand.isLocalPathArg("mylib", tmp)).isFalse();
        Files.createDirectory(tmp.resolve("mylib"));
        assertThat(AddCommand.isLocalPathArg("mylib", tmp)).isTrue();
    }

    @Test
    void isLocalPathArg_explicit_path_and_markers() {
        Path cwd = Path.of(".");
        assertThat(AddCommand.isLocalPathArg("./m", cwd)).isTrue();
        assertThat(AddCommand.isLocalPathArg("m/", cwd)).isTrue();
        assertThat(AddCommand.isLocalPathArg(":m", cwd)).isTrue();
        assertThat(AddCommand.isLocalPathArg("m@1.0", cwd)).isFalse();
        assertThat(AddCommand.isLocalPathArg("g:a", cwd)).isFalse();
        assertThat(AddCommand.isLocalPathArg("g:a:1.0", cwd)).isFalse();
    }

    @Test
    void parsedDep_at_version_preserves_selectors() {
        var p = AddCommand.ParsedDep.parse("jackson3-core@=3.1.0", null, null, null, null);
        assertThat(p.library()).isEqualTo("jackson3-core");
        assertThat(p.versionLiteral()).isEqualTo("=3.1.0");
        assertThat(p.group()).isNotBlank();

        var caret = AddCommand.ParsedDep.parse("jackson3-core@^3.1", null, null, null, null);
        assertThat(caret.versionLiteral()).isEqualTo("^3.1");
    }

    @Test
    void parsedDep_group_artifact_forms() {
        var latest = AddCommand.ParsedDep.parse("com.foo:bar", null, null, null, null);
        assertThat(latest.group()).isEqualTo("com.foo");
        assertThat(latest.name()).isEqualTo("bar");
        assertThat(latest.versionLiteral()).isEqualTo("latest");
        assertThat(latest.floating()).isTrue();

        var emptyVer = AddCommand.ParsedDep.parse("com.foo:bar:", null, null, null, null);
        assertThat(emptyVer.versionLiteral()).isEqualTo("latest");
        assertThat(emptyVer.floating()).isTrue();

        var pinned = AddCommand.ParsedDep.parse("com.foo:bar:1.2.3", null, null, null, null);
        assertThat(pinned.versionLiteral()).isEqualTo("=1.2.3");
        assertThat(pinned.floating()).isFalse();
    }

    @Test
    void remove_shortNameOf_strips_version_and_paths(@TempDir Path tmp) throws Exception {
        assertThat(RemoveCommand.shortNameOf("bar", tmp)).isEqualTo("bar");
        assertThat(RemoveCommand.shortNameOf("bar@1.2.3", tmp)).isEqualTo("bar");
        assertThat(RemoveCommand.shortNameOf("bar@=1.2.3", tmp)).isEqualTo("bar");
        assertThat(RemoveCommand.shortNameOf("com.foo:bar", tmp)).isEqualTo("bar");
        assertThat(RemoveCommand.shortNameOf("com.foo:bar:9.9", tmp)).isEqualTo("bar");

        Path lib = tmp.resolve("libb");
        Files.createDirectories(lib);
        Files.writeString(
                lib.resolve("jk.toml"),
                """
                [project]
                group = "g"
                name = "libb"
                version = "1.0.0"
                """);
        assertThat(RemoveCommand.shortNameOf("./libb", tmp)).isEqualTo("libb");
        assertThat(RemoveCommand.shortNameOf("libb/", tmp)).isEqualTo("libb");
        assertThat(RemoveCommand.shortNameOf("libb", tmp)).isEqualTo("libb"); // dir exists → path

        assertThatThrownBy(() -> RemoveCommand.shortNameOf("@1.0", tmp))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
