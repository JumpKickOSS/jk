// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a shadow's rendering left to say is parked beside the shadow, not in the engine that
 * rendered it: the next build reports it once, even from an engine that took over in between.
 */
class ShadowPendingRowsTest {

    @Test
    void rows_parked_by_an_earlier_engines_render_are_reported_once(@TempDir Path module) throws Exception {
        Path shadow = Files.createDirectories(ManifestPaths.shadowDir(module));
        Files.writeString(shadow.resolve(ShadowManifests.PENDING_TIER3), "maven-enforcer-plugin\n");
        Files.writeString(shadow.resolve(ShadowManifests.PENDING_NOTES), "junit:junit raised 4.12 → 4.13.2\n");

        assertThat(ShadowManifests.drainTier3(module))
                .containsExactly(
                        "maven-enforcer-plugin: ignored when building from pom.xml; jk import pom.xml writes a"
                                + " jk.toml to edit",
                        "junit:junit raised 4.12 → 4.13.2");
        assertThat(ShadowManifests.drainTier3(module)).as("said once").isEmpty();
        assertThat(shadow.resolve(ShadowManifests.PENDING_TIER3)).doesNotExist();
    }

    @Test
    void a_module_with_nothing_parked_says_nothing(@TempDir Path module) {
        assertThat(ShadowManifests.drainTier3(module)).isEmpty();
    }
}
