// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.forge.ForgeAuth;
import cc.jumpkick.forge.TokenStore;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.MirrorOf;
import cc.jumpkick.model.RepositoryMirror;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.testing.LoopbackHttp;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A mirror — a {@code mirror-of} table of {@code ~/.jk/config.toml} or a settings.xml {@code
 * <mirror>} — is a transport rewrite: the repository's requests open at the mirror, and its name,
 * URL and store — what the lock and the cache key on — are its own. A mirror never goes direct:
 * one jk refuses, one nothing answers at and one that refuses access each fail the resolve naming
 * the mirror, and the repository is not asked at its own URL instead.
 */
class MavenRepoMirrorTest {

    @BeforeAll
    static void isolateM2(@TempDir Path m2) {
        System.setProperty("jk.m2.local", m2.toString());
    }

    /** The repository as the project names it: it serves nothing, the way a blocked Central serves nothing. */
    @RegisterExtension
    final LoopbackHttp origin = new LoopbackHttp();

    /** The mirror standing in for it. */
    @RegisterExtension
    final LoopbackHttp nexus = new LoopbackHttp();

    @BeforeEach
    @AfterEach
    void reset() {
        RunNotices.clear();
        SessionContext.reset();
    }

    private static final Coordinate WIDGET = Coordinate.of("com.example", "widget", "1.0");
    private static final byte[] POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>widget</artifactId>
              <version>1.0</version>
            </project>
            """.getBytes(StandardCharsets.UTF_8);

    private static MavenSettings.Mirror mirror(String id, String mirrorOf, URI url) {
        return new MavenSettings.Mirror(id, mirrorOf, url, Path.of("/home/me/.m2/settings.xml"));
    }

    private static MavenSettings settings(MavenSettings.Mirror mirror, Path dir) throws Exception {
        Path xml = dir.resolve("settings.xml");
        Files.writeString(xml, """
                <settings>
                  <servers><server><id>%s</id><username>u</username><password>p</password></server></servers>
                  <mirrors><mirror><id>%s</id><mirrorOf>%s</mirrorOf><url>%s</url></mirror></mirrors>
                </settings>
                """.formatted(mirror.id(), mirror.id(), mirror.mirrorOf(), mirror.url()));
        return MavenSettings.loadFrom(xml);
    }

    /** No shell, no login store, no forge token: the resolver reads only {@code settings}. */
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

    @Test
    void requests_open_at_the_mirror_while_name_url_and_store_stay_the_repositorys_own(@TempDir Path dir)
            throws Exception {
        nexus.serve("/com/example/widget/1.0/widget-1.0.pom", new String(POM, StandardCharsets.UTF_8));
        MavenSettings settings = settings(mirror("nexus", "central", nexus.base()), dir);
        MavenRepo central = new MavenRepo("central", origin.base(), new Http(), new Cas(dir.resolve("cas")));

        MavenRepo mirrored = RepoMirrors.apply(central, List.of(), settings, creds(settings, dir));

        assertThat(mirrored).isNotSameAs(central);
        assertThat(mirrored.name()).isEqualTo("central");
        assertThat(mirrored.baseUrl()).isEqualTo(central.baseUrl());
        assertThat(mirrored.storeDir()).isEqualTo(central.storeDir());
        assertThat(mirrored.mirror()).hasValueSatisfying(m -> {
            assertThat(m.id()).isEqualTo("nexus");
            assertThat(m.url()).isEqualTo(nexus.base());
            assertThat(m.credential()).isEqualTo(new RepoCredential.Basic("u", "p"));
        });

        MavenRepo.Fetched fetched = mirrored.fetchPom(WIDGET);

        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(POM));
        assertThat(fetched.url().toString()).startsWith(nexus.base().toString());
        assertThat(nexus.requested()).contains("/com/example/widget/1.0/widget-1.0.pom");
        assertThat(nexus.headersFor("/com/example/widget/1.0/widget-1.0.pom"))
                .get()
                .extracting(h -> h.get("Authorization"))
                .as("the mirror's own <server> credential rides the request")
                .isNotNull();
        assertThat(origin.requested()).isEmpty();
        // The bytes land in the repository's own store, keyed by its origin, not the mirror's.
        assertThat(fetched.cachePath()).startsWith(central.storeDir());
    }

    @Test
    void a_repository_no_mirror_matches_is_returned_as_it_is(@TempDir Path dir) throws Exception {
        MavenSettings settings = settings(mirror("nexus", "central", nexus.base()), dir);
        MavenRepo google = new MavenRepo("google", origin.base(), new Http(), new Cas(dir.resolve("cas")));

        assertThat(RepoMirrors.apply(google, List.of(), settings, creds(settings, dir)))
                .isSameAs(google);
    }

    @Test
    void a_plaintext_settings_xml_mirror_on_a_network_path_fails_the_resolve_and_the_origin_is_not_asked(
            @TempDir Path dir) throws Exception {
        MavenSettings.Mirror plaintext = mirror("nexus", "*", URI.create("http://nexus.example/repository/public/"));
        MavenSettings settings = settings(plaintext, dir);
        origin.serve("/com/example/widget/1.0/widget-1.0.pom", new String(POM, StandardCharsets.UTF_8));
        MavenRepo central =
                new MavenRepo("central", origin.base(), new Http(), new Cas(dir.resolve("cas"))).withOptional(true);

        MavenRepo refused = RepoMirrors.apply(central, List.of(), settings, creds(settings, dir));

        assertThat(refused.mirror())
                .hasValueSatisfying(m -> assertThat(m.refusal()).contains("plaintext http"));
        assertThat(refused.passable())
                .as("a mirrored repository is never passed over")
                .isFalse();
        assertThatThrownBy(() -> refused.fetchPom(WIDGET))
                .isInstanceOf(MavenRepo.RepositoryUnreachableException.class)
                .hasMessageContaining("mirror `nexus` (" + dir.resolve("settings.xml") + ")")
                .hasMessageContaining("switch it to https")
                .hasMessageContaining("mirror-of = [\"*\"] and allow-insecure = true")
                .hasMessageContaining("is not asked at " + SafeUri.forMessage(origin.base()));
        assertThat(origin.requested()).isEmpty();
        assertThat(RepoMirrors.refusal(mirror("ok", "*", nexus.base()))).isNull();
        assertThat(RepoMirrors.refusal(mirror("ok", "*", URI.create("https://nexus.example/m2/"))))
                .isNull();
    }

    @Test
    void a_corpMirrormirror_wins_over_a_settings_xml_one_and_carries_its_own_credential(@TempDir Path dir)
            throws Exception {
        LoopbackHttp corp = nexus;
        corp.serve("/com/example/widget/1.0/widget-1.0.pom", new String(POM, StandardCharsets.UTF_8));
        MavenSettings settings = settings(mirror("other", "*", origin.base()), dir);
        RepositoryMirror corpMirror = new RepositoryMirror(
                "corp",
                corp.base(),
                new MirrorOf(List.of("*")),
                new RepoCredential.Bearer("corp-token"),
                false,
                Path.of("/home/me/.jk/config.toml"));
        MavenRepo central = new MavenRepo("central", origin.base(), new Http(), new Cas(dir.resolve("cas")));

        MavenRepo mirrored = RepoMirrors.apply(central, List.of(corpMirror), settings, creds(settings, dir));
        mirrored.fetchPom(WIDGET);

        assertThat(mirrored.mirror()).hasValueSatisfying(m -> {
            assertThat(m.id()).isEqualTo("corp");
            assertThat(m.label()).isEqualTo("mirror `corp` (/home/me/.jk/config.toml)");
            assertThat(m.refusal()).isNull();
        });
        assertThat(corp.headersFor("/com/example/widget/1.0/widget-1.0.pom"))
                .get()
                .extracting(h -> h.get("Authorization"))
                .isEqualTo(List.of("Bearer corp-token"));
        assertThat(origin.requested()).isEmpty();
    }

    @Test
    void a_corpMirrormirror_that_says_allow_insecure_is_used_over_plaintext(@TempDir Path dir) throws Exception {
        RepositoryMirror plaintext = new RepositoryMirror(
                "corp",
                URI.create("http://nexus.example/repository/public/"),
                new MirrorOf(List.of("central")),
                null,
                true,
                Path.of("/home/me/.jk/config.toml"));
        MavenRepo central = new MavenRepo("central", origin.base(), new Http(), new Cas(dir.resolve("cas")));

        MavenRepo mirrored = RepoMirrors.apply(
                central, List.of(plaintext), MavenSettings.empty(), creds(MavenSettings.empty(), dir));

        assertThat(mirrored.fetchBase()).hasToString("http://nexus.example/repository/public/");
        assertThat(mirrored.mirror())
                .hasValueSatisfying(m -> assertThat(m.refusal()).isNull());
        assertThat(mirrored.isPlaintext())
                .as("the lock summary reports it insecure (allowed)")
                .isTrue();
        assertThat(central.isPlaintext()).isFalse();
    }

    @Test
    void a_mirror_nothing_answers_at_fails_naming_it_and_the_repository_is_not_asked_at_its_own_url(@TempDir Path dir)
            throws Exception {
        origin.serve("/com/example/widget/1.0/widget-1.0.pom", new String(POM, StandardCharsets.UTF_8));
        URI dead = nexus.base();
        nexus.stop();
        RepositoryMirror corp = new RepositoryMirror(
                "corp", dead, new MirrorOf(List.of("*")), null, false, Path.of("/home/me/.jk/config.toml"));
        MavenRepo optional =
                new MavenRepo("central", origin.base(), new Http(), new Cas(dir.resolve("cas"))).withOptional(true);

        MavenRepo mirrored =
                RepoMirrors.apply(optional, List.of(corp), MavenSettings.empty(), creds(MavenSettings.empty(), dir));

        assertThatThrownBy(() -> mirrored.fetchPom(WIDGET))
                .isInstanceOf(MavenRepo.RepositoryUnreachableException.class)
                .hasMessageContaining("reached through mirror `corp` (/home/me/.jk/config.toml)")
                .hasMessageContaining("a mirror is never bypassed");
        assertThat(origin.requested()).isEmpty();
    }

    @Test
    void a_mirror_that_refuses_access_fails_naming_it(@TempDir Path dir) throws Exception {
        nexus.server().removeContext("/");
        nexus.server().createContext("/", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        origin.serve("/com/example/widget/1.0/widget-1.0.pom", new String(POM, StandardCharsets.UTF_8));
        RepositoryMirror corp = new RepositoryMirror(
                "corp", nexus.base(), new MirrorOf(List.of("*")), null, false, Path.of("/home/me/.jk/config.toml"));
        MavenRepo central = new MavenRepo("central", origin.base(), new Http(), new Cas(dir.resolve("cas")));

        MavenRepo mirrored =
                RepoMirrors.apply(central, List.of(corp), MavenSettings.empty(), creds(MavenSettings.empty(), dir));

        assertThatThrownBy(() -> mirrored.fetchPom(WIDGET))
                .isInstanceOf(MavenRepo.RepositoryUnreachableException.class)
                .hasMessageContaining("mirror `corp`")
                .hasMessageContaining("HTTP 401");
        assertThat(origin.requested()).isEmpty();
    }

    @Test
    void the_group_notes_each_mirrored_repository_once(@TempDir Path dir) throws Exception {
        MavenSettings settings = settings(mirror("nexus", "*", nexus.base()), dir);
        Cas cas = new Cas(dir.resolve("cas"));
        MavenRepo central = RepoMirrors.apply(
                new MavenRepo("central", URI.create("https://repo.maven.apache.org/maven2/"), new Http(), cas),
                List.of(),
                settings,
                creds(settings, dir));
        MavenRepo plain = new MavenRepo("plain", origin.base(), new Http(), cas);

        List<String> notes = new RepoGroup(List.of(central, plain)).mirrorNotes();

        assertThat(notes)
                .singleElement()
                .asString()
                .isEqualTo("repository `central` is reached through mirror `nexus` (" + dir.resolve("settings.xml")
                        + ") at " + SafeUri.forMessage(nexus.base())
                        + "; the lock records `central` at https://repo.maven.apache.org/maven2/");
    }
}
