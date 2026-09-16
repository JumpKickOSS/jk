// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The legs a fetch runs on the io pool — a version-catalog fan-out, a BOM-import fan-out — see the
 * session of the thread that started them: {@code --offline} keeps every leg off the network, and a
 * revalidating lock revalidates on every leg.
 */
class PooledLegSessionTest {

    private static final Coordinate LIB = Coordinate.of("com.example", "lib", "1.0");
    private static final String META = MavenStub.metadataPath("com.example", "lib");

    @RegisterExtension
    final LoopbackHttp first = new LoopbackHttp();

    @RegisterExtension
    final LoopbackHttp second = new LoopbackHttp();

    @BeforeEach
    void seed() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        EffectivePomBuilder.clearProcessCache();
        SessionContext.reset();
    }

    @AfterEach
    void reset() {
        SessionContext.reset();
    }

    @Test
    void a_revalidating_lock_revalidates_every_catalog_of_a_fan_out(@TempDir Path tmp) throws Exception {
        new MavenStub(first).metadata("com.example", "lib", "1.0");
        new MavenStub(second).metadata("com.example", "lib", "1.0", "2.0");
        RepoGroup group = group(tmp);

        // A first walk leaves both catalogs on disk, within their TTL.
        Set<String> wanted = Set.of("1.0", "2.0");
        assertThat(group.availableVersions(LIB, wanted, false)).containsExactlyInAnyOrder("1.0", "2.0");
        RepoGroup.clearProcessVersionsCache();

        MavenMetadataCache.withForceRevalidate(() -> group.availableVersions(LIB, wanted, false));

        assertThat(first.requestsFor(META))
                .as("the leg on the pool revalidated")
                .isEqualTo(2);
        assertThat(second.requestsFor(META)).isEqualTo(2);
    }

    @Test
    void an_offline_session_keeps_a_bom_fan_out_off_the_network(@TempDir Path tmp) throws Exception {
        // Two BOM imports, so the expansion fans out onto the pool; both live only on the remote.
        new MavenStub(first)
                .pom("com.example", "bom-a", "1.0", MavenStub.bom("com.example", "bom-a", "1.0", List.of()))
                .pom("com.example", "bom-b", "1.0", MavenStub.bom("com.example", "bom-b", "1.0", List.of()));
        Cas cas = new Cas(tmp.resolve("cas"));
        MavenRepo repo = repo(cas, "first", first);
        // The importing POM is already in the store: the walk starts locally and only the imports
        // would reach out.
        String pom = """
                <project>
                  <groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0</version>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>bom-a</artifactId>
                      <version>1.0</version><type>pom</type><scope>import</scope></dependency>
                    <dependency><groupId>com.example</groupId><artifactId>bom-b</artifactId>
                      <version>1.0</version><type>pom</type><scope>import</scope></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;
        Path source = Files.writeString(tmp.resolve("lib.pom"), pom);
        RepoArtifactStore.forRepository(cas.root(), "first", repo.baseUrl())
                .materialize(MavenLayout.pomPath(LIB), source, Hashing.sha256Hex(pom.getBytes(StandardCharsets.UTF_8)));

        Session offline = Session.defaults().withConfig(JkConfig.empty().withOffline(true));
        assertThatThrownBy(() -> SessionContext.where(offline, () -> new EffectivePomBuilder(repo).build(LIB)))
                .isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
        assertThat(first.requested()).as("no leg reached the network").isEmpty();
    }

    private RepoGroup group(Path tmp) {
        Cas cas = new Cas(tmp.resolve("cas"));
        return new RepoGroup(List.of(repo(cas, "first", first), repo(cas, "second", second)));
    }

    private static MavenRepo repo(Cas cas, String name, LoopbackHttp server) {
        return new MavenRepo(name, server.base(), new Http(), cas, RepoCredential.ANONYMOUS, false);
    }
}
