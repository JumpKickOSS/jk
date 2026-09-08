// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.workspace.ExecPlans;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Workspace-root {@code jk run} must pick a module with main. */
class ExecPlansWorkspaceRunTest {

    @Test
    void workspace_run_uses_declared_application_main(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "0.1.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["lib", "app"]
                """);
        writeLib(root.resolve("lib"));
        writeApp(root.resolve("app"), true);

        // Fake compiled classes so the scanner / classpath path has something.
        Path classes = root.resolve("app/target/classes/main/com/example");
        Files.createDirectories(classes);
        // Empty classfile won't parse as main — rely on declared [application] main.
        ExecPlan plan = ExecPlans.execPlan(root, root.resolve("cache"), "run", null, null);
        assertThat(plan.error()).isNull();
        assertThat(plan.argv()).isNotEmpty();
        // Declared main may launch as java -jar … or java -cp … Main depending on deps/classpath.
        String joined = String.join(" ", plan.argv()) + " " + plan.display();
        assertThat(joined).containsAnyOf("com.example.App", "app-0.1.0.jar");
    }

    @Test
    void workspace_run_with_two_declared_apps_is_ambiguous(@TempDir Path root) throws Exception {
        // Silently launching the first-listed app would make [workspace].modules ORDER change
        // what `jk run` executesname the candidates instead.
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "0.1.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["app", "tool"]
                """);
        for (String m : new String[] {"app", "tool"}) {
            Path dir = root.resolve(m);
            Files.createDirectories(dir.resolve("src"));
            Files.writeString(dir.resolve("jk.toml"), """
                    group = "com.example"
                    name = "%s"
                    version = "0.1.0"
                    jdk = 25
                    java = 25

                    [application]
                    main = "com.example.%s"
                    """.formatted(m, m));
            Files.writeString(
                    dir.resolve("src/Main.java"),
                    "package com.example; public class Main { public static void main(String[] a) {} }\n");
        }

        ExecPlan plan = ExecPlans.execPlan(root, root.resolve("cache"), "run", null, null);

        assertThat(plan.error()).isNotNull();
        assertThat(plan.mainIssue()).isEqualTo("ambiguous");
        assertThat(plan.error()).contains("app").contains("tool");
    }

    @Test
    void workspace_run_errors_when_no_main_anywhere(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "0.1.0"
                jdk = 25

                [workspace]
                modules = ["lib"]
                """);
        writeLib(root.resolve("lib"));
        ExecPlan plan = ExecPlans.execPlan(root, root.resolve("cache"), "run", null, null);
        assertThat(plan.error()).isNotNull();
        assertThat(plan.mainIssue()).isEqualTo("missing");
    }

    private static void writeLib(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "lib"
                version = "0.1.0"
                jdk = 25
                java = 25
                """);
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/Lib.java"), "package com.example; public class Lib {}\n");
    }

    private static void writeApp(Path dir, boolean withMain) throws Exception {
        Files.createDirectories(dir);
        String main = withMain ? """
                [application]
                main = "com.example.App"
                """ : "";
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "0.1.0"
                jdk = 25
                java = 25

                %s
                [dependencies]
                lib.workspace = true
                """.formatted(main));
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/App.java"), """
                package com.example;
                public class App {
                  public static void main(String[] args) {}
                }
                """);
    }
}
