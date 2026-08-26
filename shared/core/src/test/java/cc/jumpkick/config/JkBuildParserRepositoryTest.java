// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.JkBuild;
import org.junit.jupiter.api.Test;

class JkBuildParserRepositoryTest {

    @Test
    void parses_repositories_string_and_table_form() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                internal = { url = "https://nexus.example/repository/maven-releases/" }
                """);
        assertThat(parsed.repositories()).extracting(r -> r.name()).containsExactlyInAnyOrder("central", "internal");
        // No inline credential on either repo.
        assertThat(parsed.repositories())
                .allSatisfy(r -> assertThat(r.credential()).isEmpty());
    }

    @Test
    void parses_repository_exclusive_groups() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.central]
                url = "https://repo.maven.apache.org/maven2/"

                [repositories.internal]
                url = "https://repo.acme.com/maven"
                groups = ["com.acme", "com.acme.*"]
                """);
        var internal = parsed.repositories().stream()
                .filter(r -> r.name().equals("internal"))
                .findFirst()
                .orElseThrow();
        assertThat(internal.groups()).containsExactly("com.acme", "com.acme.*");
        var central = parsed.repositories().stream()
                .filter(r -> r.name().equals("central"))
                .findFirst()
                .orElseThrow();
        assertThat(central.groups()).isEmpty();
    }

    @Test
    void repositories_jk_local_is_reserved() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [repositories]
                jk-local = "https://example.invalid/maven/"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.jk-local is reserved")
                .hasMessageContaining("repos/jk-local");
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [repositories.jk-local]
                url = "file:///tmp/repo"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.jk-local is reserved");
    }

    @Test
    void parses_inline_token_and_basic_credentials() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.ghp]
                url = "https://maven.pkg.github.com/JumpKickOSS/jk"
                token = "ghp_literaltoken"

                [repositories.nexus]
                url = "https://nexus.example/repository/maven-releases/"
                username = "deployer"
                password = "s3cr3t"
                """);

        var ghp = parsed.repositories().stream()
                .filter(r -> r.name().equals("ghp"))
                .findFirst()
                .orElseThrow();
        assertThat(ghp.credential()).contains(new RepoCredential.Bearer("ghp_literaltoken"));

        var nexus = parsed.repositories().stream()
                .filter(r -> r.name().equals("nexus"))
                .findFirst()
                .orElseThrow();
        assertThat(nexus.credential()).contains(new RepoCredential.Basic("deployer", "s3cr3t"));
    }

    @Test
    void parses_object_store_config_for_s3_repo() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.s3-releases]
                url = "s3://acme-artifacts/releases"
                region = "us-west-2"
                endpoint = "https://minio.internal:9000"
                access-key = "AKID"
                secret-key = "SEKRIT"
                """);
        var spec = parsed.repositories().get(0);
        assertThat(spec.objectStore()).hasValueSatisfying(c -> {
            assertThat(c.region()).isEqualTo("us-west-2");
            assertThat(c.endpoint()).isEqualTo("https://minio.internal:9000");
            assertThat(c.accessKey()).isEqualTo("AKID");
            assertThat(c.secretKey()).isEqualTo("SEKRIT");
            assertThat(c.hasExplicitCredentials()).isTrue();
        });
    }

    @Test
    void object_store_config_absent_when_no_keys() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.plain]
                url = "https://repo.example/maven"
                """);
        assertThat(parsed.repositories().get(0).objectStore()).isEmpty();
    }

    @Test
    void object_store_keys_keep_their_raw_env_references() {
        // Same contract as credentialsraw out of the parse, expanded by RepoGroupBuilder
        // where the request's environment is in scope. Object-store keys are secrets, so they must
        // not be committed literally — but the parse is not the place to resolve them.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.s3]
                url = "s3://bucket/maven"
                access-key = "${AWS_KEY}"
                secret-key = "literal-secret"
                """);
        assertThat(parsed.repositories().get(0).objectStore()).hasValueSatisfying(c -> {
            assertThat(c.accessKey()).isEqualTo("${AWS_KEY}");
            assertThat(c.secretKey()).isEqualTo("literal-secret");
        });
    }

    @Test
    void an_inline_credential_keeps_its_raw_env_reference() {
        // The parse deliberately does NOT interpolateit stays a pure function of the
        // file's bytes, so the memo needs no environment in its key and the engine cannot
        // accidentally resolve against the daemon's environment instead of the caller's.
        // Expansion — and its strictness — is RepoCredentialResolver's job; see
        // RepoCredentialResolverTest.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.r]
                url = "https://nexus.example/repo/"
                token = "${PATH}"
                """);
        assertThat(parsed.repositories().get(0).credential()).contains(new RepoCredential.Bearer("${PATH}"));
    }

    @Test
    void an_unset_env_var_no_longer_fails_the_parse() {
        // It fails when the repository is USED, not when a manifest merely mentions it — so a
        // manifest may reference a private mirror this machine has no credentials for.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.r]
                url = "https://nexus.example/repo/"
                token = "${JK_DEFINITELY_UNSET_VAR_XYZ}"
                """);
        assertThat(parsed.repositories().get(0).credential())
                .contains(new RepoCredential.Bearer("${JK_DEFINITELY_UNSET_VAR_XYZ}"));
    }
}
