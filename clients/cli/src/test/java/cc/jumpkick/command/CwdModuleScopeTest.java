// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CwdModuleScopeTest {

    @Test
    void member_dir_without_flag_infers_that_module(@TempDir Path root) {
        Path member = root.resolve("libs/core");
        CwdModuleScope.Resolved r = CwdModuleScope.resolve(member, null, false, root.toString(), "core");
        assertThat(r.workspaceMember()).isTrue();
        assertThat(r.inferredFromCwd()).isTrue();
        assertThat(r.workspaceRoot()).isEqualTo(root.toAbsolutePath().normalize());
        assertThat(r.modulesSpec()).isEqualTo("libs/core");
        assertThat(r.focusLabel()).isEqualTo("core");
        assertThat(r.scoped()).isTrue();
    }

    @Test
    void explicit_modules_flag_wins_from_a_member_dir(@TempDir Path root) {
        CwdModuleScope.Resolved r =
                CwdModuleScope.resolve(root.resolve("app"), "libs/core", false, root.toString(), "app");
        assertThat(r.workspaceMember()).isTrue();
        assertThat(r.inferredFromCwd()).isFalse();
        assertThat(r.modulesSpec()).isEqualTo("libs/core");
        assertThat(r.focusLabel()).isNull();
    }

    @Test
    void workspace_root_is_not_inferred(@TempDir Path root) {
        CwdModuleScope.Resolved r = CwdModuleScope.resolve(root, null, true, root.toString(), "ws");
        assertThat(r.workspaceMember()).isFalse();
        assertThat(r.inferredFromCwd()).isFalse();
        assertThat(r.modulesSpec()).isNull();
        assertThat(r.workspaceRoot()).isEqualTo(root.toAbsolutePath().normalize());
    }

    @Test
    void standalone_project_is_not_inferred(@TempDir Path dir) {
        CwdModuleScope.Resolved r = CwdModuleScope.resolve(dir, null, false, "", "solo");
        assertThat(r.workspaceMember()).isFalse();
        assertThat(r.inferredFromCwd()).isFalse();
        assertThat(r.scoped()).isFalse();
    }

    @Test
    void missing_peek_is_not_inferred(@TempDir Path dir) {
        CwdModuleScope.Resolved r = CwdModuleScope.resolve(dir, null, null);
        assertThat(r.workspaceMember()).isFalse();
        assertThat(r.inferredFromCwd()).isFalse();
        assertThat(r.scoped()).isFalse();
    }

    @Test
    void blank_project_name_falls_back_to_the_relative_path(@TempDir Path root) {
        CwdModuleScope.Resolved r = CwdModuleScope.resolve(root.resolve("app"), null, false, root.toString(), "  ");
        assertThat(r.focusLabel()).isEqualTo("app");
    }
}
