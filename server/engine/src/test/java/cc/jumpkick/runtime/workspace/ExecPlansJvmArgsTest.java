// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.ExecPlan;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code [application] jvm-args} ride a run plan's JVM command line ahead of the launch target. */
class ExecPlansJvmArgsTest {

    @Test
    void the_flags_precede_the_launch_target(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "0.1.0"
                java = 25

                [application]
                main = "com.example.App"
                jvm-args = ["--enable-native-access=ALL-UNNAMED", "-Dgreeting=hi"]
                """);
        Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(dir.resolve("src/main/java/com/example/App.java"), """
                package com.example;
                public class App { public static void main(String[] a) {} }
                """);
        Files.createDirectories(dir.resolve("target/classes/main/com/example"));
        Files.write(dir.resolve("target/app-0.1.0.jar"), List.of(), StandardCharsets.UTF_8);

        ExecPlan plan = ExecPlans.execPlan(dir, dir.resolve("cache"), "run", null, null);

        assertThat(plan.error()).isNull();
        int flags = plan.argv().indexOf("--enable-native-access=ALL-UNNAMED");
        assertThat(flags).isPositive();
        assertThat(plan.argv().get(flags + 1)).isEqualTo("-Dgreeting=hi");
        assertThat(flags).isLessThan(plan.argv().indexOf("-jar"));
    }
}
