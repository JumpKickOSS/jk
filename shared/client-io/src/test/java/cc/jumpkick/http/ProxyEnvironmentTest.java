// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.NetworkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.task.RunNotices;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Which proxy a request goes through, from the file and the shell — the decision, not the wire. */
class ProxyEnvironmentTest {

    private static final URI CENTRAL = URI.create("https://repo.maven.apache.org/maven2/a.pom");
    private static final URI PLAIN = URI.create("http://mirror.example.com/a.pom");

    @BeforeEach
    @AfterEach
    void forgetRunNotices() {
        RunNotices.clear();
    }

    private static ProxyEnvironment.Settings settings(NetworkConfig config, Map<String, String> env) {
        return ProxyEnvironment.Settings.from(config, env::get);
    }

    private static Optional<ProxyEnvironment.Endpoint> proxyFor(URI target, Map<String, String> env) {
        return ProxyEnvironment.endpointFor(target, settings(NetworkConfig.EMPTY, env));
    }

    private static InetSocketAddress at(String host, int port) {
        return InetSocketAddress.createUnresolved(host, port);
    }

    @Test
    void each_scheme_reads_its_own_variable_in_either_case_and_lower_case_wins() {
        assertThat(proxyFor(CENTRAL, Map.of("https_proxy", "http://secure.proxy:3129")))
                .get()
                .extracting(ProxyEnvironment.Endpoint::address)
                .isEqualTo(at("secure.proxy", 3129));
        assertThat(proxyFor(CENTRAL, Map.of("HTTPS_PROXY", "http://upper.proxy:3129")))
                .get()
                .extracting(ProxyEnvironment.Endpoint::address)
                .isEqualTo(at("upper.proxy", 3129));
        assertThat(proxyFor(
                        CENTRAL, Map.of("https_proxy", "http://lower.proxy:1", "HTTPS_PROXY", "http://upper.proxy:2")))
                .get()
                .extracting(ProxyEnvironment.Endpoint::address)
                .isEqualTo(at("lower.proxy", 1));
        assertThat(proxyFor(PLAIN, Map.of("HTTP_PROXY", "http://plain.proxy:3128")))
                .get()
                .extracting(ProxyEnvironment.Endpoint::address)
                .isEqualTo(at("plain.proxy", 3128));

        // An https target does not borrow the http proxy, nor the other way round.
        assertThat(proxyFor(CENTRAL, Map.of("http_proxy", "http://plain.proxy:3128")))
                .isEmpty();
        assertThat(proxyFor(PLAIN, Map.of("https_proxy", "http://secure.proxy:3129")))
                .isEmpty();
    }

    @Test
    void the_config_file_outranks_the_shell_and_its_https_key_outranks_its_general_one() {
        NetworkConfig general = new NetworkConfig("http://file.proxy:8080", null, List.of());
        Map<String, String> shell = Map.of("https_proxy", "http://shell.proxy:1", "http_proxy", "http://shell.proxy:2");

        assertThat(ProxyEnvironment.endpointFor(CENTRAL, settings(general, shell)))
                .get()
                .extracting(ProxyEnvironment.Endpoint::address)
                .isEqualTo(at("file.proxy", 8080));
        assertThat(ProxyEnvironment.endpointFor(PLAIN, settings(general, shell)))
                .get()
                .extracting(ProxyEnvironment.Endpoint::address)
                .isEqualTo(at("file.proxy", 8080));

        NetworkConfig split = new NetworkConfig("http://file.proxy:8080", "http://file.proxy:8443", List.of());
        assertThat(ProxyEnvironment.endpointFor(CENTRAL, settings(split, shell)))
                .get()
                .extracting(ProxyEnvironment.Endpoint::address)
                .isEqualTo(at("file.proxy", 8443));
    }

    @Test
    void a_scheme_less_proxy_is_http_on_port_80_and_a_bare_host_keeps_its_port() {
        assertThat(proxyFor(PLAIN, Map.of("http_proxy", "proxy.corp:3128")))
                .get()
                .extracting(ProxyEnvironment.Endpoint::address)
                .isEqualTo(at("proxy.corp", 3128));
        assertThat(proxyFor(PLAIN, Map.of("http_proxy", "http://proxy.corp")))
                .get()
                .extracting(ProxyEnvironment.Endpoint::address)
                .isEqualTo(at("proxy.corp", 80));
    }

    @Test
    void no_proxy_takes_every_spelling_and_shape() {
        List<String> list = List.of("*.cdn.example", ".internal.corp", "Nexus.Corp", "10.0.0.5:8081", "[::2]");

        assertThat(ProxyEnvironment.bypassed("nexus.corp", 443, list)).isTrue();
        assertThat(ProxyEnvironment.bypassed("repo.nexus.corp", 443, list))
                .as("a bare suffix covers subdomains")
                .isTrue();
        assertThat(ProxyEnvironment.bypassed("a.internal.corp", 443, list)).isTrue();
        assertThat(ProxyEnvironment.bypassed("internal.corp", 443, list)).isTrue();
        assertThat(ProxyEnvironment.bypassed("x.cdn.example", 80, list)).isTrue();
        assertThat(ProxyEnvironment.bypassed("10.0.0.5", 8081, list)).isTrue();
        assertThat(ProxyEnvironment.bypassed("10.0.0.5", 80, list))
                .as("a port-qualified entry is that port only")
                .isFalse();
        assertThat(ProxyEnvironment.bypassed("[::2]", 80, list)).isTrue();
        assertThat(ProxyEnvironment.bypassed("notnexus.corp", 443, list))
                .as("a suffix match is on a label boundary")
                .isFalse();
        assertThat(ProxyEnvironment.bypassed("repo.maven.apache.org", 443, list))
                .isFalse();
        assertThat(ProxyEnvironment.bypassed("anything.example", 443, List.of("*")))
                .isTrue();
    }

