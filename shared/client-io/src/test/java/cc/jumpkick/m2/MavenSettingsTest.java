// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.m2;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenSettingsTest {

    private static final URI CENTRAL = RepositorySpec.MAVEN_CENTRAL.url();
    private static final URI NEXUS = URI.create("https://nexus.example/repository/maven-public/");

    private static Path write(Path dir, String xml) throws Exception {
        return write(dir, "settings.xml", xml);
    }

    private static Path write(Path dir, String name, String xml) throws Exception {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, xml);
        return p;
    }

    private static MavenSettings.Mirror mirror(String mirrorOf) {
        return new MavenSettings.Mirror("m", mirrorOf, NEXUS, Path.of("settings.xml"));
    }

    @Test
    void reads_servers_by_id(@TempDir Path dir) throws Exception {
        Path xml = write(dir, """
                <settings>
                  <servers>
                    <server>
                      <id>corp-nexus</id>
                      <username>deployer</username>
                      <password>s3cr3t</password>
                    </server>
                    <server>
                      <id>ghp</id>
                      <username>octocat</username>
                      <password>ghp_token</password>
                    </server>
                  </servers>
                </settings>
                """);

        MavenSettings settings = MavenSettings.loadFrom(xml);
        assertThat(settings.server("corp-nexus")).hasValueSatisfying(s -> {
            assertThat(s.username()).isEqualTo("deployer");
            assertThat(s.password()).isEqualTo("s3cr3t");
        });
        assertThat(settings.server("ghp"))
                .hasValueSatisfying(s -> assertThat(s.username()).isEqualTo("octocat"));
        assertThat(settings.server("unknown")).isEmpty();
        assertThat(settings.files()).containsExactly(xml);
    }

    @Test
    void missing_file_is_empty(@TempDir Path dir) {
        MavenSettings settings = MavenSettings.loadFrom(dir.resolve("nope.xml"));
        assertThat(settings.isEmpty()).isTrue();
        assertThat(settings.files()).isEmpty();
    }

    @Test
    void malformed_xml_degrades_to_empty(@TempDir Path dir) throws Exception {
        Path xml = write(dir, "<settings><servers><server><id>x");
        assertThat(MavenSettings.loadFrom(xml).isEmpty()).isTrue();
    }

    @Test
    void server_without_credentials_is_skipped(@TempDir Path dir) throws Exception {
        Path xml = write(dir, """
                <settings>
                  <servers>
                    <server>
                      <id>ssh-only</id>
                      <privateKey>/home/me/.ssh/id_rsa</privateKey>
                    </server>
                  </servers>
                </settings>
                """);
        assertThat(MavenSettings.loadFrom(xml).server("ssh-only")).isEmpty();
    }

    @Test
    void does_not_resolve_external_entities(@TempDir Path dir) throws Exception {
        // XXE attempt: a DOCTYPE with an external entity. Hardened parser must
        // refuse the DOCTYPE outright (→ empty), never read /etc/hostname.
        Path xml = write(dir, """
                <?xml version="1.0"?>
                <!DOCTYPE settings [ <!ENTITY xxe SYSTEM "file:///etc/hostname"> ]>
                <settings><servers><server>
                  <id>evil</id><username>&xxe;</username><password>p</password>
                </server></servers></settings>
                """);
        assertThat(MavenSettings.loadFrom(xml).isEmpty()).isTrue();
    }

    // ---- mirrors ------------------------------------------------------------------------------

    @Test
    void reads_mirrors_in_order_and_the_first_match_wins(@TempDir Path dir) throws Exception {
        Path xml = write(dir, """
                <settings>
                  <mirrors>
                    <mirror>
                      <id>central-only</id>
                      <mirrorOf>central</mirrorOf>
                      <url>https://nexus.example/repository/central/</url>
                    </mirror>
                    <mirror>
                      <id>everything</id>
                      <mirrorOf>*</mirrorOf>
                      <url>https://nexus.example/repository/maven-public/</url>
                    </mirror>
                    <mirror>
                      <id>no-url</id>
                      <mirrorOf>*</mirrorOf>
                    </mirror>
                  </mirrors>
                </settings>
                """);

        MavenSettings settings = MavenSettings.loadFrom(xml);
        assertThat(settings.mirrors())
                .extracting(MavenSettings.Mirror::id)
                .containsExactly("central-only", "everything");
        assertThat(settings.mirrorFor("central", CENTRAL))
                .hasValueSatisfying(m -> assertThat(m.id()).isEqualTo("central-only"));
        assertThat(settings.mirrorFor("google", RepositorySpec.GOOGLE_MAVEN.url()))
                .hasValueSatisfying(m -> assertThat(m.id()).isEqualTo("everything"));
        assertThat(settings.mirrors().getFirst().label()).isEqualTo("mirror `central-only` (" + xml + ")");
    }

    @Test
    void mirrorOf_exact_id_matches_that_repository_alone() {
        assertThat(mirror("central").matches("central", CENTRAL)).isTrue();
        assertThat(mirror("central").matches("google", RepositorySpec.GOOGLE_MAVEN.url()))
                .isFalse();
        assertThat(mirror(" central ").matches("central", CENTRAL)).isTrue();
    }

    @Test
    void mirrorOf_star_matches_every_repository_including_local_ones() {
        assertThat(mirror("*").matches("central", CENTRAL)).isTrue();
        assertThat(mirror("*").matches("stub", URI.create("http://127.0.0.1:8081/repo/")))
                .isTrue();
        assertThat(mirror("*").matches("disk", URI.create("file:///srv/repo/"))).isTrue();
    }

    @Test
    void mirrorOf_external_star_skips_loopback_and_file_repositories() {
        MavenSettings.Mirror external = mirror("external:*");
        assertThat(external.matches("central", CENTRAL)).isTrue();
        assertThat(external.matches("plain", URI.create("http://repo.example/m2/")))
                .isTrue();
        assertThat(external.matches("stub", URI.create("http://127.0.0.1:8081/repo/")))
                .isFalse();
        assertThat(external.matches("local", URI.create("http://localhost/repo/")))
                .isFalse();
        assertThat(external.matches("disk", URI.create("file:///srv/repo/"))).isFalse();
    }

    @Test
    void mirrorOf_external_http_star_matches_plaintext_remote_repositories_only() {
        MavenSettings.Mirror externalHttp = mirror("external:http:*");
        assertThat(externalHttp.matches("plain", URI.create("http://repo.example/m2/")))
                .isTrue();
        assertThat(externalHttp.matches("central", CENTRAL)).isFalse();
        assertThat(externalHttp.matches("stub", URI.create("http://127.0.0.1:8081/repo/")))
                .isFalse();
    }

    @Test
    void mirrorOf_lists_and_exclusions_follow_maven() {
        assertThat(mirror("central,google").matches("google", RepositorySpec.GOOGLE_MAVEN.url()))
                .isTrue();
        assertThat(mirror("central, google").matches("jumpkick", RepositorySpec.JUMPKICK.url()))
                .isFalse();
        MavenSettings.Mirror allButJumpkick = mirror("*,!jumpkick");
        assertThat(allButJumpkick.matches("central", CENTRAL)).isTrue();
        assertThat(allButJumpkick.matches("jumpkick", RepositorySpec.JUMPKICK.url()))
                .isFalse();
        // An exclusion vetoes a wildcard wherever it stands in the list.
        assertThat(mirror("!jumpkick,*").matches("jumpkick", RepositorySpec.JUMPKICK.url()))
                .isFalse();
        assertThat(mirror("external:*,!central").matches("central", CENTRAL)).isFalse();
    }

    // ---- proxies ------------------------------------------------------------------------------

    @Test
    void reads_active_proxies_with_maven_defaults_and_selects_by_protocol(@TempDir Path dir) throws Exception {
        Path xml = write(dir, """
                <settings>
                  <proxies>
                    <proxy>
                      <id>old</id>
                      <active>false</active>
                      <host>gone.example</host>
                    </proxy>
                    <proxy>
                      <id>corp</id>
                      <protocol>https</protocol>
                      <host>proxy.example</host>
                      <port>3128</port>
                      <username>alice</username>
                      <password>s3cr:et</password>
                      <nonProxyHosts>*.example.internal|localhost|nexus.example</nonProxyHosts>
                    </proxy>
                    <proxy>
                      <host>plain.example</host>
                    </proxy>
                  </proxies>
                </settings>
                """);

        MavenSettings settings = MavenSettings.loadFrom(xml);
        assertThat(settings.proxies()).extracting(MavenSettings.Proxy::id).containsExactly("corp", "default");

        MavenSettings.Proxy corp = settings.proxyFor("https").orElseThrow();
        assertThat(corp.host()).isEqualTo("proxy.example");
        assertThat(corp.port()).isEqualTo(3128);
        assertThat(corp.url().toString()).isEqualTo("http://alice:s3cr:et@proxy.example:3128");
        assertThat(corp.url().getUserInfo()).isEqualTo("alice:s3cr:et");
        assertThat(corp.bypasses("build.example.internal")).isTrue();
        assertThat(corp.bypasses("LOCALHOST")).isTrue();
        assertThat(corp.bypasses("nexus.example")).isTrue();
        assertThat(corp.bypasses("repo.maven.apache.org")).isFalse();
        assertThat(corp.bypasses("nexus.example.org")).isFalse();

        MavenSettings.Proxy plain = settings.proxyFor("http").orElseThrow();
        assertThat(plain.protocol()).isEqualTo("http");
        assertThat(plain.port()).isEqualTo(8080);
        assertThat(plain.username()).isNull();
        assertThat(plain.nonProxyHosts()).isEmpty();
    }

    // ---- profiles -----------------------------------------------------------------------------

    @Test
    void active_profile_repositories_join_with_their_policy_and_inactive_ones_do_not(@TempDir Path dir)
            throws Exception {
        Path xml = write(dir, """
                <settings>
                  <profiles>
                    <profile>
                      <id>corp</id>
                      <repositories>
                        <repository>
                          <id>corp-releases</id>
                          <url>https://nexus.example/repository/releases/</url>
                          <snapshots><enabled>false</enabled></snapshots>
                        </repository>
                        <repository>
                          <id>corp-snapshots</id>
                          <url>https://nexus.example/repository/snapshots/</url>
                          <releases><enabled>false</enabled></releases>
                        </repository>
                      </repositories>
                    </profile>
                    <profile>
                      <id>lab</id>
                      <repositories>
                        <repository>
                          <id>lab</id>
                          <url>https://lab.example/m2/</url>
                        </repository>
                      </repositories>
                    </profile>
                  </profiles>
                  <activeProfiles>
                    <activeProfile>corp</activeProfile>
                  </activeProfiles>
                </settings>
                """);

        MavenSettings settings = MavenSettings.loadFrom(xml);
        assertThat(settings.profileRepositories())
                .extracting(RepositorySpec::name)
                .containsExactly("corp-releases", "corp-snapshots");
        RepositorySpec releases = settings.profileRepositories().getFirst();
        assertThat(releases.releases()).isTrue();
        assertThat(releases.snapshots()).isFalse();
        RepositorySpec snapshots = settings.profileRepositories().get(1);
        assertThat(snapshots.releases()).isFalse();
        assertThat(snapshots.snapshots()).isTrue();
        assertThat(settings.declaredUrl("corp-releases"))
                .contains(URI.create("https://nexus.example/repository/releases/"));
        assertThat(settings.declaredUrl("lab")).isEmpty();
    }

    @Test
    void an_active_by_default_profile_is_active_unless_another_profile_is_listed(@TempDir Path dir) throws Exception {
        String profiles = """
                  <profiles>
                    <profile>
                      <id>default</id>
                      <activation><activeByDefault>true</activeByDefault></activation>
                      <repositories><repository><id>dflt</id><url>https://d.example/</url></repository></repositories>
                    </profile>
                    <profile>
                      <id>other</id>
                      <repositories><repository><id>oth</id><url>https://o.example/</url></repository></repositories>
                    </profile>
                  </profiles>
                """;
        Path alone = write(dir, "alone/settings.xml", "<settings>" + profiles + "</settings>");
        assertThat(MavenSettings.loadFrom(alone).profileRepositories())
                .extracting(RepositorySpec::name)
                .containsExactly("dflt");

        Path listed = write(
                dir,
                "listed/settings.xml",
                "<settings>" + profiles
                        + "<activeProfiles><activeProfile>other</activeProfile></activeProfiles></settings>");
        assertThat(MavenSettings.loadFrom(listed).profileRepositories())
                .extracting(RepositorySpec::name)
                .containsExactly("oth");
    }

    // ---- two files ----------------------------------------------------------------------------

    @Test
    void the_user_file_wins_over_the_installation_file_by_id_and_active_profiles_are_the_union(@TempDir Path dir)
            throws Exception {
        Path user = write(dir, "home/.m2/settings.xml", """
                <settings>
                  <servers><server><id>nexus</id><username>me</username><password>mine</password></server></servers>
                  <mirrors>
                    <mirror><id>nexus</id><mirrorOf>central</mirrorOf><url>https://mine.example/m2/</url></mirror>
                  </mirrors>
                  <activeProfiles><activeProfile>team</activeProfile></activeProfiles>
                </settings>
                """);
        Path global = write(dir, "maven/conf/settings.xml", """
                <settings>
                  <servers><server><id>nexus</id><username>shared</username><password>theirs</password></server></servers>
                  <mirrors>
                    <mirror><id>nexus</id><mirrorOf>*</mirrorOf><url>https://shared.example/m2/</url></mirror>
                    <mirror><id>google</id><mirrorOf>google</mirrorOf><url>https://shared.example/google/</url></mirror>
                  </mirrors>
                  <proxies><proxy><id>corp</id><host>proxy.example</host></proxy></proxies>
                  <profiles>
                    <profile>
                      <id>team</id>
                      <repositories><repository><id>team</id><url>https://team.example/m2/</url></repository></repositories>
                    </profile>
                  </profiles>
                </settings>
                """);

        Map<String, String> env = Map.of(
                MavenSettings.SETTINGS_ENV,
                user.toString(),
                "M2_HOME",
                dir.resolve("maven").toString());
        MavenSettings merged = MavenSettings.load(env::get);

        assertThat(merged.files()).containsExactly(user, global);
        assertThat(merged.server("nexus"))
                .hasValueSatisfying(s -> assertThat(s.username()).isEqualTo("me"));
        assertThat(merged.mirrors()).extracting(MavenSettings.Mirror::id).containsExactly("nexus", "google");
        assertThat(merged.mirrorFor("central", CENTRAL))
                .hasValueSatisfying(m -> assertThat(m.url()).hasToString("https://mine.example/m2/"));
        assertThat(merged.mirrorFor("jumpkick", RepositorySpec.JUMPKICK.url())).isEmpty();
        assertThat(merged.proxyFor("http"))
                .hasValueSatisfying(p -> assertThat(p.host()).isEqualTo("proxy.example"));
        // The user's <activeProfiles> turns on a profile the installation file declares.
        assertThat(merged.profileRepositories())
                .extracting(RepositorySpec::name)
                .containsExactly("team");
    }

    @Test
    void the_user_file_is_the_property_then_the_variable_then_the_home(@TempDir Path dir) {
        Map<String, String> env =
                Map.of(MavenSettings.SETTINGS_ENV, dir.resolve("from-env.xml").toString());
        assertThat(MavenSettings.userSettingsPath(env::get)).isEqualTo(dir.resolve("from-env.xml"));
        assertThat(MavenSettings.userSettingsPath(k -> null))
                .isEqualTo(Path.of(System.getProperty("user.home"), ".m2", "settings.xml"));
        assertThat(MavenSettings.globalSettingsPath(k -> null)).isNull();
        assertThat(MavenSettings.globalSettingsPath(Map.of("MAVEN_HOME", dir.toString())::get))
                .isEqualTo(dir.resolve("conf").resolve("settings.xml"));
    }
}
