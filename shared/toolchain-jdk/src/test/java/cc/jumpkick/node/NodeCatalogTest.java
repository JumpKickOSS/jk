// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.compat.ToolDistribution;
import cc.jumpkick.http.Http;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class NodeCatalogTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().withoutChecksums();

    private static final String SHASUMS = """
            1111111111111111111111111111111111111111111111111111111111111111  node-v24.21.0-darwin-arm64.tar.gz
            2222222222222222222222222222222222222222222222222222222222222222  node-v24.21.0-darwin-x64.tar.gz
            3333333333333333333333333333333333333333333333333333333333333333  node-v24.21.0-linux-arm64.tar.gz
            4444444444444444444444444444444444444444444444444444444444444444  node-v24.21.0-linux-x64.tar.gz
            5555555555555555555555555555555555555555555555555555555555555555  node-v24.21.0-linux-x64.tar.xz
            6666666666666666666666666666666666666666666666666666666666666666  node-v24.21.0-win-x64.zip
            7777777777777777777777777777777777777777777777777777777777777777  node-v24.21.0-win-arm64.zip
            8888888888888888888888888888888888888888888888888888888888888888  node-v24.21.0-linux-x64-musl.tar.gz
            """;

    private void serveIndex() throws IOException {
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("index.json"))) {
            http.served().put("/index.json", in.readAllBytes());
        }
        http.serve("/v24.21.0/SHASUMS256.txt", SHASUMS);
    }

    @Test
    void a_spec_resolves_to_its_release_with_every_locked_platform_s_digest(@TempDir Path store) throws Exception {
        serveIndex();
        NodeCatalog catalog = new NodeCatalog(new Http(), http.base(), store, Duration.ofHours(12));
        NodeResolution r =
                new NodeResolver(catalog).resolve(NodeSpec.parse("24"), NodePlatform.of("Linux", "amd64", "glibc"));

        assertThat(r.version()).isEqualTo("24.21.0");
        assertThat(r.npm()).isEqualTo("11.6.0");
        assertThat(r.sha256())
                .containsEntry("linux-x64", "4".repeat(64))
                .containsEntry("darwin-arm64", "1".repeat(64))
                .containsEntry("win-arm64", "7".repeat(64))
                .hasSize(6);
        ToolDistribution dist = r.distribution(NodePlatform.of("Windows 11", "amd64", null), catalog.distBase());
        assertThat(dist.downloadUri().toString()).endsWith("/v24.21.0/node-v24.21.0-win-x64.zip");
        assertThat(dist.archiveType()).isEqualTo("zip");
        assertThat(dist.sha256()).isEqualTo("6".repeat(64));
    }

    @Test
    void a_musl_host_gets_its_own_archive_s_digest_beside_the_locked_set(@TempDir Path store) throws Exception {
        serveIndex();
        NodeCatalog catalog = new NodeCatalog(new Http(), http.base(), store, Duration.ofHours(12));
        NodeResolution r =
                new NodeResolver(catalog).resolve(NodeSpec.parse("24"), NodePlatform.of("Linux", "amd64", "musl"));
        assertThat(r.sha256()).containsEntry("linux-x64-musl", "8".repeat(64)).hasSize(7);
    }

    @Test
    void a_host_with_no_published_archive_is_refused(@TempDir Path store) throws Exception {
        serveIndex();
        NodeCatalog catalog = new NodeCatalog(new Http(), http.base(), store, Duration.ofHours(12));
        assertThatThrownBy(() -> new NodeResolver(catalog)
                        .resolve(NodeSpec.parse("24"), NodePlatform.of("Linux", "aarch64", "musl")))
                .hasMessageContaining("publishes no tar.gz archive for linux-arm64-musl");
    }

    @Test
    void the_index_is_cached_and_served_from_the_cache_when_the_network_is_gone(@TempDir Path store) throws Exception {
        serveIndex();
        List<String> warnings = new ArrayList<>();
        NodeCatalog fresh = new NodeCatalog(new Http(), http.base(), store, Duration.ofHours(12));
        assertThat(fresh.releases()).hasSize(5);
        assertThat(store.resolve("node-index.json")).exists();

        http.served().remove("/index.json");
        assertThat(fresh.releases()).hasSize(5);
        assertThat(http.requestsFor("/index.json")).isEqualTo(1);

        NodeCatalog stale = new NodeCatalog(new Http(), http.base(), store, Duration.ZERO).onWarning(warnings::add);
        assertThat(stale.releases()).hasSize(5);
        assertThat(warnings).singleElement().asString().contains("Node index unreachable");
    }

    @Test
    void a_release_s_checksums_are_kept_for_good(@TempDir Path store) throws Exception {
        serveIndex();
        NodeCatalog catalog = new NodeCatalog(new Http(), http.base(), store, Duration.ofHours(12));
        assertThat(catalog.shasums("24.21.0")).containsEntry("node-v24.21.0-linux-x64.tar.gz", "4".repeat(64));
        http.served().clear();
        assertThat(catalog.shasums("24.21.0")).hasSize(8);
        assertThat(Files.readString(store.resolve("node-shasums/v24.21.0.txt"))).isEqualTo(SHASUMS);
    }
}
