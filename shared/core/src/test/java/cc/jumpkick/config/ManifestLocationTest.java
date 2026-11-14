// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import cc.jumpkick.run.SourceLocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A manifest that does not parse or validate says where: the file, and the line and column it can name. */
class ManifestLocationTest {

    private static final String HEAD = """
            group = "x.y"
            name = "app"
            version = "0.1.0"
            """;

    @Test
    void a_syntax_error_is_located_where_toml_stopped(@TempDir Path dir) throws Exception {
        Path file = write(dir, HEAD + "java = 25\n\n[dependencies]\nfoo = { group = \"a\" \n");
        SourceLocation at = locationOf(file);
        assertThat(at.file()).isEqualTo(file.toString());
        assertThat(at.line()).isGreaterThanOrEqualTo(7);
    }

    @Test
    void an_unknown_key_in_a_dependency_entry_is_located_at_the_key(@TempDir Path dir) throws Exception {
        Path file = write(
                dir,
                HEAD + "java = 25\n\n[dependencies]\nnope = { group = \"g\", artifact = \"a\", version = \"1\" }\n");
        assertThat(locationOf(file)).isEqualTo(new SourceLocation(file.toString(), 7, 23));
    }

    @Test
    void a_value_of_the_wrong_type_is_located_at_its_key(@TempDir Path dir) throws Exception {
        Path file = write(dir, HEAD + "java = \"twenty-five\"\n");
        assertThat(locationOf(file)).isEqualTo(new SourceLocation(file.toString(), 4, 1));
    }

    @Test
    void a_check_that_names_no_key_is_the_whole_file(@TempDir Path dir) throws Exception {
        Path file = write(dir, "group = \"x.y\"\nversion = \"0.1.0\"\n");
        assertThat(locationOf(file)).isEqualTo(SourceLocation.of(file));
    }

    @Test
    void a_dependency_is_located_at_its_key_in_the_member_that_declares_it(@TempDir Path dir) throws Exception {
        write(dir, """
                group = "x.y"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["app"]
                """);
        Path member = Files.createDirectories(dir.resolve("app"));
        Path memberFile = write(
                member, HEAD + "java = 25\n\n[dependencies]\nlib = { group = \"com.example\", version = \"1.0\" }\n");

        assertThat(ManifestLocator.dependency(dir, List.of("absent:thing", "com.example:lib")))
                .isEqualTo(new SourceLocation(memberFile.toString(), 7, 1));
        assertThat(ManifestLocator.dependency(dir, List.of("absent:thing"))).isNull();
    }

    private static SourceLocation locationOf(Path file) {
        JkBuildParseException e = catchThrowableOfType(JkBuildParseException.class, () -> JkBuildParser.parse(file));
        assertThat(e).as("the manifest is refused").isNotNull();
        SourceLocation at = e.location();
        assertThat(at).as("the refusal is located").isNotNull();
        return Objects.requireNonNull(at);
    }

    private static Path write(Path dir, String text) throws Exception {
        Path file = dir.resolve("jk.toml").toAbsolutePath().normalize();
        Files.writeString(file, text);
        return file;
    }
}
