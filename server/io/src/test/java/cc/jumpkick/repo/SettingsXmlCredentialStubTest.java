// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.forge.ForgeAuth;
import cc.jumpkick.forge.TokenStore;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.http.Http;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.testing.LoopbackHttp;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A settings.xml {@code <server>} password that is not written in the file as plain text
 * authenticates against a repository that answers 401 to anything else: an {@code ${env.NAME}}
 * the request's shell supplies, not the environment of the process serving the request, or a
 * {@code {...}} value the master password of {@code settings-security.xml} unlocks.
 */
@Tag("integration")
class SettingsXmlCredentialStubTest {

    @BeforeAll
    static void isolateM2(@TempDir Path m2) {
        System.setProperty("jk.m2.local", m2.toString());
    }

    /** The repository the project names; it serves nothing, so only the mirror can answer. */
    @RegisterExtension
    final LoopbackHttp origin = new LoopbackHttp();

    /** The mirror: a repository that wants Basic {@code deployer:<password>}. */
    @RegisterExtension
    final LoopbackHttp nexus = new LoopbackHttp();

    private static final String VARIABLE = "JK_SETTINGS_STUB_PW";
    private static final String PASSWORD = "only-in-the-requesting-shell";
    private static final Coordinate WIDGET = Coordinate.of("com.example", "widget", "1.0");
    private static final String POM_PATH = "/com/example/widget/1.0/widget-1.0.pom";
    private static final byte[] POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>widget</artifactId>
              <version>1.0</version>
            </project>
            """.getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    @AfterEach
    void reset() {
        RunNotices.clear();
        SessionContext.reset();
        System.clearProperty(MavenSettings.SETTINGS_PROPERTY);
    }

    /** {@code POM_PATH} and its {@code .sha256} answered only to {@code deployer:<expected>}; 401 otherwise. */
    private void requireBasic(String expected) {
        String want = "Basic "
                + Base64.getEncoder().encodeToString(("deployer:" + expected).getBytes(StandardCharsets.UTF_8));
        String base = nexus.base().getPath();
        String path = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + POM_PATH;
        nexus.server().createContext(path, exchange -> {
            try (exchange) {
                if (!want.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"nexus\"");
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                // The context is a prefix, so it answers the checksum sidecars too.
                String asked = exchange.getRequestURI().getPath();
                byte[] body = asked.endsWith(".pom")
                        ? POM
                        : asked.endsWith(".pom.sha256")
                                ? Hashing.sha256Hex(POM).getBytes(StandardCharsets.UTF_8)
                                : null;
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
    }

    private Path settings(Path dir, String password) throws Exception {
        Path xml = dir.resolve("settings.xml");
        Files.writeString(xml, """
                <settings>
                  <servers><server><id>nexus</id><username>deployer</username><password>%s</password></server></servers>
                  <mirrors><mirror><id>nexus</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors>
                </settings>
                """.formatted(password, nexus.base()));
        return xml;
    }

    /** No login store, no forge token: the resolver's only source is {@code settings}. */
    private static RepoCredentialResolver creds(MavenSettings settings, Path dir) {
        return new RepoCredentialResolver(
                k -> null,
                settings,
                new RepoCredentialStore(dir.resolve("creds")),
                new ForgeAuth(new TokenStore(dir.resolve("tokens")), k -> null, argv -> Optional.empty()),
                (endpoint, field, token) -> Optional.empty(),
                k -> null,
                List::of);
    }

    private MavenRepo.Fetched fetchThroughMirror(Path dir) throws Exception {
        MavenSettings settings = MavenSettings.current();
        MavenRepo central = new MavenRepo("central", origin.base(), new Http(), new Cas(dir.resolve("cas")));
        return RepoMirrors.apply(central, settings, creds(settings, dir)).fetchPom(WIDGET);
    }

    @Test
    void an_env_password_exported_only_in_the_requesting_shell_authenticates(@TempDir Path dir) throws Exception {
        assertThat(ProcessEnv.get(VARIABLE))
                .as("the serving process does not have it")
                .isNull();
        requireBasic(PASSWORD);
        System.setProperty(
                MavenSettings.SETTINGS_PROPERTY,
                settings(dir, "${env." + VARIABLE + "}").toString());

        MavenRepo.Fetched fetched = RequestShell.where(Map.of(VARIABLE, PASSWORD), () -> fetchThroughMirror(dir));

        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(POM));
        assertThat(origin.requested()).isEmpty();
        assertThat(URI.create(fetched.url().toString()).getPort())
                .isEqualTo(nexus.base().getPort());
    }

    /**
     * Maven's encrypted form, master password included: published known answers from
     * plexus-sec-dispatcher 2.0's {@code SecUtilTest} — the master {@code testtest} under {@code
     * settings.security}, and the password {@code testtest} under that master.
     */
    @Test
    void an_encrypted_password_authenticates(@TempDir Path dir) throws Exception {
        requireBasic("testtest");
        Path security = dir.resolve("settings-security.xml");
        Files.writeString(
                security,
                "<settingsSecurity><master>{1wQaa6S/o8MH7FnaTNL53XmhT5O0SEGXQi3gC49o6OY=}</master></settingsSecurity>");
        System.setProperty(
                MavenSettings.SETTINGS_PROPERTY,
                settings(dir, "{BteqUEnqHecHM7MZfnj9FwLcYbdInWxou1C929Txa0A=}").toString());

        MavenRepo.Fetched fetched = RequestShell.where(
                Map.of("JK_M2_SETTINGS_SECURITY", security.toString()), () -> fetchThroughMirror(dir));

        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(POM));
        assertThat(origin.requested()).isEmpty();
    }
}
