// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.m2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Maven 4's {@code mvnenc} passwords, from real {@code mvnenc} output: Apache Maven 4.0.0-rc-7
 * (plexus-sec-dispatcher 4.2.0) run under a scratch home, {@code mvnenc init} choosing the master
 * dispatcher, AES/GCM/NoPadding and an {@code env:} source, then {@code mvnenc encrypt}. Each
 * ciphertext was checked with that Maven's own {@code mvn help:effective-settings
 * -DshowPasswords=true} before it was committed here. The master passwords are fixtures, not
 * anyone's.
 */
class MvnencPasswordsTest {

    /** The master password {@link #SECURITY4_ENV} names through {@code env:MAVEN_MASTER_PASSWORD}. */
    static final String ENV_MASTER = "jk-fixture-master-4";

    /** {@code jk-fixture-server-password} under {@link #ENV_MASTER}. */
    static final String SERVER = "{[name=master,cipher=AES/GCM/NoPadding,version=4.2]"
            + "5YUIPYP8pAMzglyrX7LALQyYLYH5OVJy5ZPRNQHt07HRij47b+3mWVxaYxXfLjCkQodyQUx5dAT7MXSX+vKkehaqDIXgrg==}";

    /** {@code proxy p@ss/wörd} under {@link #ENV_MASTER}: non-ASCII survives the round trip. */
    static final String PROXY = "{[name=master,cipher=AES/GCM/NoPadding,version=4.2]"
            + "TfFn/0kDlJu9MQ7IXvMKtbajYpbjOn/3XopyYWuFjH3/RBJ1si102YzAL35PnsS3AUbRw0Uoo/4xQU1z}";

    /** {@code jk-fixture-file-secret} under the master {@code jk-fixture-file-master}. */
    static final String FILE_SECRET = "{[name=master,cipher=AES/GCM/NoPadding,version=4.2]"
            + "+/D4+mhP3gOeewydCzQiYfXCVeesLVCczOIHkoUsfnVFom6pcxyqPbmUdosyvTEkVk3OHQDVCGLRvBjjuSEXh8Xr}";

    /** {@code jk-fixture-gpg-secret} under the master {@code jk-fixture-gpg-master}. */
    static final String GPG_SECRET = "{[name=master,cipher=AES/GCM/NoPadding,version=4.2]"
            + "cAZJvsvP7HRtRXbOg17sp5CEpWkeIRdeZ1WGp4Swtz81TgwO7nQ/eQArN1YhPy4rbzhvPDXZciEVywnhCZ+Q7jw=}";

    /** The master source lookup dispatcher: the password is the variable's value. */
    static final String LOOKUP = "{[name=masterSourceLookup,version=4.2]env:JK_FIXTURE_LOOKUP_PASSWORD}";

    /** {@code settings-security4.xml} exactly as {@code mvnenc init} wrote it. */
    static final String SECURITY4_ENV = """
            <?xml version='1.0' encoding='UTF-8'?>
            <settingsSecurity xmlns="http://codehaus-plexus.github.io/plexus-sec-dispatcher/4.0.0" \
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
            xsi:schemaLocation="http://codehaus-plexus.github.io/plexus-sec-dispatcher/4.0.0 \
            https://codehaus-plexus.github.io/plexus-sec-dispatcher/xsd/settings-security-4.0.0.xsd">
              <modelVersion>4.0</modelVersion>
              <defaultDispatcher>master</defaultDispatcher>
              <configurations>
                <configuration>
                  <name>master</name>
                  <properties>
                    <property>
                      <name>source</name>
                      <value>env:MAVEN_MASTER_PASSWORD</value>
                    </property>
                    <property>
                      <name>cipher</name>
                      <value>AES/GCM/NoPadding</value>
                    </property>
                  </properties>
                </configuration>
              </configurations>
            </settingsSecurity>
            """;