    @Test
    void the_shell_and_the_file_bypass_lists_are_both_honoured() {
        NetworkConfig config = new NetworkConfig("http://proxy.corp:3128", null, List.of("nexus.corp"));
        Map<String, String> shell =
                Map.of("no_proxy", "mirror.example.com, other.example", "NO_PROXY", "third.example");
        ProxyEnvironment.Settings settings = settings(config, shell);

        assertThat(settings.noProxy())
                .containsExactly("nexus.corp", "mirror.example.com", "other.example", "third.example");
        assertThat(ProxyEnvironment.endpointFor(PLAIN, settings))
                .as("bypassed by the shell's list")
                .isEmpty();
        assertThat(ProxyEnvironment.endpointFor(URI.create("https://nexus.corp/repo/"), settings))
                .as("bypassed by the file's list")
                .isEmpty();
        assertThat(ProxyEnvironment.endpointFor(CENTRAL, settings)).isPresent();
    }

    @Test
    void loopback_targets_never_go_through_a_proxy() {
        Map<String, String> shell =
                Map.of("http_proxy", "http://proxy.corp:3128", "https_proxy", "http://proxy.corp:3128");
        for (String target : new String[] {
            "http://127.0.0.1:8081/repo/", "http://localhost:8081/repo/", "https://[::1]/repo/", "http://127.1.2.3/x"
        }) {
            assertThat(proxyFor(URI.create(target), shell)).as(target).isEmpty();
        }
    }

    @Test
    void the_selector_answers_the_client_with_the_proxy_or_direct() {
        ProxyEnvironment selector = new ProxyEnvironment(
                () -> NetworkConfig.EMPTY,
                () -> Map.of("https_proxy", "http://proxy.corp:3128", "no_proxy", ".corp")::get);

        assertThat(selector.select(CENTRAL)).containsExactly(new Proxy(Proxy.Type.HTTP, at("proxy.corp", 3128)));
        assertThat(selector.select(URI.create("https://nexus.corp/"))).containsExactly(Proxy.NO_PROXY);
        assertThat(selector.select(PLAIN)).containsExactly(Proxy.NO_PROXY);
    }

    @Test
    void a_credential_in_the_proxy_url_is_kept_for_the_challenge_and_out_of_what_is_shown() {
        Optional<ProxyEnvironment.Endpoint> endpoint =
                proxyFor(CENTRAL, Map.of("https_proxy", "http://alice:s3cr3t@proxy.corp:3128"));

        assertThat(endpoint).isPresent();
        PasswordAuthentication credential =
                Objects.requireNonNull(endpoint.get().credential());
        assertThat(credential.getUserName()).isEqualTo("alice");
        assertThat(new String(credential.getPassword())).isEqualTo("s3cr3t");
        assertThat(endpoint.get().shown().toString()).isEqualTo("http://proxy.corp:3128");
        assertThat(endpoint.get().toString()).doesNotContain("s3cr3t");
    }

    /**
     * A value this client cannot use goes direct after one warning that names the variable — the
     * value is the likeliest place for a credential to sit unparsed, so it is never echoed.
     */
    @Test
    void an_unusable_proxy_value_goes_direct_with_a_warning_that_names_the_variable_not_the_value() {
        var err = new ByteArrayOutputStream();
        Log.install(
                new PrintStream(err, true, StandardCharsets.UTF_8), System.Logger.Level.INFO, UnaryOperator.identity());
        try {
            SessionContext.runWhere(Session.defaults(), () -> {
                assertThat(proxyFor(CENTRAL, Map.of("https_proxy", "socks5://alice:s3cr3t@proxy.corp:1080")))
                        .isEmpty();
                assertThat(proxyFor(CENTRAL, Map.of("https_proxy", "socks5://alice:s3cr3t@proxy.corp:1080")))
                        .isEmpty();
                assertThat(proxyFor(PLAIN, Map.of("http_proxy", "http://"))).isEmpty();
            });
        } finally {
            Log.install(System.err, System.Logger.Level.INFO, UnaryOperator.identity());
        }
        String warnings = err.toString(StandardCharsets.UTF_8);
        assertThat(warnings)
                .contains("ignoring https_proxy")
                .contains("socks5")
                .contains("ignoring http_proxy")
                .doesNotContain("s3cr3t")
                .doesNotContain("alice");
        assertThat(warnings.split("ignoring https_proxy", -1))
                .as("said once per run")
                .hasSize(2);
    }
}
