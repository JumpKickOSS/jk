// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.NetworkConfig;
import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.task.RunNotices;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A proxy password, from whichever source wrote it, is masked in the text the request's workspace
 * sends out. The redactor is {@code ResolvedSecrets.plus(workspace, …)}, the one the engine composes
 * for the wire, {@code -o jsonl} and the journal; the failures are the ones a tool given the proxy
 * URL prints, and the one a proxy that echoes the header it was sent produces.
 */
class ProxyCredentialRedactionTest {

    private static final URI NPM = URI.create("https://registry.npmjs.org/left-pad");

    /** The password {@code testtest}, encrypted under the master password {@code testtest} (plexus-sec-dispatcher's SecUtilTest). */
    private static final String ENCRYPTED = "{BteqUEnqHecHM7MZfnj9FwLcYbdInWxou1C929Txa0A=}";

    /** The master password {@code testtest}, as {@code settings-security.xml} holds it. */
    private static final String MASTER = "{1wQaa6S/o8MH7FnaTNL53XmhT5O0SEGXQi3gC49o6OY=}";

    @BeforeEach
    @AfterEach
    void forget() {
        ResolvedSecrets.clear();
        RunNotices.clear();
    }

    /** What npm prints when the proxy jk handed it refuses: the URL as it was given. */
    private static String npmFailure(String proxyUrl) {
        return "npm error request to " + NPM + " failed, reason: tunneling socket could not be established, proxy "
                + proxyUrl + " answered 407";
    }

    private static String masked(Path workspace, String text) {
        return ResolvedSecrets.plus(workspace, SecretRedactor.none()).redact(text);
    }

    /** The proxy URL a tool of {@code workspace}'s request is handed. */
    private static String handedOut(Path workspace, Map<String, String> shell, Callable<ProxyEnvironment> proxies)
            throws Exception {
        Session request = Session.defaults().withWorkingDir(workspace).withVariant(null, shell);
        return SessionContext.where(request, () -> proxies.call().proxyUrl(NPM).orElseThrow());
    }

    private static MavenSettings settingsXml(Path dir, String password) throws Exception {
        Path file = dir.resolve("settings.xml");
        Files.writeString(file, """
                <settings>
                  <proxies>
                    <proxy><id>corp</id><protocol>https</protocol><host>proxy.corp</host><port>3128</port>
                      <username>builder</username><password>%s</password></proxy>
                  </proxies>
                </settings>
                """.formatted(password));
        return MavenSettings.loadFrom(file);
    }

    @Test
    void a_settings_xml_proxy_password_expanded_from_the_shell_is_masked(@TempDir Path tmp) throws Exception {
        Path workspace = Files.createDirectories(tmp.resolve("project"));
        Map<String, String> shell = Map.of("CORP_PROXY_PASSWORD", "expanded-proxy-secret");
        String url = handedOut(workspace, shell, () -> {
            MavenSettings maven = settingsXml(tmp, "${env.CORP_PROXY_PASSWORD}");
            return new ProxyEnvironment(() -> NetworkConfig.EMPTY, () -> maven, () -> shell::get);
        });
        assertThat(url).contains("expanded-proxy-secret");

        assertThat(masked(workspace, npmFailure(url)))
                .doesNotContain("expanded-proxy-secret")
                .contains(SecretRedactor.MASK)
                .contains("builder:" + SecretRedactor.MASK + "@proxy.corp");
    }

    @Test
    void a_settings_xml_proxy_password_decrypted_with_the_master_is_masked(@TempDir Path tmp) throws Exception {
        Path workspace = Files.createDirectories(tmp.resolve("project"));
        Path security = tmp.resolve("settings-security.xml");
        Files.writeString(security, "<settingsSecurity><master>" + MASTER + "</master></settingsSecurity>");
        Map<String, String> shell = Map.of("JK_M2_SETTINGS_SECURITY", security.toString());
        String url = handedOut(workspace, shell, () -> {
            MavenSettings maven = settingsXml(tmp, ENCRYPTED);
            assertThat(maven.warnings()).isEmpty();
            return new ProxyEnvironment(() -> NetworkConfig.EMPTY, () -> maven, () -> shell::get);
        });
        assertThat(url).contains("builder:testtest@");

        assertThat(masked(workspace, npmFailure(url)))
                .doesNotContain("testtest")
                .contains(SecretRedactor.MASK);
    }