    /** {@link #SECURITY4_ENV} with another master source. */
    static String security4(String source) {
        return SECURITY4_ENV.replace("env:MAVEN_MASTER_PASSWORD", source);
    }

    /** {@code home/.m2/settings-security4.xml} holding {@code body}. */
    private static Path home(Path dir, String body) throws Exception {
        Path m2 = Files.createDirectories(dir.resolve("home/.m2"));
        Files.writeString(m2.resolve(MvnencPasswords.SECURITY_FILE), body);
        return dir.resolve("home");
    }

    private static Path settings(Path dir, String serverPassword, String proxyPassword) throws Exception {
        Path xml = dir.resolve("settings.xml");
        Files.writeString(xml, """
                <settings>
                  <servers>
                    <server><id>nexus</id><username>deployer</username><password>%s</password></server>
                  </servers>
                  <proxies>
                    <proxy><id>corp</id><host>proxy.corp</host><port>3128</port>
                      <username>me</username><password>%s</password></proxy>
                  </proxies>
                </settings>
                """.formatted(serverPassword, proxyPassword));
        return xml;
    }

    private static SettingsValues values(Path home, Map<String, String> env) {
        return new SettingsValues(
                env::get, name -> name.equals("user.home") ? home.toString() : System.getProperty(name));
    }

    private static MavenSettings read(Path xml, Path home, Map<String, String> env) {
        return SettingsXml.read(SettingsXml.parse(xml), values(home, env));
    }

    private static @Nullable String server(MavenSettings settings) {
        return settings.server("nexus").map(MavenSettings.Server::password).orElse(null);
    }

    @Test
    void mvnenc_values_are_recognised_and_maven_3_values_are_not() {
        assertThat(MvnencPasswords.encrypted(SERVER)).isTrue();
        assertThat(MvnencPasswords.encrypted(LOOKUP)).isTrue();
        assertThat(MvnencPasswords.encrypted("{COQLCE6DU6GtcS5P=}")).isFalse();
        assertThat(MvnencPasswords.encrypted("comment " + SERVER))
                .as("text outside the braces")
                .isFalse();
        assertThat(MvnencPasswords.encrypted("{[name=master]abc}"))
                .as("no version")
                .isFalse();
        assertThat(MvnencPasswords.encrypted("plain")).isFalse();
    }

    @Test
    void the_aes_gcm_ciphertexts_decrypt_to_their_plaintexts() throws Exception {
        assertThat(MvnencPasswords.aesGcm(payload(SERVER), ENV_MASTER)).isEqualTo("jk-fixture-server-password");
        assertThat(MvnencPasswords.aesGcm(payload(PROXY), ENV_MASTER)).isEqualTo("proxy p@ss/wörd");
        assertThat(MvnencPasswords.aesGcm(payload(FILE_SECRET), "jk-fixture-file-master"))
                .isEqualTo("jk-fixture-file-secret");
        assertThat(MvnencPasswords.aesGcm(payload(GPG_SECRET), "jk-fixture-gpg-master"))
                .isEqualTo("jk-fixture-gpg-secret");
    }

    @Test
    void the_wrong_master_does_not_decrypt() {
        assertThatThrownBy(() -> MvnencPasswords.aesGcm(payload(SERVER), "not-the-master"))
                .isInstanceOf(MavenPasswords.Undecryptable.class)
                .hasMessageNotContaining("not-the-master");
    }

    private static String payload(String value) {
        return value.substring(value.indexOf(']') + 1, value.length() - 1);
    }

    @Test
    void server_and_proxy_passwords_decrypt_with_the_env_master_of_the_request(@TempDir Path dir) throws Exception {
        Path home = home(dir, SECURITY4_ENV);
        MavenSettings settings = read(settings(dir, SERVER, PROXY), home, Map.of("MAVEN_MASTER_PASSWORD", ENV_MASTER));

        assertThat(server(settings)).isEqualTo("jk-fixture-server-password");
        assertThat(settings.proxies()).singleElement().satisfies(p -> assertThat(p.password())
                .isEqualTo("proxy p@ss/wörd"));
        assertThat(settings.warnings()).isEmpty();
    }

