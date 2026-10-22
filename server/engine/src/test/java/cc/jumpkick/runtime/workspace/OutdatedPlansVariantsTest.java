// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.wire.protocol.OutdatedReport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk outdated} reports every variant value's dependencies under their scope, as the lock
 * resolves the union of them all; no {@code --variant} selection is needed.
 */
class OutdatedPlansVariantsTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @TempDir
    Path isolatedStore;

    @BeforeEach
    void isolateStore() {
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        RepoGroup.clearProcessVersionsCache();
    }

    @AfterEach
    void releaseStoreOverride() {
        System.clearProperty("jk.env.JK_STORE_DIR");
    }

    @Test
    void every_variant_values_dependencies_get_a_row(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), """
                group = "com.example"
                name = "cli"
                version = "1.0.0"
                java = 25

                [dependencies]
                util = "com.acme:util:1.0.0"

                [variants.contentType.demo]
                extra-src = ["src-demo"]

                [variants.contentType.prod]
                extra-src = ["src-prod"]

                [variants.contentType.prod.dependencies]
                lang = "com.acme:lang:3.18.0"

                [variants.contentType.prod.test-dependencies]
                probe = "com.acme:probe:1.0"
                """);
        MavenStub upstream = new MavenStub(http);
        published(upstream, "util", "1.0.0");
        published(upstream, "lang", "3.18.0", "3.21.0");
        published(upstream, "probe", "1.0", "1.1");

        OutdatedReport report = OutdatedPlans.compute(
                project, project.resolve("cache"), http.base(), false, OutdatedPlans.Progress.NONE);

        assertThat(report.error()).isNull();
        assertThat(report.rows())
                .extracting(r -> r.coordinate(), r -> r.scope(), r -> r.latest())
                .containsExactlyInAnyOrder(
                        tuple("com.acme:util", "main", "1.0.0"),
                        tuple("com.acme:lang", "main", "3.21.0"),
                        tuple("com.acme:probe", "test", "1.1"));
    }

    private static void published(MavenStub upstream, String artifact, String... versions) {
        for (String v : versions) {
            upstream.pom("com.acme", artifact, v, MavenStub.emptyPom("com.acme", artifact, v));
        }
        upstream.metadata("com.acme", artifact, versions);
    }
}
