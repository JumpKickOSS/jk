// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.runtime.ImagePush;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code jk image} builds and loads an image and never pushes it; {@code jk publish}'s image lane
 * does, and says what it pushed or, on a dry run, would push.
 */
class ImageNoPushTest {

    @Test
    void jk_image_has_no_push_options() {
        List<String> names = new ImageVerb()
                .ownOptions().stream().flatMap(o -> o.names().stream()).toList();
        assertThat(names).doesNotContain("--registry", "--sigstore").contains("--tarball", "--tag");
    }

    @Test
    void jk_image_names_what_jk_publish_pushes_only_when_a_registry_is_set() {
        ModuleOutcome.Image loaded =
                new ModuleOutcome.Image("ghcr.io/acme/app:1.0", null, "app", "1.0", "docker", "ghcr.io/acme/app:1.0");
        assertThat(ImageVerb.publishLine(loaded)).isEqualTo("jk publish pushes ghcr.io/acme/app:1.0");
        ModuleOutcome.Image local = new ModuleOutcome.Image("app:1.0", null, "app", "1.0", "docker", null);
        assertThat(ImageVerb.publishLine(local)).isNull();
        assertThat(ImageVerb.imageSuccessTail(null, "app", "1.0", "podman"))
                .contains("Loaded OCI image")
                .contains("into podman")
                .doesNotContain("Pushed");
    }

    @Test
    void publish_prints_each_image_pushed_with_its_digest_and_a_dry_run_each_it_would_push() {
        assertThat(PublishCommand.imageLines(List.of(
                        new ImagePush("app", "ghcr.io/acme/app:1.0", "sha256:ab12", false),
                        new ImagePush("web", "ghcr.io/acme/web:1.0", "", true))))
                .containsExactly(
                        "Pushed image ghcr.io/acme/app:1.0@sha256:ab12", "Would push image ghcr.io/acme/web:1.0");
    }

    @Test
    void the_settle_line_counts_the_images() {
        WorkspaceResult pushed = new WorkspaceResult(true, 0, List.of(), List.of())
                .withImages(List.of(new ImagePush("app", "ghcr.io/acme/app:1.0", "sha256:ab12", false)));
        assertThat(PublishVerb.shipped(pushed)).isEqualTo("1 image");
        assertThat(PublishVerb.shipped(new WorkspaceResult(true, 0, List.of(), List.of())))
                .isEqualTo("0 modules");
    }
}
