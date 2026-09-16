// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.JkBuild;
import java.util.List;
import org.assertj.core.groups.Tuple;
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
                .allSatisfy(r -> assertThat(r.credentialOpt()).isEmpty());
    }

    /** A quoted table key with dots names one repository: {@code [repositories."nexus.internal"]}. */
    @Test
    void a_quoted_dotted_repository_name_is_one_repository() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories."nexus.internal"]
                url = "https://nexus.internal/repository/maven-releases/"
                """);
        assertThat(parsed.repositories())
                .extracting(r -> r.name(), r -> r.url().toString())
                .containsExactly(Tuple.tuple("nexus.internal", "https://nexus.internal/repository/maven-releases/"));
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
    void a_plaintext_http_repository_is_refused_by_name_unless_it_allows_insecure() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [repositories.mirror]
                url = "http://nexus.corp.example/maven"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.mirror uses plaintext http:// (http://nexus.corp.example/maven)")
                .hasMessageContaining("allow-insecure = true");
        // The string form has nowhere to put the key, so it is refused the same way.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [repositories]
                mirror = "http://nexus.corp.example/maven"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.mirror uses plaintext http://");

        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.mirror]
                url = "http://nexus.corp.example/maven"
                allow-insecure = true
                """);
        assertThat(parsed.repositories().get(0).allowInsecure()).isTrue();
        assertThat(parsed.repositories().get(0).allowUnverified()).isFalse();
    }

    @Test
    void allow_unverified_is_parsed_and_defaults_to_false() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.legacy]
                url = "https://old.example/maven"
                allow-unverified = true

                [repositories.plain]
                url = "https://repo.example/maven"
                """);
        assertThat(parsed.repositories())
                .extracting(r -> r.name(), r -> r.allowUnverified())
                .containsExactly(Tuple.tuple("legacy", true), Tuple.tuple("plain", false));
    }

    @Test
    void the_release_and_snapshot_policies_default_to_maven_s_and_a_repository_must_serve_something() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories]
                plain = "https://repo.example/maven"
                nightly = { url = "https://repo.example/snapshots", releases = false }
                stable = { url = "https://repo.example/releases", snapshots = false }
                """);
        assertThat(parsed.repositories())
                .extracting(r -> r.name(), r -> r.releases(), r -> r.snapshots())
                .containsExactly(
                        Tuple.tuple("plain", true, true),
                        Tuple.tuple("nightly", false, true),
                        Tuple.tuple("stable", true, false));
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [repositories.idle]
                url = "https://repo.example/maven"
                releases = false
                snapshots = false
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.idle serves neither releases nor snapshots");
    }

    @Test
    void the_trust_keys_are_refused_on_central() {
        for (String key : List.of("allow-insecure", "allow-unverified")) {
            assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                    [repositories.central]
                    url = "https://repo.maven.apache.org/maven2/"
                    %s = true
                    """.formatted(key)))
                    .as(key)
                    .isInstanceOf(JkBuildParseException.class)
                    .hasMessageContaining("repositories.central is Maven Central")
                    .hasMessageContaining("allow-insecure and allow-unverified are not accepted on it");
        }
    }

    @Test
    void a_trust_key_must_be_a_boolean() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [repositories.mirror]
                url = "https://repo.example/maven"
                allow-unverified = "yes"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.mirror.allow-unverified must be true or false");
    }

    @Test
    void an_unknown_repository_key_fails_the_parse() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [repositories.mirror]
                url = "https://repo.example/maven"
                allow-insecrue = true
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.mirror unknown key `allow-insecrue`")
                .hasMessageContaining("allow-insecure");
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
        assertThat(ghp.credentialOpt()).contains(new RepoCredential.Bearer("ghp_literaltoken"));

        var nexus = parsed.repositories().stream()
                .filter(r -> r.name().equals("nexus"))
                .findFirst()
                .orElseThrow();
        assertThat(nexus.credentialOpt()).contains(new RepoCredential.Basic("deployer", "s3cr3t"));
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
        assertThat(spec.objectStoreOpt()).hasValueSatisfying(c -> {
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
        assertThat(parsed.repositories().get(0).objectStoreOpt()).isEmpty();
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
        assertThat(parsed.repositories().get(0).objectStoreOpt()).hasValueSatisfying(c -> {
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
        assertThat(parsed.repositories().get(0).credentialOpt()).contains(new RepoCredential.Bearer("${PATH}"));
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
        assertThat(parsed.repositories().get(0).credentialOpt())
                .contains(new RepoCredential.Bearer("${JK_DEFINITELY_UNSET_VAR_XYZ}"));
    }

    @Test
    void a_plaintext_repository_on_loopback_needs_no_opt_in() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """
                [repositories.local]
                url = "http://127.0.0.1:8081/maven"
                [repositories.tunnel]
                url = "http://localhost:8082/maven"
                [repositories.six]
                url = "http://[::1]:8083/maven"
                """);
        assertThat(b.repositories()).extracting(r -> r.name()).contains("local", "tunnel", "six");
        assertThat(RepositoryToml.loopback("10.0.0.7")).isFalse();
        assertThat(RepositoryToml.loopback("nexus.corp.example")).isFalse();
    }
}
