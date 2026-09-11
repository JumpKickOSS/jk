// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code run} plan under {@code --debug-jvm} launches the same command with the JDWP agent as
 * the first JVM option; a plan without the request is exactly what it was.
 */
class ExecPlansDebugTest {

    private static final DebugJvm DEBUG = DebugJvm.parse("localhost:5005");

    @Test
    void the_agent_is_the_first_jvm_option_and_nothing_else_moves(@TempDir Path dir) throws Exception {
        app(dir);

        ExecPlan plain = ExecPlans.execPlan(dir, dir.resolve("cache"), "run", null, null);
        ExecPlan debugged =
                ExecPlans.execPlan(dir, dir.resolve("cache"), "run", null, null, null, null, "", Map.of(), DEBUG);

        assertThat(plain.error()).isNull();
        assertThat(plain.argv()).noneMatch(a -> a.contains("jdwp"));
        assertThat(debugged.error()).isNull();
        assertThat(debugged.argv()).hasSize(plain.argv().size() + 1);
        assertThat(debugged.argv().get(0)).isEqualTo(plain.argv().get(0));
        assertThat(debugged.argv().get(1)).isEqualTo(DEBUG.agentArg());
        assertThat(debugged.argv().subList(2, debugged.argv().size()))
                .isEqualTo(plain.argv().subList(1, plain.argv().size()));
        assertThat(debugged.display()).isEqualTo(plain.display());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_native_binary_is_passed_over_because_jdwp_needs_a_jvm(@TempDir Path dir) throws Exception {
        app(dir);
        Path bin = dir.resolve("target/app");
        Files.writeString(bin, "#!/bin/sh\n");
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));

        ExecPlan plain = ExecPlans.execPlan(dir, dir.resolve("cache"), "run", null, null);
        ExecPlan debugged =
                ExecPlans.execPlan(dir, dir.resolve("cache"), "run", null, null, null, null, "", Map.of(), DEBUG);

        assertThat(plain.argv())
                .as("without debug the native binary wins")
                .containsExactly(bin.toAbsolutePath().toString());
        assertThat(debugged.argv().get(0)).endsWith("java");
        assertThat(debugged.argv().get(1)).isEqualTo(DEBUG.agentArg());
    }

    @Test
    void an_error_plan_and_a_dev_plan_ride_untouched(@TempDir Path dir) throws Exception {
        app(dir);
        ExecPlan error = ExecPlan.error("run", "nothing to run");
        assertThat(ExecPlans.debugged(error, DEBUG)).isEqualTo(error);

        ExecPlan dev =
                ExecPlans.execPlan(dir, dir.resolve("cache"), "dev", null, null, null, null, "", Map.of(), DEBUG);
        assertThat(dev.error()).isNull();
        assertThat(dev.argv()).as("only a run plan reads the request").noneMatch(a -> a.contains("jdwp"));
    }

    private static void app(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "0.1.0"
                java = 25

                [application]
                main = "com.example.App"
                """);
        Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(dir.resolve("src/main/java/com/example/App.java"), """
                package com.example;
                public class App { public static void main(String[] a) {} }
                """);
        Files.createDirectories(dir.resolve("target/classes/main/com/example"));
        // The plan wants an artifact to launch; the manifest's declared main names the class.
        Files.write(dir.resolve("target/app-0.1.0.jar"), List.of(), StandardCharsets.UTF_8);
    }
}
