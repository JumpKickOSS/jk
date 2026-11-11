// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.host.OwnerOnlyFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.tomlj.Toml;

/**
 * {@code ${env:NAME}}, {@code ${file:path}} and {@code ${file:path#key}}: how each is spelled, what
 * it expands to, and where a project manifest may not write one.
 */
class FileReferencesTest {

    @AfterEach
    void forget() {
        SecretFiles.clear();
    }

    private static Path secret(Path dir, String name, String body) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, body);
        OwnerOnlyFiles.setOwnerOnly(file, "rw-------");
        return file;
    }

    private static ReferenceResolver resolver(Path baseDir, Map<String, String> env) {
        return new ReferenceResolver(ReferenceResolver.strict(env::get, "test"), () -> baseDir);
    }

    @Test
    void each_spelling_parses_to_its_scheme_and_body() {
        List<ConfigReference> refs = ConfigReference.all("${A}-${env:B}-${file:~/x}-${file: rel/y.properties#k.v }");

        assertThat(refs)
                .extracting(ConfigReference::scheme, ConfigReference::body)
                .containsExactly(
                        tuple(ConfigReference.Scheme.ENV, "A"),
                        tuple(ConfigReference.Scheme.ENV, "B"),
                        tuple(ConfigReference.Scheme.FILE, "~/x"),
                        tuple(ConfigReference.Scheme.FILE, "rel/y.properties#k.v"));
        assertThat(Interpolation.references("${A}-${env:B}-${file:x}")).containsExactly("A", "B");
    }

    @Test
    void env_is_the_explicit_spelling_of_a_bare_name_everywhere_env_expands() {
        assertThat(RepositoryToml.interpolate(
                        "${env:TOK}/${TOK}", RepositoryToml.VarPolicy.STRICT, "x", Map.of("TOK", "t")::get))
                .isEqualTo("t/t");
    }

    @Test
    void env_only_expansion_leaves_a_file_reference_for_the_resolver() {
        assertThat(RepositoryToml.interpolate(
                        "${file:/s}", RepositoryToml.VarPolicy.LENIENT, "x", Map.<String, String>of()::get))
                .isEqualTo("${file:/s}");
    }

    @Test
    void a_file_reference_expands_to_the_whole_file_without_its_trailing_newline(@TempDir Path dir) throws IOException {
        Path token = secret(dir, "token", "s3cret-token\n");

        String out = resolver(dir, Map.of())
                .expand(
                        "Bearer ${file:" + token + "}",
                        "repositories.corp.token",
                        ReferenceResolver.Origin.USER_CONFIG);

        assertThat(out).isEqualTo("Bearer s3cret-token");
    }

    @Test
    void a_key_reference_reads_one_property_and_a_relative_path_is_under_the_declaring_files_directory(
            @TempDir Path dir) throws IOException {
        secret(dir, "secrets.properties", "nexus.user = alice\nnexus.password=pa55word\n");

        String out = resolver(dir, Map.of())
                .expand(
                        "${file:secrets.properties#nexus.password}",
                        "repositories.corp.password",
                        ReferenceResolver.Origin.USER_CONFIG);

        assertThat(out).isEqualTo("pa55word");
    }

    @Test
    void a_missing_key_is_an_error_naming_the_position_the_file_and_the_key(@TempDir Path dir) throws IOException {
        Path props = secret(dir, "secrets.properties", "other=1\n");

        assertThatThrownBy(() -> resolver(dir, Map.of())
                        .expand(
                                "${file:" + props + "#nexus.password}",
                                "repositories.corp.password",
                                ReferenceResolver.Origin.USER_CONFIG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageStartingWith("repositories.corp.password")
                .hasMessageContaining(props.toString())
                .hasMessageContaining("nexus.password");
    }

    @Test
    void a_missing_file_is_an_error_naming_the_position_and_the_path(@TempDir Path dir) {
        Path absent = dir.resolve("nope");

        assertThatThrownBy(() -> resolver(dir, Map.of())
                        .expand(
                                "${file:" + absent + "}",
                                "repositories.corp.token",
                                ReferenceResolver.Origin.USER_CONFIG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageStartingWith("repositories.corp.token")
                .hasMessageContaining(absent + ", which does not exist");
    }

    /** Windows has no group or other read bit, so jk checks nothing there. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_file_its_group_or_others_can_read_is_refused_naming_chmod_600(@TempDir Path dir) throws IOException {
        Path token = secret(dir, "token", "s3cret");
        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-r--r--"));

        assertThatThrownBy(() -> resolver(dir, Map.of())
                        .expand(
                                "${file:" + token + "}",
                                "repositories.corp.token",
                                ReferenceResolver.Origin.USER_CONFIG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("chmod 600 " + token);
    }

    @Test
    void a_rotated_file_is_read_again_on_the_next_resolve(@TempDir Path dir) throws IOException {
        Path token = secret(dir, "token", "first-token");
        ReferenceResolver r = resolver(dir, Map.of());
        String ref = "${file:" + token + "}";
        assertThat(r.expand(ref, "w", ReferenceResolver.Origin.USER_CONFIG)).isEqualTo("first-token");

        Files.writeString(token, "second-token-longer");
        Files.setLastModifiedTime(token, FileTime.from(Instant.now().plusSeconds(5)));

        assertThat(r.expand(ref, "w", ReferenceResolver.Origin.USER_CONFIG)).isEqualTo("second-token-longer");
    }

    @Test
    void a_project_value_may_not_read_a_file(@TempDir Path dir) throws IOException {
        Path token = secret(dir, "token", "s3cret");

        assertThatThrownBy(() -> resolver(dir, Map.of())
                        .expand("${file:" + token + "}", "repositories.corp.token", ReferenceResolver.Origin.PROJECT))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("only in ~/.jk/config.toml");
    }

    @Test
    void a_file_reference_in_a_manifest_repository_credential_passes_the_parse() {
        Interpolation.guard(Toml.parse("""
                [repositories.corp]
                url = "https://corp.example/maven/"
                token = "${file:~/.secrets/corp}"
                """));
    }

    @Test
    void a_file_reference_anywhere_else_in_a_manifest_is_a_parse_error() {
        assertThatThrownBy(() -> Interpolation.guard(Toml.parse("""
                        [[test.env]]
                        TOKEN = "${file:/etc/shadow}"
                        """)))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("${file:/etc/shadow}")
                .hasMessageContaining("only in ~/.jk/config.toml");
        assertThatThrownBy(() -> Interpolation.guard(Toml.parse("version = \"${env:V}\"")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("version (${env:V})");
    }
}
