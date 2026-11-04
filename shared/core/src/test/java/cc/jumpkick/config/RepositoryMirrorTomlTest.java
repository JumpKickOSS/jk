// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.MirrorOf;
import cc.jumpkick.model.RepositoryMirror;
import cc.jumpkick.model.RepositorySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code [repositories.<id>]} table that carries {@code mirror-of} is a mirror: read from {@code
 * ~/.jk/config.toml} alone, strictly, and never as a repository; in a project's {@code jk.toml} it
 * is a parse error.
 */
class RepositoryMirrorTomlTest {

    private static Path config(Path dir, String body) throws IOException {
        Path file = dir.resolve("config.toml");
        Files.writeString(file, body);
        return file;
    }

    @Test
    void a_mirror_table_is_read_as_a_mirror_in_file_order_and_never_as_a_repository(@TempDir Path dir)
            throws IOException {
        Path file = config(dir, """
                [repositories.corp]
                url = "https://artifactory.acme.test/maven-virtual/"
                mirror-of = ["*", "!jumpkick"]
                token = "${JK_REPO_CORP_TOKEN_UNSET_IN_TESTS}"

                [repositories.internal]
                url = "https://repo.acme.test/internal/"
                groups = ["com.acme", "com.acme.*"]

                [repositories.plain]
                url = "http://nexus.acme.test/m2/"
                mirror-of = ["central"]
                allow-insecure = true
                """);

        List<RepositoryMirror> mirrors = GlobalConfig.repositoryMirrors(file);

        assertThat(mirrors).extracting(RepositoryMirror::id).containsExactly("corp", "plain");
        RepositoryMirror corp = mirrors.getFirst();
        assertThat(corp.url()).hasToString("https://artifactory.acme.test/maven-virtual/");
        assertThat(corp.mirrorOf()).isEqualTo(new MirrorOf(List.of("*", "!jumpkick")));
        assertThat(corp.credential()).isEqualTo(new RepoCredential.Bearer("${JK_REPO_CORP_TOKEN_UNSET_IN_TESTS}"));
        assertThat(corp.label()).isEqualTo("mirror `corp` (" + file + ")");
        assertThat(mirrors.get(1).allowInsecure()).isTrue();
        assertThat(GlobalConfig.repositories(file))
                .extracting(RepositorySpec::name)
                .containsExactly("internal");
    }

    @Test
    void a_mirror_in_a_project_manifest_is_a_parse_error_pointing_at_the_user_config() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                        name = "demo"

                        [repositories.corp]
                        url = "https://artifactory.acme.test/maven-virtual/"
                        mirror-of = ["*"]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.corp.mirror-of is not accepted in jk.toml")
                .hasMessageContaining("~/.jk/config.toml");
    }

    @Test
    void a_key_only_a_repository_takes_fails_the_mirror_naming_it_and_the_file(@TempDir Path dir) throws IOException {
        Path file = config(dir, """
                [repositories.corp]
                url = "https://artifactory.acme.test/maven-virtual/"
                mirror-of = ["*"]
                groups = ["com.acme"]
                """);

        assertThatThrownBy(() -> GlobalConfig.repositoryMirrors(file))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.corp in " + file)
                .hasMessageContaining("`groups` belongs on a repository");
    }

    @Test
    void a_plaintext_mirror_on_a_network_path_needs_allow_insecure(@TempDir Path dir) throws IOException {
        Path file = config(dir, """
                [repositories.corp]
                url = "http://nexus.acme.test/m2/"
                mirror-of = ["*"]
                """);

        assertThatThrownBy(() -> GlobalConfig.repositoryMirrors(file))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("plaintext http://")
                .hasMessageContaining("allow-insecure = true");
    }

    @Test
    void mirror_of_is_a_non_empty_list_of_ids(@TempDir Path dir) throws IOException {
        Path file = config(dir, """
                [repositories.corp]
                url = "https://artifactory.acme.test/maven-virtual/"
                mirror-of = "*"
                """);

        assertThatThrownBy(() -> GlobalConfig.repositoryMirrors(file))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("mirror-of must be a non-empty array");
    }

    @Test
    void a_mirror_binds_its_id_to_its_url_for_the_credential_resolver() {
        RepositoryMirror corp = new RepositoryMirror(
                "corp",
                URI.create("https://artifactory.acme.test/maven-virtual/"),
                new MirrorOf(List.of("*")),
                new RepoCredential.Bearer("${JK_REPO_CORP_TOKEN}"),
                false,
                Path.of("config.toml"));

        assertThat(corp.declaration())
                .isEqualTo(new RepositorySpec(
                        "corp",
                        URI.create("https://artifactory.acme.test/maven-virtual/"),
                        new RepoCredential.Bearer("${JK_REPO_CORP_TOKEN}")));
    }
}
