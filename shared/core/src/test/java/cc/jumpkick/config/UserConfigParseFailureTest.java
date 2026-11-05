// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.RepositoryMirror;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code ~/.jk/config.toml} that is not valid TOML is never read as an empty file by the readers
 * that decide where a request goes or what it carries: each one raises the same typed error, with
 * the file and the position of the first mistake. The readers of local preferences stay lenient.
 */
class UserConfigParseFailureTest {

    /** A mirror table whose second table header is never closed: line 5, where the bracket is missing. */
    private static final String BROKEN = """
            [repositories.corp]
            url = "https://artifactory.acme.test/maven-virtual/"
            mirror-of = ["*"]

            [mirrors
            maven = "https://artifactory.acme.test/maven-dist/"
            """;

    private static final String FIXED = """
            [repositories.corp]
            url = "https://artifactory.acme.test/maven-virtual/"
            mirror-of = ["*"]

            [mirrors]
            maven = "https://artifactory.acme.test/maven-dist/"
            """;

    @BeforeEach
    @AfterEach
    void clean() {
        GlobalConfig.clearCache();
    }

    @Test
    void broken_syntax_is_a_typed_error_naming_the_file_line_and_column(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("config.toml"), BROKEN);

        assertThatThrownBy(() -> GlobalConfig.repositoryMirrors(file))
                .isInstanceOfSatisfying(UserConfigParseException.class, e -> {
                    assertThat(e.file()).isEqualTo(file);
                    assertThat(e.line()).isEqualTo(5);
                    assertThat(e.column()).isPositive();
                    assertThat(e.reason()).isNotBlank();
                    assertThat(e.getMessage())
                            .startsWith(file + ":5:" + e.column() + ": ")
                            .contains("reads no repositories, mirrors, proxies or credentials");
                });
        assertThat(GlobalConfig.userConfigProblem(file))
                .hasValueSatisfying(e -> assertThat(e.line()).isEqualTo(5));
    }

    @Test
    void every_reader_that_routes_a_request_refuses_the_file(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("config.toml"), BROKEN);

        assertThatThrownBy(() -> GlobalConfig.repositories(file)).isInstanceOf(UserConfigParseException.class);
        assertThatThrownBy(() -> GlobalConfig.repositoryMirrors(file)).isInstanceOf(UserConfigParseException.class);
        assertThatThrownBy(() -> GlobalConfig.mirrors(file)).isInstanceOf(UserConfigParseException.class);
        assertThatThrownBy(() -> GlobalConfig.nodeSources(file)).isInstanceOf(UserConfigParseException.class);
        assertThatThrownBy(() -> GlobalConfig.image(file)).isInstanceOf(UserConfigParseException.class);
        assertThatThrownBy(() -> GlobalConfig.network(file)).isInstanceOf(UserConfigParseException.class);
        assertThatThrownBy(() -> JkTemplatesConfig.resolve(file)).isInstanceOf(UserConfigParseException.class);
    }

    @Test
    void a_preference_reader_still_answers_so_help_and_doctor_run(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("config.toml"), "nerd-font = false\n" + BROKEN);

        assertThat(GlobalConfig.nerdFontMode(file, null, null)).isEqualTo(NerdFontMode.OFF);
        assertThat(EffectiveUserConfig.rows(file, name -> null))
                .filteredOn(row -> row.key().equals("config.parse-error"))
                .singleElement()
                .satisfies(row -> assertThat(row.effectiveValue()).startsWith(file + ":"));
    }

    @Test
    void an_absent_file_is_no_config_and_no_error(@TempDir Path dir) {
        Path file = dir.resolve("config.toml");

        assertThat(GlobalConfig.repositoryMirrors(file)).isEmpty();
        assertThat(GlobalConfig.userConfigProblem(file)).isEmpty();
        assertThat(EffectiveUserConfig.rows(file, name -> null))
                .noneMatch(row -> row.key().equals("config.parse-error"));
    }

    @Test
    void the_next_read_sees_the_file_as_it_is_now_in_both_directions(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("config.toml"), FIXED);
        assertThat(GlobalConfig.repositoryMirrors(file))
                .extracting(RepositoryMirror::id)
                .containsExactly("corp");

        Files.writeString(file, BROKEN);
        assertThatThrownBy(() -> GlobalConfig.repositoryMirrors(file))
                .as("a break is reported, not answered from the last good copy")
                .isInstanceOf(UserConfigParseException.class);

        Files.writeString(file, FIXED);
        assertThat(GlobalConfig.repositoryMirrors(file))
                .as("a fix needs no restart")
                .extracting(RepositoryMirror::id)
                .containsExactly("corp");
        assertThat(GlobalConfig.userConfigProblem(file)).isEmpty();
    }

    @Test
    void a_broken_file_does_not_read_as_an_unset_credentials_store(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("config.toml"), """
                [credentials]
                store = "keychain"

                [mirrors
                """);

        assertThatThrownBy(() -> GlobalConfig.credentialsStore(file)).isInstanceOf(UserConfigParseException.class);

        GlobalConfig.clearCache();
        Files.writeString(file, """
                [credentials]
                store = "keychain"
                """);
        assertThat(GlobalConfig.credentialsStore(file)).contains("keychain");
    }
}
