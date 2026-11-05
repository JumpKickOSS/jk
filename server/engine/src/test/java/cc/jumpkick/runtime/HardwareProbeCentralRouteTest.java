// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.RepositorySpec;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The calibration probe's downloads ask Central where the user's resolves do: through the mirror
 * that stands in for it, never around it.
 */
class HardwareProbeCentralRouteTest {

    @TempDir
    Path tmp;

    private @Nullable String priorHome;
    private @Nullable String priorSettings;
    private Path config = Path.of("config.toml");

    @BeforeEach
    void isolate() throws Exception {
        priorHome = System.getProperty("jk.env.JK_HOME");
        priorSettings = System.getProperty(MavenSettings.SETTINGS_PROPERTY);
        Path home = Files.createDirectories(tmp.resolve("home"));
        config = home.resolve("config.toml");
        Files.writeString(config, "");
        System.setProperty("jk.env.JK_HOME", home.toString());
        System.setProperty(
                MavenSettings.SETTINGS_PROPERTY,
                tmp.resolve("absent-settings.xml").toString());
    }

    @AfterEach
    void restore() {
        restore("jk.env.JK_HOME", priorHome);
        restore(MavenSettings.SETTINGS_PROPERTY, priorSettings);
    }

    private static void restore(String property, @Nullable String prior) {
        if (prior == null) System.clearProperty(property);
        else System.setProperty(property, prior);
    }

    @Test
    void with_no_mirror_the_probe_asks_central_itself() {
        assertThat(HardwareProbe.centralBase())
                .isEqualTo(RepositorySpec.MAVEN_CENTRAL.url().toString());
    }

    @Test
    void a_mirror_of_central_in_config_toml_is_where_the_probe_asks() throws Exception {
        Files.writeString(config, """
                [repositories.corp]
                url = "http://127.0.0.1:47613/corp"
                mirror-of = ["*"]
                """);

        assertThat(HardwareProbe.centralBase()).isEqualTo("http://127.0.0.1:47613/corp/");
    }

    @Test
    void finding_the_mirror_runs_no_credential_helper() throws Exception {
        Path ran = tmp.resolve("helper-ran");
        Files.writeString(config, """
                [repositories.corp]
                url = "http://127.0.0.1:47613/corp/"
                mirror-of = ["central"]
                token = "${cmd:touch %s}"
                """.formatted(ran));

        assertThat(HardwareProbe.centralBase()).isEqualTo("http://127.0.0.1:47613/corp/");
        assertThat(ran).doesNotExist();
    }

    @Test
    void a_config_toml_override_of_central_is_where_the_probe_asks() throws Exception {
        Files.writeString(config, """
                [repositories.central]
                url = "http://127.0.0.1:47613/central/"
                """);

        assertThat(HardwareProbe.centralBase()).isEqualTo("http://127.0.0.1:47613/central/");
    }

    @Test
    void a_mirror_that_answers_401_still_times_the_round_trip_and_no_helper_runs() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        HttpServer stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/", exchange -> {
            asked.incrementAndGet();
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"corp\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        stub.start();
        try {
            Path ran = tmp.resolve("helper-ran");
            Files.writeString(config, """
                    [repositories.corp]
                    url = "http://127.0.0.1:%d/corp/"
                    mirror-of = ["*"]
                    token = "${cmd:touch %s}"
                    """.formatted(stub.getAddress().getPort(), ran));

            String base = Objects.requireNonNull(HardwareProbe.centralBase());
            long ms = HardwareProbe.measureResolve(base);

            assertThat(ms).as("a timing from the 401 answers").isGreaterThanOrEqualTo(0);
            assertThat(asked.get()).isEqualTo(HardwareProbe.WARM_SAMPLES + 1);
            assertThat(ran).as("the probe resolves no credential").doesNotExist();
        } finally {
            stub.stop(0);
        }
    }

    @Test
    void a_refusal_counts_as_a_round_trip_and_a_missing_file_does_not() {
        assertThat(HardwareProbe.isRoundTrip(401, 0)).isTrue();
        assertThat(HardwareProbe.isRoundTrip(403, 0)).isTrue();
        assertThat(HardwareProbe.isRoundTrip(200, 5_000)).isTrue();
        assertThat(HardwareProbe.isRoundTrip(200, 0)).isFalse();
        assertThat(HardwareProbe.isRoundTrip(404, 120)).isFalse();
        assertThat(HardwareProbe.isRoundTrip(503, 0)).isFalse();
    }
}
