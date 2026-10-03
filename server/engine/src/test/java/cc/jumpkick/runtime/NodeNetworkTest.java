// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.node.NodeSources;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** What a node step's package manager is handed: a per-run user config and the proxy variables. */
class NodeNetworkTest {

    private static final NodeSources.Origin REGISTRY =
            new NodeSources.Origin(URI.create("https://nexus.corp/npm/"), "nexus.corp");
    private static final NodeSources.Origin ACME =
            new NodeSources.Origin(URI.create("https://acme.corp/npm-acme/"), "acme.corp");

    @TempDir
    Path dir;

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void the_registry_scopes_and_tokens_go_into_an_owner_only_user_config_after_the_user_s_own() throws Exception {
        Path own = Files.writeString(dir.resolve("user.npmrc"), "fund=false");
        NodeNetwork.Sources sources = new NodeNetwork.Sources(
                REGISTRY,
                Map.of("@acme", ACME),
                origin -> origin.equals(REGISTRY)
                        ? new RepoCredential.Bearer("reg-token")
                        : new RepoCredential.Basic("u", "p"),
                uri -> Optional.empty(),
                List.of(),
                own);

        Map<String, String> env = NodeNetwork.env(sources, dir.resolve("work"));

        Path file = Path.of(env.get(NodeNetwork.USERCONFIG));
        assertThat(file.getFileName().toString()).startsWith(NodeNetwork.USERCONFIG_PREFIX);
        assertThat(Files.readString(file)).isEqualTo("""
                        fund=false
                        registry=https://nexus.corp/npm/
                        //nexus.corp/npm/:_authToken=reg-token
                        @acme:registry=https://acme.corp/npm-acme/
                        //acme.corp/npm-acme/:_auth=dTpw
                        """);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
        assertThat(env)
                .containsEntry("YARN_NPM_REGISTRY_SERVER", "https://nexus.corp/npm")
                .containsEntry("YARN_NPM_AUTH_TOKEN", "reg-token");

        NodeNetwork.discard(env);
        assertThat(file).doesNotExist();
    }

    @Test
    void the_public_registry_with_no_credential_writes_nothing() throws Exception {
        NodeNetwork.Sources sources = new NodeNetwork.Sources(
                new NodeSources.Origin(URI.create(NodeSources.NPM_REGISTRY), "registry.npmjs.org"),
                Map.of(),
                origin -> RepoCredential.ANONYMOUS,
                uri -> Optional.empty(),
                List.of(),
                null);

        assertThat(NodeNetwork.env(sources, dir.resolve("work"))).isEmpty();
        assertThat(dir.resolve("work")).doesNotExist();
    }

    @Test
    void the_proxy_reaches_every_manager_s_own_variables() {
        Map<String, String> vars = NodeNetwork.proxy(
                uri -> "https".equals(uri.getScheme()) ? Optional.of("http://u:p@proxy:3128") : Optional.empty(),
                List.of("intranet", ".corp"),
                REGISTRY.url());

        assertThat(vars)
                .containsEntry("npm_config_https_proxy", "http://u:p@proxy:3128")
                .containsEntry("YARN_HTTPS_PROXY", "http://u:p@proxy:3128")
                .containsEntry("HTTPS_PROXY", "http://u:p@proxy:3128")
                .containsEntry("npm_config_noproxy", "intranet,.corp")
                .containsEntry("NO_PROXY", "intranet,.corp")
                .doesNotContainKey("npm_config_proxy");
    }

    @Test
    void the_install_key_reads_where_packages_come_from_and_never_a_credential() {
        Map<String, String> keyed = NodeNetwork.keyed(REGISTRY, Map.of("@acme", ACME));

        assertThat(keyed)
                .containsEntry("JK_NODE_REGISTRY", "https://nexus.corp/npm/")
                .containsEntry("JK_NODE_SCOPES", "@acme=https://acme.corp/npm-acme/;");
        assertThat(String.join(" ", keyed.values())).doesNotContain("token").doesNotContain("_auth");
    }
}