    /** A {@code [network]} password needing percent-encoding is masked as written and as the URL spells it. */
    @Test
    void a_network_table_password_is_masked_as_written_and_as_encoded(@TempDir Path tmp) throws Exception {
        Path workspace = Files.createDirectories(tmp.resolve("project"));
        NetworkConfig network = new NetworkConfig("http://builder:p%40ss%2Fw0rd-net@proxy.corp:3128", null, List.of());
        String url = handedOut(
                workspace,
                Map.of(),
                () -> new ProxyEnvironment(() -> network, MavenSettings::empty, () -> name -> null));

        assertThat(masked(workspace, npmFailure(url)))
                .doesNotContain("p%40ss%2Fw0rd-net")
                .contains(SecretRedactor.MASK);
        assertThat(masked(workspace, "proxy refused builder:p@ss/w0rd-net"))
                .doesNotContain("p@ss/w0rd-net")
                .contains(SecretRedactor.MASK);
    }

    /**
     * The shell's {@code https_proxy}, as jk's own request sends it: the Basic value of {@code
     * Proxy-Authorization} is masked too, so a proxy that quotes the header back does not print a
     * reversible credential.
     */
    @Test
    void a_shell_proxy_password_and_its_basic_header_are_masked(@TempDir Path tmp) throws Exception {
        Path workspace = Files.createDirectories(tmp.resolve("project"));
        Map<String, String> shell = Map.of("https_proxy", "http://builder:shell-proxy-secret@proxy.corp:3128");
        ProxyEnvironment proxies = new ProxyEnvironment(() -> NetworkConfig.EMPTY, () -> shell::get);
        Session request = Session.defaults().withWorkingDir(workspace).withVariant(null, shell);
        String header = SessionContext.where(
                request, () -> proxies.proxyAuthorization(NPM).orElseThrow());

        String failure = "502 from proxy.corp: upstream refused (sent Proxy-Authorization: " + header
                + "; user builder:shell-proxy-secret)";
        assertThat(masked(workspace, failure))
                .doesNotContain("shell-proxy-secret")
                .doesNotContain(header.substring("Basic ".length()))
                .contains("builder");
    }

    /** The engine serves many workspaces: one request's proxy password is not masked out of another's output. */
    @Test
    void the_proxy_password_stays_in_the_requests_workspace(@TempDir Path tmp) throws Exception {
        Path workspace = Files.createDirectories(tmp.resolve("project"));
        Path other = Files.createDirectories(tmp.resolve("other"));
        Map<String, String> shell = Map.of("https_proxy", "http://builder:scoped-proxy-secret@proxy.corp:3128");
        handedOut(workspace, shell, () -> new ProxyEnvironment(() -> NetworkConfig.EMPTY, () -> shell::get));

        assertThat(masked(other, "scoped-proxy-secret")).isEqualTo("scoped-proxy-secret");
        assertThat(masked(workspace, "scoped-proxy-secret")).isEqualTo(SecretRedactor.MASK);
    }

    /** A direct request hands out no proxy credential, so nothing is filed for it. */
    @Test
    void a_target_that_goes_direct_files_nothing(@TempDir Path tmp) throws Exception {
        Path workspace = Files.createDirectories(tmp.resolve("project"));
        Map<String, String> shell = Map.of(
                "https_proxy", "http://builder:direct-proxy-secret@proxy.corp:3128", "no_proxy", "registry.npmjs.org");
        ProxyEnvironment proxies = new ProxyEnvironment(() -> NetworkConfig.EMPTY, () -> shell::get);
        Session request = Session.defaults().withWorkingDir(workspace).withVariant(null, shell);
        assertThat(SessionContext.where(request, () -> proxies.proxyUrl(NPM))).isEmpty();

        assertThat(masked(workspace, "direct-proxy-secret")).isEqualTo("direct-proxy-secret");
    }
}
