// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every verb that builds runs the workspace preflight, and the preflight names JumpKick's own pins
 * behind the running jk before the lock, while the pinned release still resolves; 0.51.1 said
 * nothing until the download failed.
 */
class WorkspacePreflightPinsBehindTest {

    @TempDir
    Path tmp;

    @Test
    void a_pack_behind_the_running_jk_is_one_warning_before_the_lock() throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), "group = \"ex\"\nname = \"lib\"\nversion = \"1.0.0\"\n");
        Files.writeString(
                tmp.resolve("jk-guards.toml"), "[guards]\nextends = [\"cc.jumpkick.guards:library:0.1.0\"]\n");
        List<String> warnings = new ArrayList<>();
        WorkspaceBuildListener listener = new WorkspaceBuildListener() {
            @Override
            public void onModuleWarning(Path dir, String module, String step, String code, String message) {
                warnings.add(step + " " + code + " " + message);
            }
        };

        WorkspacePreflightPhase.warnPinsBehindClient(request(), listener);

        assertThat(warnings)
                .singleElement()
                .asString()
                .startsWith("lock " + WorkspacePreflightPhase.PINS_BEHIND)
                .contains("library 0.1.0 (jk-guards.toml)", "run jk update");

        warnings.clear();
        Files.writeString(tmp.resolve("jk-guards.toml"), "[guards]\nextends = [\"com.acme:house:0.1.0\"]\n");
        WorkspacePreflightPhase.warnPinsBehindClient(request(), listener);
        assertThat(warnings).as("a third-party pack is not JumpKick's").isEmpty();
    }

    private WorkspaceRequest request() {
        return new WorkspaceRequest(tmp, tmp.resolve("cache"), null, 0, null, true, false, 0, null, true, true);
    }
}
