// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Sidecar;
import cc.jumpkick.runtime.workspace.ExecPlans;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A dev plan carries the module's sidecars, resolved; a run plan carries none. */
class ExecPlansSidecarsTest {

    @Test
    void dev_plan_resolves_cwd_env_and_the_root_union_while_run_carries_nothing(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["app"]

                [dev.sidecars]
                docs = { command = "mkdocs serve", cwd = "docs" }
                web = { command = "echo root-wins-not" }
                """);
        Files.writeString(root.resolve(".env"), "FROM_DOTENV=file\nSHADOWED=file\n");
        Path app = root.resolve("app");
        Files.createDirectories(app.resolve("src/main/java/com/example"));
        Files.writeString(app.resolve("src/main/java/com/example/App.java"), """
                package com.example;
                public class App { public static void main(String[] a) {} }
                """);
        Files.writeString(app.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "0.1.0"
                java = 25

                [application]
                main = "com.example.App"

                [dev.sidecars]
                web = { command = "npm run dev", cwd = "../web", env = { PORT = "5173" }, ready = "http://localhost:5173", front-door = true }
                """);
        Files.createDirectories(app.resolve("target/classes/main"));

        ExecPlan dev = ExecPlans.execPlan(
                app, root.resolve("cache"), "dev", null, null, null, null, "", Map.of("SHADOWED", "real"), null);
        assertThat(dev.error()).isNull();
        assertThat(dev.sidecars()).extracting(ExecPlan.Sidecar::name).containsExactly("docs", "web");
        ExecPlan.Sidecar docs = dev.sidecars().get(0);
        assertThat(docs.cwd())
                .isEqualTo(root.resolve("docs").toAbsolutePath().normalize().toString());
        assertThat(docs.command()).containsExactly("mkdocs", "serve");
        ExecPlan.Sidecar web = dev.sidecars().get(1);
        assertThat(web.command()).containsExactly("npm", "run", "dev");
        assertThat(web.cwd())
                .isEqualTo(root.resolve("web").toAbsolutePath().normalize().toString());
        // .env supplies what the real environment lacks; the real value is never overridden
        assertThat(web.env()).containsEntry("FROM_DOTENV", "file").containsEntry("PORT", "5173");
        assertThat(web.env()).doesNotContainKey("SHADOWED");
        assertThat(web.probe().ready()).isEqualTo("http://localhost:5173");
        assertThat(web.frontDoor()).isTrue();
        assertThat(web.restart()).isEqualTo(Sidecar.Restart.NEVER);

        ExecPlan run =
                ExecPlans.execPlan(app, root.resolve("cache"), "run", null, null, null, null, "", Map.of(), null);
        assertThat(run.sidecars()).isEmpty();
    }
}
