// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.cloud.tools.jib.api.buildplan.Platform;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The JVM an AOT cache trains with is the image's own: the base image is pulled for the image's
 * platform, and each platform's JRE has its own tree, so an emulating host never trains an arm64
 * image's cache with the amd64 JVM.
 */
class BaseJrePlatformTest {

    @Test
    void theBaseImageIsPulledForTheImagesPlatform() throws Exception {
        assertThat(BaseJre.pull(RegistryAuth.NONE, "example.invalid/jre:25", "linux/arm64")
                        .toContainerBuildPlan()
                        .getPlatforms())
                .containsExactly(new Platform("arm64", "linux"));
        assertThat(BaseJre.target(List.of())).isEqualTo("linux/amd64");
        assertThat(BaseJre.target(List.of("linux/arm64"))).isEqualTo("linux/arm64");
    }

    @Test
    void eachPlatformsJreHasItsOwnTree() {
        Path cache = Path.of("cache");
        assertThat(BaseJre.treeOf("example.invalid/jre:25", "linux/arm64", cache))
                .isNotEqualTo(BaseJre.treeOf("example.invalid/jre:25", "linux/amd64", cache));
    }
}
