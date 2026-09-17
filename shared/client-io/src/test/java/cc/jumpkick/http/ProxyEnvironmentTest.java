// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.NetworkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.task.RunNotices;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        return ProxyEnvironment.Settings.from(config, MavenSettings.empty(), env::get);
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

    /**
     * The acceptance the forward exists for: {@code https_proxy} exported by the shell running
     * {@code jk} and absent from the engine's own environment, and the production selector inside
     * that request names the shell's proxy. {@code JK_HOME} is pointed at an empty home so the
     * developer's own {@code [network]} table cannot answer instead.
     */
    @Test
    void the_ambient_selector_inside_a_request_names_the_proxy_the_request_carried(@TempDir Path home)
            throws Exception {
        Files.writeString(home.resolve("config.toml"), "");
        System.setProperty("jk.env.JK_HOME", home.toString());
        try {
            Session request =
                    Session.defaults().withVariant(null, Map.of("https_proxy", "http://this-terminal.proxy:3128"));
            List<Proxy> chosen = SessionContext.where(
                    request, () -> ProxyEnvironment.ambient().select(CENTRAL));

            assertThat(chosen).singleElement().satisfies(proxy -> {
                assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP);
                assertThat(proxy.address()).isEqualTo(at("this-terminal.proxy", 3128));
            });
        } finally {
            System.clearProperty("jk.env.JK_HOME");
        }
    }

    /**
     * {@link BuildEnv#PROXY} is what the client forwards and the workers inherit; this is what the
     * decision reads. Each name on the list moves the decision on its own, and nothing off the list
     * does, so the two cannot drift apart silently.
     */
    @Test
    void the_names_the_decision_reads_are_exactly_the_forwarded_list() {
        for (String name : BuildEnv.PROXY) {
            URI target = name.toLowerCase(Locale.ROOT).startsWith("https") ? CENTRAL : PLAIN;
            Map<String, String> alone = Map.of(name, "http://only.this.name:3128");
            if (name.toLowerCase(Locale.ROOT).startsWith("no_proxy")) {
                Map<String, String> withBypass = Map.of("https_proxy", "http://p:1", name, CENTRAL.getHost());
                assertThat(proxyFor(CENTRAL, withBypass)).as(name + " bypasses").isEmpty();
            } else {
                assertThat(proxyFor(target, alone))
                        .as(name + " selects")
                        .get()
                        .extracting(ProxyEnvironment.Endpoint::address)
                        .isEqualTo(at("only.this.name", 3128));
            }
        }
        assertThat(proxyFor(PLAIN, Map.of("ftp_proxy", "http://p:1", "all_proxy", "http://p:1")))
                .as("a name off the list does nothing")
                .isEmpty();
    }

    /**
     * Maven scopes a settings.xml proxy to repository traffic, and so does jk: a repository client
     * takes it, a general client (a JDK download, a forge API) reads the file and the shell alone.
     */
    @Test
    void a_settings_xml_proxy_reaches_repository_traffic_and_no_other(@TempDir Path dir) throws Exception {
        MavenSettings maven = maven(dir, """
                <settings><proxies>
                  <proxy><id>corp</id><protocol>https</protocol><host>maven.proxy</host><port>3129</port></proxy>
                </proxies></settings>
                """);
        Map<String, String> shell = Map.of("https_proxy", "http://shell.proxy:1");
        URI jdk = URI.create("https://api.adoptium.net/v3/assets/latest/25/hotspot");

        ProxyEnvironment repository = ProxyEnvironment.of(
                ProxyEnvironment.Traffic.REPOSITORY, () -> NetworkConfig.EMPTY, () -> maven, () -> shell::get);
        ProxyEnvironment general = ProxyEnvironment.of(
                ProxyEnvironment.Traffic.GENERAL, () -> NetworkConfig.EMPTY, () -> maven, () -> shell::get);

        assertThat(repository.select(CENTRAL))
                .singleElement()
                .extracting(Proxy::address)
                .isEqualTo(at("maven.proxy", 3129));
        assertThat(general.select(CENTRAL))
                .singleElement()
                .extracting(Proxy::address)
                .isEqualTo(at("shell.proxy", 1));
        assertThat(general.select(jdk))
                .singleElement()
                .extracting(Proxy::address)
                .isEqualTo(at("shell.proxy", 1));
        // The user's own [network] table and the shell reach every kind of traffic.
        NetworkConfig file = new NetworkConfig("http://file.proxy:3", null, List.of());
        ProxyEnvironment generalFromFile =
                ProxyEnvironment.of(ProxyEnvironment.Traffic.GENERAL, () -> file, () -> maven, () -> shell::get);
        assertThat(generalFromFile.select(jdk))
                .singleElement()
                .extracting(Proxy::address)
                .isEqualTo(at("file.proxy", 3));
    }

    private static MavenSettings maven(Path dir, String xml) throws Exception {
        Path file = dir.resolve("settings.xml");
        Files.writeString(file, xml);
        return MavenSettings.loadFrom(file);
    }

    private static String basic(String pair) {
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Maven's {@code <proxy>} sits between the file and the shell, is matched by protocol the way
     * Maven matches it, sends its credential as Basic, and its {@code nonProxyHosts} go direct.
     */
    @Test
    void a_settings_xml_proxy_is_taken_for_its_protocol_after_the_file_and_before_the_shell(@TempDir Path dir)
            throws Exception {
        MavenSettings maven = maven(dir, """
                <settings><proxies>
                  <proxy>
                    <id>corp</id><protocol>https</protocol><host>maven.proxy</host><port>3129</port>
                    <username>alice</username><password>s3cr3t</password>
                    <nonProxyHosts>*.corp|nexus.example</nonProxyHosts>
                  </proxy>
                </proxies></settings>
                """);
        Map<String, String> shell = Map.of("https_proxy", "http://shell.proxy:1", "http_proxy", "http://shell.proxy:2");

        ProxyEnvironment.Settings settings = ProxyEnvironment.Settings.from(NetworkConfig.EMPTY, maven, shell::get);
        Optional<ProxyEnvironment.Endpoint> viaMaven = ProxyEnvironment.endpointFor(CENTRAL, settings);
        assertThat(viaMaven)
                .get()
                .extracting(ProxyEnvironment.Endpoint::address)
                .isEqualTo(at("maven.proxy", 3129));
        assertThat(viaMaven.get().proxyAuthorization()).contains(basic("alice:s3cr3t"));
        assertThat(viaMaven.get().shown()).hasToString("http://maven.proxy:3129");
        // nonProxyHosts: a glob and an exact host go direct; a lookalike does not.
        assertThat(ProxyEnvironment.endpointFor(URI.create("https://repo.corp/m2/"), settings))
                .isEmpty();
        assertThat(ProxyEnvironment.endpointFor(URI.create("https://nexus.example/m2/"), settings))
                .isEmpty();
        assertThat(ProxyEnvironment.endpointFor(URI.create("https://nexus.example.org/m2/"), settings))
                .isPresent();
        // No Maven entry for http targets, so the shell's http_proxy decides those.
        assertThat(ProxyEnvironment.endpointFor(PLAIN, settings))
                .get()
                .extracting(ProxyEnvironment.Endpoint::address)
                .isEqualTo(at("shell.proxy", 2));
        // The user's own [network] table outranks Maven's file.
        NetworkConfig file = new NetworkConfig("http://file.proxy:3", null, List.of());
        assertThat(ProxyEnvironment.endpointFor(CENTRAL, ProxyEnvironment.Settings.from(file, maven, k -> null)))
                .get()
                .extracting(ProxyEnvironment.Endpoint::address)
                .isEqualTo(at("file.proxy", 3));
        // The shell's no_proxy still applies to a request the Maven proxy would carry.
        ProxyEnvironment.Settings bypassed = ProxyEnvironment.Settings.from(
                NetworkConfig.EMPTY, maven, Map.of("no_proxy", ".maven.apache.org")::get);
        assertThat(ProxyEnvironment.endpointFor(CENTRAL, bypassed)).isEmpty();
    }
}
