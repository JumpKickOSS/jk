// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.repo.RepoCredentialResolver;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where Node and npm packages come from: the environment over {@code ~/.jk/config.toml} over a
 * Maven {@code settings.xml} mirror over the public default, and the credential each origin carries.
 */
class NodeSourcesTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().withoutChecksums();

    @TempDir
    Path dir;

    @Test
    void the_environment_wins_then_the_config_file_then_a_settings_xml_mirror_then_the_default() throws Exception {
        MavenSettings maven = settings("""
                <settings><mirrors>
                  <mirror><id>corp-npm</id><mirrorOf>npm</mirrorOf><url>https://nexus.corp/npm/</url></mirror>
                  <mirror><id>corp-node</id><mirrorOf>nodejs,central</mirrorOf><url>https://nexus.corp/node</url></mirror>
                  <mirror><id>all</id><mirrorOf>*</mirrorOf><url>https://nexus.corp/maven/</url></mirror>
                </mirrors></settings>
                """);
        GlobalConfig.NodeSources none = GlobalConfig.NodeSources.EMPTY;
        GlobalConfig.NodeSources file =
                new GlobalConfig.NodeSources("https://file.corp/dist", "https://file.corp/npm/", Map.of());
        Map<String, String> env = Map.of(NodeSources.REGISTRY_ENV, "https://env.corp/npm");

        NodeSources.Inputs fromEnv = new NodeSources.Inputs(env::get, file, maven);
        assertThat(NodeSources.npmRegistry(fromEnv).url()).hasToString("https://env.corp/npm/");
        assertThat(NodeSources.dist(fromEnv).url()).hasToString("https://file.corp/dist/");

        NodeSources.Inputs fromSettings = new NodeSources.Inputs(name -> null, none, maven);
        assertThat(NodeSources.npmRegistry(fromSettings))
                .isEqualTo(new NodeSources.Origin(URI.create("https://nexus.corp/npm/"), "corp-npm"));
        assertThat(NodeSources.dist(fromSettings))
                .isEqualTo(new NodeSources.Origin(URI.create("https://nexus.corp/node/"), "corp-node"));

        NodeSources.Inputs nothing = new NodeSources.Inputs(name -> null, none, MavenSettings.empty());
        assertThat(NodeSources.npmRegistry(nothing).url()).hasToString(NodeSources.NPM_REGISTRY);
        assertThat(NodeSources.npmRegistry(nothing).credentialId()).isEqualTo("registry.npmjs.org");
        assertThat(NodeSources.dist(nothing).url()).hasToString(NodeSources.NODEJS_DIST);
    }

    @Test
    void config_values_expand_variables_and_an_unset_one_is_named() {
        Map<String, String> env = Map.of("NEXUS", "https://nexus.corp");
        GlobalConfig.NodeSources file =
                new GlobalConfig.NodeSources(null, "${NEXUS}/npm/", Map.of("@acme", "${NEXUS}/npm-acme"));
        NodeSources.Inputs in = new NodeSources.Inputs(env::get, file, MavenSettings.empty());

        assertThat(NodeSources.npmRegistry(in).url()).hasToString("https://nexus.corp/npm/");
        assertThat(NodeSources.scopes(in))
                .containsExactly(Map.entry(
                        "@acme", new NodeSources.Origin(URI.create("https://nexus.corp/npm-acme/"), "nexus.corp")));

        NodeSources.Inputs unset = new NodeSources.Inputs(name -> null, file, MavenSettings.empty());
        assertThatThrownBy(() -> NodeSources.npmRegistry(unset))
                .hasMessageContaining("[node] registry")
                .hasMessageContaining("${NEXUS}");
    }

    @Test
    void an_origin_s_credential_is_sent_to_that_origin_alone() {
        NodeSources.Origin registry =
                new NodeSources.Origin(URI.create("https://nexus.corp:8443/npm/"), "nexus.corp:8443");
        Map<String, String> env = Map.of("JK_REPO_NEXUS_CORP_8443_TOKEN", "t0ken");
        Function<URI, Optional<String>> auth = NodeSources.authorization(List.of(registry), resolver(env::get));

        assertThat(auth.apply(URI.create("https://nexus.corp:8443/npm/left-pad/-/left-pad-1.0.0.tgz")))
                .contains("Bearer t0ken");
        assertThat(auth.apply(URI.create("https://nexus.corp:8443/maven/a.jar")))
                .as("another path on the host")
                .isEmpty();
        assertThat(auth.apply(URI.create("https://registry.npmjs.org/left-pad")))
                .isEmpty();
        assertThat(NodeSources.header(new RepoCredential.Basic("u", "p")))
                .contains("Basic " + Base64.getEncoder().encodeToString("u:p".getBytes(StandardCharsets.UTF_8)));
        assertThat(NodeSources.header(RepoCredential.ANONYMOUS)).isEmpty();
    }

    @Test
    void a_settings_xml_mirror_s_server_credential_authenticates_a_node_download() throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("index.json"))) {
            http.served().put("/node/index.json", in.readAllBytes());
        }
        URI base = http.base().resolve("/node/");
        MavenSettings maven = settings("""
                <settings>
                  <servers><server><id>corp-node</id><username>deploy</username><password>s3cret</password></server></servers>
                  <mirrors><mirror><id>corp-node</id><mirrorOf>nodejs</mirrorOf><url>%s</url></mirror></mirrors>
                </settings>
                """.formatted(base));
        NodeSources.Origin origin =
                NodeSources.dist(new NodeSources.Inputs(name -> null, GlobalConfig.NodeSources.EMPTY, maven));
        Http client =
                new Http().withAuthorization(NodeSources.authorization(List.of(origin), resolver(name -> null, maven)));

        List<NodeRelease> releases =
                new NodeCatalog(client, origin.url(), dir.resolve("store"), Duration.ofHours(1)).releases();

        assertThat(releases).isNotEmpty();
        assertThat(http.headersFor("/node/index.json"))
                .get()
                .extracting(h -> h.get("Authorization"))
                .isEqualTo(List.of("Basic "
                        + Base64.getEncoder().encodeToString("deploy:s3cret".getBytes(StandardCharsets.UTF_8))));
    }

    private MavenSettings settings(String xml) throws Exception {
        Path file = dir.resolve("settings.xml");
        Files.writeString(file, xml);
        return MavenSettings.loadFrom(file);
    }

    private static RepoCredentialResolver resolver(Function<String, @Nullable String> env) {
        return resolver(env, MavenSettings.empty());
    }

    private static RepoCredentialResolver resolver(Function<String, @Nullable String> env, MavenSettings maven) {
        return RepoCredentialResolver.withEnv(env, maven);
    }
}
