// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A build whose jk.toml does not parse fails naming the file, line and column. */
class WorkspacePreflightManifestErrorTest {

    @Test
    void an_unknown_key_is_reported_at_its_line_and_column(@TempDir Path tmp) throws Exception {
        Path manifest = tmp.resolve("jk.toml").toAbsolutePath().normalize();
        Files.writeString(manifest, """
                group = "ex"
                name = "app"
                version = "0.1.0"
                java = 25

                [build]
                bogus = 1
                """);
        WorkspaceRequest request =
                new WorkspaceRequest(tmp, tmp.resolve("cache"), null, 0, null, true, false, 0, null, true, true);

        WorkspacePreflightPhase.Outcome outcome = WorkspacePreflightPhase.run(request, new WorkspaceBuildListener() {});

        assertThat(outcome).isInstanceOf(WorkspacePreflightPhase.Completed.class);
        assertThat(((WorkspacePreflightPhase.Completed) outcome).result().errors())
                .singleElement()
                .asString()
                .startsWith(manifest + ":7:1: [build] unknown key `bogus`");
    }
}