    @Test
    void the_security_file_the_request_names_is_read_instead_of_the_homes(@TempDir Path dir) throws Exception {
        Path home = home(dir, security4("env:NOT_THIS_ONE"));
        Path named = dir.resolve("elsewhere.xml");
        Files.writeString(named, SECURITY4_ENV);
        MavenSettings settings = read(
                settings(dir, SERVER, "plain"),
                home,
                Map.of(MvnencPasswords.SECURITY_ENV, named.toString(), "MAVEN_MASTER_PASSWORD", ENV_MASTER));

        assertThat(server(settings)).isEqualTo("jk-fixture-server-password");
    }

    @Test
    void an_unset_env_master_drops_the_server_with_a_warning_naming_the_variable(@TempDir Path dir) throws Exception {
        Path home = home(dir, SECURITY4_ENV);
        MavenSettings settings = read(settings(dir, SERVER, "plain"), home, Map.of());

        assertThat(settings.server("nexus")).isEmpty();
        assertThat(settings.warnings())
                .singleElement()
                .asString()
                .contains("<server> `nexus`")
                .contains("env:MAVEN_MASTER_PASSWORD")
                .contains("MAVEN_MASTER_PASSWORD is not set in the shell that ran jk")
                .doesNotContain("5YUIPYP8");
    }

    @Test
    void a_missing_security_file_is_named_in_the_warning(@TempDir Path dir) throws Exception {
        MavenSettings settings = read(settings(dir, SERVER, "plain"), dir, Map.of());

        assertThat(settings.warnings()).singleElement().asString().contains(MvnencPasswords.SECURITY_FILE);
    }

    @Test
    void a_file_master_source_reads_its_first_line_that_is_not_a_comment(@TempDir Path dir) throws Exception {
        Path master = dir.resolve("master.txt");
        Files.writeString(master, "# jk fixture master\njk-fixture-file-master\n");
        Path home = home(dir, security4("file:" + master.toAbsolutePath()));

        assertThat(server(read(settings(dir, FILE_SECRET, "plain"), home, Map.of())))
                .isEqualTo("jk-fixture-file-secret");
    }

    @Test
    void a_relative_file_master_source_is_refused_as_maven_refuses_it(@TempDir Path dir) throws Exception {
        Path home = home(dir, security4("file:master.txt"));
        MavenSettings settings = read(settings(dir, FILE_SECRET, "plain"), home, Map.of());

        assertThat(settings.server("nexus")).isEmpty();
        assertThat(settings.warnings()).singleElement().asString().contains("not an absolute path");
    }

    @Test
    void the_lookup_dispatcher_reads_the_source_its_payload_names(@TempDir Path dir) throws Exception {
        MavenSettings settings = read(
                settings(dir, LOOKUP, "plain"), dir, Map.of("JK_FIXTURE_LOOKUP_PASSWORD", "jk-fixture-lookup-secret"));

        assertThat(server(settings)).isEqualTo("jk-fixture-lookup-secret");
    }

    /** Headless is the normal case: a source that would ask someone is reported, never started. */
    @Test
    void an_interactive_or_jvm_bound_master_source_is_reported_not_used(@TempDir Path dir) throws Exception {
        for (String source :
                List.of("console-prompt", "pinentry-prompt:/usr/bin/pinentry", "onepassword:op://vault/maven/pw")) {
            Path home = home(dir.resolve(source.substring(0, 4)), security4(source));
            MavenSettings settings = read(settings(dir, SERVER, "plain"), home, Map.of());
            assertThat(settings.server("nexus")).as(source).isEmpty();
            assertThat(settings.warnings())
                    .as(source)
                    .singleElement()
                    .asString()
                    .contains("asks for the password interactively, and jk never prompts");
        }
        Path home = home(dir.resolve("sysprop"), security4("system-property:maven.master"));
        assertThat(read(settings(dir, SERVER, "plain"), home, Map.of()).warnings())
                .singleElement()
                .asString()
                .contains("system property of Maven's own JVM");
    }

