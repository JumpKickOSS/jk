// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code wrote} line names a document the way every other path jk prints is named: relative
 * to the workspace root. A member's build output lives under the root's {@code target/}, outside
 * the member's own directory, so relativizing against the member alone would fall back to an
 * absolute, machine-specific path.
 */
class PublishDisplayPathTest {

    @TempDir
    Path tmp;

    @Test
    void a_workspace_member_s_document_is_shown_relative_to_the_workspace_root() {
        Path ws = tmp.resolve("ws");
        Path member = ws.resolve("libs/widget");
        Path doc = ws.resolve("target/libs/widget/sbom/widget-1.0.0.cdx.json");
        assertThat(PublishCommand.displayPath(doc, ws, member))
                .isEqualTo("target/libs/widget/sbom/widget-1.0.0.cdx.json");
    }

    @Test
    void a_standalone_project_s_document_is_shown_relative_to_the_project() {
        Path project = tmp.resolve("app");
        Path doc = project.resolve("target/sbom/app-1.0.0.spdx.json");
        assertThat(PublishCommand.displayPath(doc, project, project)).isEqualTo("target/sbom/app-1.0.0.spdx.json");
    }

    @Test
    void a_document_outside_both_roots_stays_absolute() {
        Path elsewhere = tmp.resolve("elsewhere/x.cdx.json").toAbsolutePath().normalize();
        assertThat(PublishCommand.displayPath(elsewhere, tmp.resolve("ws"), tmp.resolve("ws/m")))
                .isEqualTo(elsewhere.toString().replace('\\', '/'));
    }
}
