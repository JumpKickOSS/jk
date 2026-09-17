// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.forge.ForgeAuth;
import cc.jumpkick.forge.TokenStore;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A settings.xml mirror is a transport rewrite: the repository's requests open at the mirror, and
 * its name, URL and store — what the lock and the cache key on — are its own.
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

        MavenRepo mirrored = RepoMirrors.apply(central, settings, creds(settings, dir));

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

        assertThat(RepoMirrors.apply(google, settings, creds(settings, dir))).isSameAs(google);
    }

    @Test
    void a_plaintext_mirror_on_a_network_path_is_refused_once_naming_the_entry(@TempDir Path dir) throws Exception {
        MavenSettings.Mirror plaintext = mirror("nexus", "*", URI.create("http://nexus.example/repository/public/"));
        MavenSettings settings = settings(plaintext, dir);
        MavenRepo central = new MavenRepo("central", origin.base(), new Http(), new Cas(dir.resolve("cas")));

        var err = new ByteArrayOutputStream();
        Log.install(
                new PrintStream(err, true, StandardCharsets.UTF_8), System.Logger.Level.INFO, UnaryOperator.identity());
        try {
            SessionContext.runWhere(Session.defaults(), () -> {
                assertThat(RepoMirrors.apply(central, settings, creds(settings, dir)))
                        .isSameAs(central);
                assertThat(RepoMirrors.apply(central, settings, creds(settings, dir)))
                        .isSameAs(central);
            });
        } finally {
            Log.install(System.err, System.Logger.Level.INFO, UnaryOperator.identity());
        }
        String warnings = err.toString(StandardCharsets.UTF_8);
        assertThat(warnings)
                .contains("mirror `nexus` (" + dir.resolve("settings.xml") + ") is not used for repository `central`")
                .contains("plaintext http")
                .contains(origin.base().toString());
        assertThat(warnings.split("is not used for repository", -1)).hasSize(2);
        assertThat(RepoMirrors.refusal(mirror("ok", "*", nexus.base()))).isNull();
        assertThat(RepoMirrors.refusal(mirror("ok", "*", URI.create("https://nexus.example/m2/"))))
                .isNull();
    }

    @Test
    void the_group_notes_each_mirrored_repository_once(@TempDir Path dir) throws Exception {
        MavenSettings settings = settings(mirror("nexus", "*", nexus.base()), dir);
        Cas cas = new Cas(dir.resolve("cas"));
        MavenRepo central = RepoMirrors.apply(
                new MavenRepo("central", URI.create("https://repo.maven.apache.org/maven2/"), new Http(), cas),
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
