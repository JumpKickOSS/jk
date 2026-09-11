// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The image's dependency layers come from the module's runtime closure, least volatile first:
 * locked releases, locked snapshots, then workspace siblings' thin jars, which have no lock row and
 * change on every rebuild of that module.
 */
class ImagePlansRuntimeJarsTest {

    private static Lockfile.Artifact row(String ga, String version) {
        return new Lockfile.Artifact(
                ga + ":jar:", version, "central", null, null, List.of(), List.of(), null, null, null);
    }

    @Test
    void snapshots_and_siblings_each_get_their_own_layer() throws Exception {
        Path netty = Path.of("/m2/io/netty/netty-common/4.2.0/netty-common-4.2.0.jar");
        Path dev = Path.of("/m2/com/acme/widget/1.0-SNAPSHOT/widget-1.0-SNAPSHOT.jar");
        Path sibling = Path.of("/ws/target/core/lib/core-0.1.0.jar");
        Map<Path, Lockfile.Artifact> rows =
                Map.of(netty, row("io.netty:netty-common", "4.2.0"), dev, row("com.acme:widget", "1.0-SNAPSHOT"));

        ImagePlans.RuntimeJars jars = ImagePlans.split(List.of(netty, sibling, dev), rows);

        assertThat(jars.releases())
                .as("a sibling rebuilt on every edit must not invalidate the release layer")
                .containsExactly(netty);
        assertThat(jars.snapshots()).containsExactly(dev);
        assertThat(jars.siblings()).containsExactly(sibling);
    }

    @Test
    void locked_jars_ship_under_their_coordinate_name_and_siblings_under_their_own() throws Exception {
        Path netty = Path.of("/store/ab/cd/ef0123.jar");
        Path sibling = Path.of("/ws/target/core/lib/core-0.1.0.jar");

        ImagePlans.RuntimeJars jars =
                ImagePlans.split(List.of(netty, sibling), Map.of(netty, row("io.netty:netty-common", "4.2.0")));

        assertThat(jars.name(netty)).isEqualTo("netty-common-4.2.0.jar");
        assertThat(jars.name(sibling)).isEqualTo("core-0.1.0.jar");
    }

    @Test
    void an_absent_lock_yields_no_rows_rather_than_an_error() throws Exception {
        assertThat(ImagePlans.lockRows(Path.of("/nowhere/jk-lock.toml"))).isEmpty();
    }
}
