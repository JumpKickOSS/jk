// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.m2;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.task.RunNotices;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Maven's {@code ${...}} expressions in a {@code settings.xml}: {@code ${env.NAME}} from the
 * requesting shell, {@code ${user.home}} and the other system properties from the JVM, and an
 * expression that does not resolve left as written with a warning that names where it is.
 */
class SettingsExpansionTest {

    private static final String PROPERTY = "jk.test.settings.nexus.host";

    @AfterEach
    void clear() {
        System.clearProperty(PROPERTY);
        System.clearProperty(MavenSettings.SETTINGS_PROPERTY);
        RunNotices.clear();
    }

    private static Path write(Path dir, String xml) throws Exception {
        Path file = dir.resolve("settings.xml");
        Files.writeString(file, xml);
        return file;
    }

    private static final String SERVER = """
            <settings>
              <servers>
                <server>
                  <id>nexus</id>
                  <username>${env.NEXUS_USER}</username>
                  <password>${env.NEXUS_PASSWORD}</password>
                  <passphrase>${env.NEXUS_PASSPHRASE}</passphrase>
                </server>
              </servers>
            </settings>
            """;

    @Test
    void env_expressions_in_a_server_entry_expand_from_the_environment(@TempDir Path dir) throws Exception {
        Path xml = write(dir, SERVER);
        Map<String, String> env =
                Map.of("NEXUS_USER", "deployer", "NEXUS_PASSWORD", "s3cr3t-pw", "NEXUS_PASSPHRASE", "key-phrase");

        MavenSettings settings = MavenSettings.loadFrom(xml, env::get);

        assertThat(settings.server("nexus")).hasValueSatisfying(s -> {
            assertThat(s.username()).isEqualTo("deployer");
            assertThat(s.password()).isEqualTo("s3cr3t-pw");
            assertThat(s.passphrase()).isEqualTo("key-phrase");
        });
        assertThat(settings.warnings()).isEmpty();
    }

    @Test
    void an_expression_inside_a_mirror_url_expands_and_its_credential_stays_out_of_messages(@TempDir Path dir)
            throws Exception {
        Path xml = write(dir, """
                <settings>
                  <mirrors>
                    <mirror>
                      <id>corp</id>
                      <mirrorOf>*</mirrorOf>
                      <url>https://${env.MIRROR_USER}:${env.MIRROR_TOKEN}@${env.MIRROR_HOST}/m2/</url>
                    </mirror>
                  </mirrors>
                </settings>
                """);
        Map<String, String> env =
                Map.of("MIRROR_USER", "ci", "MIRROR_TOKEN", "tok-123456", "MIRROR_HOST", "nexus.corp");

        MavenSettings settings = MavenSettings.loadFrom(xml, env::get);

        assertThat(settings.mirrors()).singleElement().satisfies(m -> {
            assertThat(m.url()).hasToString("https://ci:tok-123456@nexus.corp/m2/");
            // What jk doctor prints for the mirror: the expanded URL without its credential.
            assertThat(SafeUri.forMessage(m.url())).isEqualTo("https://nexus.corp/m2/");
        });
    }

    @Test
    void system_properties_expand_in_urls(@TempDir Path dir) throws Exception {
        System.setProperty(PROPERTY, "nexus.example");
        Path xml = write(dir, """
                <settings>
                  <mirrors>
                    <mirror><id>corp</id><mirrorOf>central</mirrorOf><url>https://${%s}/m2/</url></mirror>
                  </mirrors>
                  <profiles>
                    <profile>
                      <id>team</id>
                      <repositories>
                        <repository><id>local-team</id><url>file:${user.home}/team-repo/</url></repository>
                      </repositories>
                    </profile>
                  </profiles>
                  <activeProfiles><activeProfile>team</activeProfile></activeProfiles>
                </settings>
                """.formatted(PROPERTY));

        MavenSettings settings = MavenSettings.loadFrom(xml, k -> null);

        assertThat(settings.mirrors()).singleElement().satisfies(m -> assertThat(m.url())
                .hasToString("https://nexus.example/m2/"));
        assertThat(settings.profileRepositories())
                .extracting(RepositorySpec::url)
                .containsExactly(URI.create("file:" + System.getProperty("user.home") + "/team-repo/"));
    }