    @Test
    void an_unknown_dispatcher_is_reported(@TempDir Path dir) throws Exception {
        MavenSettings settings = read(settings(dir, "{[name=vault,version=4.2]abc}", "plain"), dir, Map.of());

        assertThat(settings.server("nexus")).isEmpty();
        assertThat(settings.warnings()).singleElement().asString().contains("`vault`");
    }

    /** A stand-in gpg-agent on a Unix socket: greets, then answers one request with {@code reply}. */
    private record Agent(Path socket, List<String> heard, Thread thread) implements AutoCloseable {

        static Agent serving(Path dir, String reply) throws Exception {
            Path socket = dir.resolve("S.gpg-agent");
            ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            List<String> heard = new CopyOnWriteArrayList<>();
            Thread thread = Thread.ofPlatform().daemon().start(() -> {
                try (server;
                        SocketChannel client = server.accept()) {
                    OutputStream out = Channels.newOutputStream(client);
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(Channels.newInputStream(client), StandardCharsets.UTF_8));
                    out.write("OK Pleased to meet you\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    heard.add(in.readLine());
                    out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception closed) {
                    // the test is over
                }
            });
            return new Agent(socket, heard, thread);
        }

        @Override
        public void close() throws Exception {
            thread.join(5_000);
        }
    }

    @Test
    void a_gpg_agent_master_source_is_asked_never_to_prompt_and_its_cached_passphrase_is_used(@TempDir Path dir)
            throws Exception {
        String hex = Hashing.hex("jk-fixture-gpg-master".getBytes(StandardCharsets.UTF_8));
        try (Agent agent = Agent.serving(dir, "OK " + hex.toUpperCase(Locale.ROOT))) {
            // No ?non-interactive: jk asks with --no-ask whatever the configuration says.
            Path home = home(dir, security4("gpg-agent:" + agent.socket()));
            SettingsValues values = values(home, Map.of());
            MavenSettings settings = SettingsXml.read(SettingsXml.parse(settings(dir, GPG_SECRET, "plain")), values);

            assertThat(server(settings)).isEqualTo("jk-fixture-gpg-secret");
            agent.close();
            assertThat(agent.heard())
                    .singleElement()
                    .asString()
                    .startsWith("GET_PASSPHRASE --no-ask " + MvnencPasswords.GPG_CACHE_ID + " ");
            assertThat(values.stillHolds(values(home, Map.of())))
                    .as("an agent's cache can empty without any file changing, so the next request asks again")
                    .isFalse();
        }
    }

    @Test
    void a_gpg_agent_with_nothing_cached_is_reported_and_the_server_dropped(@TempDir Path dir) throws Exception {
        try (Agent agent = Agent.serving(dir, "ERR 67108922 No data <GPG Agent>")) {
            Path home = home(dir, security4("gpg-agent:" + agent.socket() + "?non-interactive"));
            MavenSettings settings = read(settings(dir, GPG_SECRET, "plain"), home, Map.of());

            assertThat(settings.server("nexus")).isEmpty();
            assertThat(settings.warnings())
                    .singleElement()
                    .asString()
                    .contains("holds no cached master password")
                    .contains("jk never asks the agent to prompt");
        }
    }

    @Test
    void a_gpg_agent_that_is_not_running_is_reported(@TempDir Path dir) throws Exception {
        Path home = home(dir, security4("gpg-agent:" + dir.resolve("no-agent-here")));
        MavenSettings settings = read(settings(dir, GPG_SECRET, "plain"), home, Map.of());

        assertThat(settings.warnings()).singleElement().asString().contains("gpg-agent did not answer");
    }
}
