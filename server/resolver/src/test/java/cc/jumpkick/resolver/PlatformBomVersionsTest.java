// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** Platform BOM selectors resolve to a concrete catalog version before management load. */
class PlatformBomVersionsTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @Test
    void exact_pin_returns_literal_without_needing_metadata(@TempDir Path tmp) throws Exception {
        RepoGroup repos = RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tmp.resolve("c"))));
        String v = PlatformBomVersions.resolve(repos, "org.example", "bom", VersionSelector.parse("=1.2.3"));
        assertThat(v).isEqualTo("1.2.3");
    }

    @Test
    void caret_major_floor_picks_highest_stable(@TempDir Path tmp) throws Exception {
        upstream.metadata("org.example", "bom", "4.0.0", "4.0.1", "4.1.0", "4.1.0-RC1", "5.0.0");
        RepoGroup repos = RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tmp.resolve("c"))));
        String v = PlatformBomVersions.resolve(repos, "org.example", "bom", VersionSelector.parse("^4"));
        assertThat(v).isEqualTo("4.1.0");
    }

    @Test
    void caret_floor_at_minor_does_not_go_below_anchor(@TempDir Path tmp) throws Exception {
        upstream.metadata("org.example", "bom", "4.0.0", "4.0.1", "4.1.0", "4.2.0");
        RepoGroup repos = RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tmp.resolve("c"))));
        String v = PlatformBomVersions.resolve(repos, "org.example", "bom", VersionSelector.parse("^4.1.0"));
        assertThat(v).isEqualTo("4.2.0");
    }

    @Test
    void tilde_stays_within_minor(@TempDir Path tmp) throws Exception {
        upstream.metadata("org.example", "bom", "4.1.0", "4.1.5", "4.2.0");
        RepoGroup repos = RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tmp.resolve("c"))));
        String v = PlatformBomVersions.resolve(repos, "org.example", "bom", VersionSelector.parse("~4.1.0"));
        assertThat(v).isEqualTo("4.1.5");
    }

    @Test
    void latest_picks_highest_stable(@TempDir Path tmp) throws Exception {
        upstream.metadata("org.example", "bom", "4.0.0", "4.1.0", "4.2.0-RC1", "5.0.0");
        RepoGroup repos = RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tmp.resolve("c"))));
        String v = PlatformBomVersions.resolve(repos, "org.example", "bom", VersionSelector.parse("latest"));
        assertThat(v).isEqualTo("5.0.0");
    }

    @Test
    void latest_with_no_stable_is_loud_not_a_silent_milestone(@TempDir Path tmp) throws Exception {
        upstream.metadata("org.example", "bom", "8.0.0-M2", "8.0.0-M4");
        RepoGroup repos = RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tmp.resolve("c"))));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> PlatformBomVersions.resolve(repos, "org.example", "bom", VersionSelector.parse("latest")))
                .hasMessageContaining("no stable version")
                .hasMessageContaining("8.0.0-M4");
    }

    @Test
    void snapshot_picks_highest_including_pre_release(@TempDir Path tmp) throws Exception {
        upstream.metadata("org.example", "bom", "4.1.0", "5.0.0-M4");
        RepoGroup repos = RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tmp.resolve("c"))));
        String v = PlatformBomVersions.resolve(repos, "org.example", "bom", VersionSelector.parse("snapshot"));
        assertThat(v).isEqualTo("5.0.0-M4");
    }

    @Test
    void open_range_is_rejected(@TempDir Path tmp) {
        RepoGroup repos = RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tmp.resolve("c"))));
        assertThatThrownBy(() -> PlatformBomVersions.resolve(repos, "org.example", "bom", VersionSelector.parse(">=4")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact, caret/tilde, latest, or snapshot");
    }
}
