// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.gradle.GradleResolver;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.mvn.MavenResolver;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The bundled defaults are only installable if their publishers really put a checksum beside
 * them — Central's {@code .sha512} for Apache Maven, {@code .sha256} on services.gradle.org and on
 * the Kotlin GitHub release. Fetches the three sidecars (a few hundred bytes each), never the
 * archives.
 */
@Tag("network")
class DefaultDistributionChecksumsTest {

    @Test
    void every_default_distribution_has_a_digest_published_beside_it() throws Exception {
        Http http = new Http();
        List<ToolDistribution> defaults = List.of(
                MavenResolver.defaultDistribution(),
                GradleResolver.defaultDistribution(),
                KotlinResolver.defaultDistribution());
        for (ToolDistribution dist : defaults) {
            PublishedChecksum sidecar = dist.tool().publishedChecksum();
            URI uri = sidecar.beside(dist.downloadUri());
            HttpResponse<byte[]> response = http.get(uri);
            assertThat(response.statusCode()).as("%s", uri).isEqualTo(200);
            String body = new String(response.body(), StandardCharsets.UTF_8);
            assertThat(Hashing.checksumFromSidecar(body, sidecar.hexLength()))
                    .as("%s publishes a %s digest", uri, sidecar.label())
                    .isPresent();
        }
    }
}
