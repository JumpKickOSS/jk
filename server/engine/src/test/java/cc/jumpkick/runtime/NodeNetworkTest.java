// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.DownloadOrigin;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.node.NodeSources;
import cc.jumpkick.node.PackageManager;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** What a node step's package manager is handed: a per-run user config and the proxy variables. */
class NodeNetworkTest {

    private static final DownloadOrigin REGISTRY =
            new DownloadOrigin(URI.create("https://nexus.corp/npm/"), "nexus.corp");
    private static final DownloadOrigin ACME =
            new DownloadOrigin(URI.create("https://acme.corp/npm-acme/"), "acme.corp");

    @TempDir
    Path dir;

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void the_registry_scopes_and_tokens_go_into_an_owner_only_user_config_after_the_user_s_own() throws Exception {
        Path userHome = Files.createDirectories(dir.resolve("home"));
        Files.writeString(userHome.resolve(".npmrc"), "fund=false");
        NodeNetwork.Sources sources = new NodeNetwork.Sources(
                REGISTRY,
                Map.of("@acme", ACME),
                origin -> origin.equals(REGISTRY)
                        ? new RepoCredential.Bearer("reg-token")
                        : new RepoCredential.Basic("u", "p"),
                uri -> Optional.empty(),
                List.of(),
                userHome);

        Map<String, String> env = NodeNetwork.env(sources, dir.resolve("work"), PackageManager.NPM);

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
                .containsEntry("YARN_NPM_AUTH_TOKEN", "reg-token")
                .containsEntry("YARN_NPM_ALWAYS_AUTH", "true");

        NodeNetwork.discard(env);
        assertThat(file).doesNotExist();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void bun_gets_the_registry_scopes_and_credentials_as_an_owner_only_global_bunfig() throws Exception {
        NodeNetwork.Sources sources = new NodeNetwork.Sources(
                REGISTRY,
                Map.of("@acme", ACME),
                origin -> origin.equals(REGISTRY)
                        ? new RepoCredential.Bearer("reg-token")
                        : new RepoCredential.Basic("u", "p"),
                uri -> Optional.empty(),
                List.of(),
                null);

        Map<String, String> env = NodeNetwork.env(sources, dir.resolve("work"), PackageManager.BUN);

        assertThat(env).doesNotContainKey(NodeNetwork.USERCONFIG);
        Path home = Path.of(env.get(NodeNetwork.BUN_CONFIG_HOME));
        assertThat(home.getFileName().toString()).startsWith(NodeNetwork.BUN_CONFIG_PREFIX);
        Path bunfig = home.resolve(".bunfig.toml");
        assertThat(Files.readString(bunfig)).isEqualTo("""
                        [install]
                        registry = { url = "https://nexus.corp/npm/", token = "reg-token" }

                        [install.scopes]
                        "@acme" = { url = "https://acme.corp/npm-acme/", username = "u", password = "p" }
                        """);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(bunfig)))
                .isEqualTo("rw-------");

        NodeNetwork.discard(env);
        assertThat(home).doesNotExist();
    }

    @Test
    void yarn_berry_is_told_to_trust_a_plain_http_registry_jk_hands_it() throws Exception {
        NodeNetwork.Sources sources = new NodeNetwork.Sources(
                new DownloadOrigin(URI.create("http://nexus.lan:8081/npm/"), "nexus.lan:8081"),
                Map.of(),
                origin -> RepoCredential.ANONYMOUS,
                uri -> Optional.empty(),
                List.of(),
                null);

        Map<String, String> env = NodeNetwork.env(sources, dir.resolve("work"), PackageManager.YARN);

        assertThat(env).containsEntry("YARN_UNSAFE_HTTP_WHITELIST", "nexus.lan");
        NodeNetwork.discard(env);
    }

    @Test
    void the_public_registry_with_no_credential_writes_nothing() throws Exception {
        NodeNetwork.Sources sources = new NodeNetwork.Sources(
                new DownloadOrigin(URI.create(NodeSources.NPM_REGISTRY), "registry.npmjs.org"),
                Map.of(),
                origin -> RepoCredential.ANONYMOUS,
                uri -> Optional.empty(),
                List.of(),
                null);

        assertThat(NodeNetwork.env(sources, dir.resolve("work"), PackageManager.NPM))
                .isEmpty();
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

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void berry_gets_its_scopes_from_a_run_home_that_links_the_real_one() throws Exception {
        Path userHome = Files.createDirectories(dir.resolve("real-home"));
        Files.createDirectories(userHome.resolve(".ssh"));
        Files.writeString(userHome.resolve(".yarnrc.yml"), "enableTelemetry: false\n");
        NodeNetwork.Sources sources = new NodeNetwork.Sources(
                REGISTRY,
                Map.of("@acme", ACME),
                origin -> origin.equals(REGISTRY)
                        ? new RepoCredential.Bearer("reg-token")
                        : new RepoCredential.Bearer("acme-token"),
                uri -> Optional.empty(),
                List.of(),
                userHome);

        Map<String, String> env = NodeNetwork.env(sources, dir.resolve("work"), PackageManager.YARN);

        Path home = Path.of(env.get("HOME"));
        assertThat(home.getFileName().toString()).startsWith(NodeNetwork.YARN_HOME_PREFIX);
        assertThat(Files.isSymbolicLink(home.resolve(".ssh"))).isTrue();
        assertThat(Files.readString(home.resolve(".yarnrc.yml")))
                .startsWith("enableTelemetry: false\n")
                .contains("npmScopes:\n  acme:\n    npmRegistryServer: \""
                        + ACME.url().toString().replaceAll("/$", "") + "\"")
                .contains("npmAuthToken: \"acme-token\"");
        assertThat(Files.getPosixFilePermissions(home.resolve(".yarnrc.yml")).toString())
                .isEqualTo("[OWNER_READ, OWNER_WRITE]");
        NodeNetwork.discard(env);
        assertThat(home).doesNotExist();
        assertThat(userHome.resolve(".ssh")).isDirectory();
    }

    @Test
    void a_scope_the_user_s_rc_already_names_is_left_to_it() {
        String rc = "npmScopes:\n  acme:\n    npmRegistryServer: \"https://mine\"\nnodeLinker: pnp\n";
        String merged = NodeNetwork.withScopes(
                rc,
                new LinkedHashMap<>(Map.of(
                        "acme", "    npmRegistryServer: \"https://jk\"\n",
                        "other", "    npmRegistryServer: \"https://other\"\n")));
        assertThat(merged)
                .contains("  acme:\n    npmRegistryServer: \"https://mine\"")
                .doesNotContain("https://jk")
                .contains("npmScopes:\n  other:\n    npmRegistryServer: \"https://other\"\n")
                .contains("nodeLinker: pnp");
    }
}