    @Test
    void an_unset_variable_stays_as_written_and_warns_naming_the_file_the_element_and_the_variable(@TempDir Path dir)
            throws Exception {
        Path xml = write(dir, SERVER);

        MavenSettings settings = MavenSettings.loadFrom(xml, Map.of("NEXUS_USER", "deployer")::get);

        assertThat(settings.server("nexus"))
                .hasValueSatisfying(s -> assertThat(s.password()).isEqualTo("${env.NEXUS_PASSWORD}"));
        assertThat(settings.warnings())
                .contains("jk: warning: " + xml + " <server> `nexus` <password> names ${env.NEXUS_PASSWORD},"
                        + " and NEXUS_PASSWORD is not set in the shell that ran jk; it is used as written");
    }

    @Test
    void settings_and_project_expressions_are_not_expanded(@TempDir Path dir) throws Exception {
        Path xml = write(dir, """
                <settings>
                  <servers>
                    <server><id>a</id><username>${settings.localRepository}</username><password>p</password></server>
                    <server><id>b</id><username>${project.version}</username><password>p</password></server>
                  </servers>
                </settings>
                """);

        MavenSettings settings = MavenSettings.loadFrom(xml, k -> "always-set");

        assertThat(settings.server("a"))
                .hasValueSatisfying(s -> assertThat(s.username()).isEqualTo("${settings.localRepository}"));
        assertThat(settings.server("b"))
                .hasValueSatisfying(s -> assertThat(s.username()).isEqualTo("${project.version}"));
        assertThat(settings.warnings()).anySatisfy(w -> assertThat(w)
                .contains("<server> `a` <username> names ${settings.localRepository}, which settings.xml does not"
                        + " expand"));
    }

    /**
     * The engine is resident and the file is parsed once, but each request expands it against its
     * own shell: one caller's password is never served to another.
     */
    @Test
    void the_current_settings_expand_against_each_requesting_shell(@TempDir Path dir) throws Exception {
        Path xml = write(dir, SERVER);
        System.setProperty(MavenSettings.SETTINGS_PROPERTY, xml.toString());

        String first =
                RequestShell.where(Map.of("NEXUS_PASSWORD", "first-shell-pw"), () -> password(MavenSettings.current()));
        String second = RequestShell.where(
                Map.of("NEXUS_PASSWORD", "second-shell-pw"), () -> password(MavenSettings.current()));
        String again =
                RequestShell.where(Map.of("NEXUS_PASSWORD", "first-shell-pw"), () -> password(MavenSettings.current()));

        assertThat(first).isEqualTo("first-shell-pw");
        assertThat(second).isEqualTo("second-shell-pw");
        assertThat(again).isEqualTo("first-shell-pw");
    }

    @Test
    void the_current_settings_say_an_unset_variable_on_the_run(@TempDir Path dir) throws Exception {
        Path xml = write(dir, SERVER);
        System.setProperty(MavenSettings.SETTINGS_PROPERTY, xml.toString());

        String said = warningsFrom(() -> RequestShell.runWhere(Map.of("NEXUS_USER", "deployer"), () -> {
            MavenSettings.current();
            MavenSettings.current();
        }));

        assertThat(said).containsOnlyOnce("<server> `nexus` <password> names ${env.NEXUS_PASSWORD}");
    }

    private static String password(MavenSettings settings) {
        return settings.server("nexus").map(MavenSettings.Server::password).orElse("none");
    }

    /** One run, the way the engine scopes one, with the log captured. */
    private static String warningsFrom(Runnable body) {
        var err = new ByteArrayOutputStream();
        Log.install(
                new PrintStream(err, true, StandardCharsets.UTF_8), System.Logger.Level.INFO, UnaryOperator.identity());
        try {
            SessionContext.runWhere(Session.defaults(), body);
        } finally {
            Log.install(System.err, System.Logger.Level.INFO, UnaryOperator.identity());
        }
        return err.toString(StandardCharsets.UTF_8);
    }
}
