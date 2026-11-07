// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.m2;

import static cc.jumpkick.m2.MavenPasswordsTest.MASTER;
import static cc.jumpkick.m2.MavenPasswordsTest.SERVER_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Encrypted {@code settings.xml} passwords as a Maven user has them: the master password in
 * {@code settings-security.xml}, found the way Maven finds it, and a value that cannot be
 * decrypted dropped with a warning rather than sent. Ciphertexts are the published known answers
 * in {@link MavenPasswordsTest}.
 */
class SettingsDecryptionTest {

    private static Path settings(Path dir, String password) throws Exception {
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
                """.formatted(password, password));
        return xml;
    }

    private static Path security(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<settingsSecurity>" + body + "</settingsSecurity>");
        return file;
    }

    /** {@code xml} read with {@code home} as {@code user.home} and {@code env} as the request's shell. */
    private static MavenSettings read(Path xml, Path home, Map<String, String> env) {
        SettingsValues values = new SettingsValues(env::get, name -> property(name, home));
        return SettingsXml.read(SettingsXml.parse(xml), values);
    }

    private static @Nullable String property(String name, Path home) {
        return name.equals("user.home") ? home.toString() : System.getProperty(name);
    }

    private static @Nullable String password(MavenSettings settings) {
        return settings.server("nexus").map(MavenSettings.Server::password).orElse(null);
    }

    @Test
    void a_password_decrypts_with_the_master_of_the_home_security_file(@TempDir Path dir) throws Exception {
        Path home = dir.resolve("home");
        security(home.resolve(".m2").resolve("settings-security.xml"), "<master>" + MASTER + "</master>");

        MavenSettings settings = read(settings(dir, SERVER_PASSWORD), home, Map.of());

        assertThat(password(settings)).isEqualTo("testtest");
        assertThat(settings.proxyFor("http"))
                .hasValueSatisfying(p -> assertThat(p.password()).isEqualTo("testtest"));
        assertThat(settings.warnings()).isEmpty();
    }

    @Test
    void text_around_the_braces_is_ignored(@TempDir Path dir) throws Exception {
        Path home = dir.resolve("home");
        security(home.resolve(".m2").resolve("settings-security.xml"), "<master>" + MASTER + "</master>");

        MavenSettings settings = read(settings(dir, "rotated in October " + SERVER_PASSWORD), home, Map.of());

        assertThat(password(settings)).isEqualTo("testtest");
    }

    @Test
    void the_security_file_variable_names_another_file(@TempDir Path dir) throws Exception {
        Path elsewhere = security(dir.resolve("vault").resolve("sec.xml"), "<master>" + MASTER + "</master>");

        MavenSettings settings = read(
                settings(dir, SERVER_PASSWORD),
                dir.resolve("empty-home"),
                Map.of(MavenPasswords.SECURITY_ENV, elsewhere.toString()));

        assertThat(password(settings)).isEqualTo("testtest");
    }

    @Test
    void a_relocated_security_file_is_followed(@TempDir Path dir) throws Exception {
        Path home = dir.resolve("home");
        Path moved = security(dir.resolve("usb").resolve("settings-security.xml"), "<master>" + MASTER + "</master>");
        security(home.resolve(".m2").resolve("settings-security.xml"), "<relocation>" + moved + "</relocation>");

        assertThat(password(read(settings(dir, SERVER_PASSWORD), home, Map.of())))
                .isEqualTo("testtest");
    }

    @Test
    void a_missing_security_file_warns_and_the_repository_goes_anonymous(@TempDir Path dir) throws Exception {
        Path xml = settings(dir, SERVER_PASSWORD);

        MavenSettings settings = read(xml, dir.resolve("home"), Map.of());

        assertThat(settings.server("nexus")).isEmpty();
        assertThat(settings.proxyFor("http")).hasValueSatisfying(p -> {
            assertThat(p.username()).isNull();
            assertThat(p.password()).isNull();
        });
        assertThat(settings.warnings()).anySatisfy(w -> assertThat(w)
                .startsWith("jk: warning: <server> `nexus` in " + xml + " has an encrypted <password> jk"
                        + " cannot decrypt: there is no "
                        + dir.resolve("home").resolve(".m2").resolve("settings-security.xml"))
                .endsWith("; the repository is accessed anonymously")
                .doesNotContain(SERVER_PASSWORD));
    }

    @Test
    void a_master_that_does_not_decrypt_warns_naming_the_server_and_the_file(@TempDir Path dir) throws Exception {
        Path home = dir.resolve("home");
        Path sec = security(
                home.resolve(".m2").resolve("settings-security.xml"),
                "<master>{" + MavenPasswordsTest.PBE_CIPHER + "}</master>");
        Path xml = settings(dir, SERVER_PASSWORD);

        MavenSettings settings = read(xml, home, Map.of());

        assertThat(settings.server("nexus")).isEmpty();
        assertThat(settings.warnings()).anySatisfy(w -> assertThat(w)
                .contains("<server> `nexus` in " + xml)
                .contains("the <master> in " + sec + " does not decrypt"));
    }

    @Test
    void a_plain_password_needs_no_security_file(@TempDir Path dir) throws Exception {
        MavenSettings settings = read(settings(dir, "plain-pw"), dir.resolve("home"), Map.of());

        assertThat(password(settings)).isEqualTo("plain-pw");
        assertThat(settings.warnings()).isEmpty();
    }

    @Test
    void an_expanded_value_is_decrypted_after_expansion(@TempDir Path dir) throws Exception {
        Path home = dir.resolve("home");
        security(home.resolve(".m2").resolve("settings-security.xml"), "<master>" + MASTER + "</master>");

        MavenSettings settings =
                read(settings(dir, "${env.NEXUS_PASSWORD}"), home, Map.of("NEXUS_PASSWORD", SERVER_PASSWORD));

        assertThat(password(settings)).isEqualTo("testtest");
    }
}
