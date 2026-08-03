// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LockfileModulesTest {

    @Test
    void capture_workspace_resolves_inherited_project_fields(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group       = "com.acme"
                name        = "root"
                version     = "9.9.9"
                java        = 25
                jdk         = "temurin-25"
                description = "workspace root"

                [workspace]
                modules = ["lib"]
                """);
        Path lib = tmp.resolve("lib");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("jk.toml"), """
                [project]
                name = "lib"
                description.workspace = true
                """);

        List<Lockfile.ModuleEntry> modules = LockfileModules.capture(tmp);
        assertThat(modules).hasSize(2);

        Lockfile.ModuleEntry root =
                modules.stream().filter(m -> ".".equals(m.path())).findFirst().orElseThrow();
        assertThat(root.group()).isEqualTo("com.acme");
        assertThat(root.version()).isEqualTo("9.9.9");

        Lockfile.ModuleEntry member =
                modules.stream().filter(m -> "lib".equals(m.path())).findFirst().orElseThrow();
        assertThat(member.group()).isEqualTo("com.acme");
        assertThat(member.name()).isEqualTo("lib");
        assertThat(member.version()).isEqualTo("9.9.9");
        assertThat(member.java()).isEqualTo(25);
        assertThat(member.jdk()).isEqualTo("temurin-25");
        assertThat(member.description()).isEqualTo("workspace root");
    }

    @Test
    void stamp_attaches_modules_to_lock(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "solo"
                version = "1.0.0"
                java    = 25
                """);

        Lockfile stamped = LockfileModules.stamp(Lockfile.empty("0.1.0"), tmp);
        assertThat(stamped.modules()).singleElement().satisfies(m -> {
            assertThat(m.path()).isEqualTo(".");
            assertThat(m.name()).isEqualTo("solo");
            assertThat(m.version()).isEqualTo("1.0.0");
            assertThat(m.java()).isEqualTo(25);
        });
    }
}
