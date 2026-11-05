// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.docs.AgentsGuide;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project scaffolded from a template gets what every standalone {@code jk new} gets: the agent
 * files, a first code graph and a {@code .gitignore}. A workspace module gets none of them; it is
 * listed in the root's {@code [workspace].modules} instead, so the workspace builds it.
 */
@Tag("integration")
class NewTemplateWiringTest {

    @Test
    void standalone_template_wires_claude_seeds_the_graph_and_writes_a_gitignore(@TempDir Path dir) throws Exception {
        Path template = template(dir, null);
        Path target = dir.resolve("made");

        assertThat(newFromTemplate(env(Map.of("CLAUDECODE", "1")), false, target, template))
                .isZero();

        assertThat(target.resolve(AgentsGuide.FILE)).isRegularFile();
        assertThat(target.resolve("CLAUDE.md")).isRegularFile();
        assertThat(target.resolve(".mcp.json")).isRegularFile();
        assertThat(target.resolve(".claude/settings.json")).isRegularFile();
        assertThat(target.resolve(".jk/graph")).isDirectory();
        assertThat(target.resolve(".gitignore")).content().contains("target/");
    }

    @Test
    void jk_agents_none_writes_agents_md_only(@TempDir Path dir) throws Exception {
        Path template = template(dir, null);
        Path target = dir.resolve("made");

        assertThat(newFromTemplate(env(Map.of("CLAUDECODE", "1", "JK_AGENTS", "none")), false, target, template))
                .isZero();

        assertThat(target.resolve(AgentsGuide.FILE)).isRegularFile();
        assertThat(target.resolve("CLAUDE.md")).doesNotExist();
        assertThat(target.resolve(".mcp.json")).doesNotExist();
        assertThat(target.resolve(".jk/graph")).doesNotExist();
        assertThat(target.resolve(".gitignore")).isRegularFile();
    }

    @Test
    void jk_agents_all_wires_every_agent_with_none_detected(@TempDir Path dir) throws Exception {
        Path template = template(dir, null);
        Path target = dir.resolve("made");

        assertThat(newFromTemplate(env(Map.of("JK_AGENTS", "all")), false, target, template))
                .isZero();

        assertThat(target.resolve("CLAUDE.md")).isRegularFile();
        assertThat(target.resolve(".mcp.json")).isRegularFile();
        assertThat(target.resolve(".jk/graph")).isDirectory();
    }

    @Test
    void a_template_that_ships_a_gitignore_keeps_it(@TempDir Path dir) throws Exception {
        Path template = template(dir, "build-output/\n");
        Path target = dir.resolve("made");

        assertThat(newFromTemplate(env(Map.of()), false, target, template)).isZero();

        assertThat(target.resolve(".gitignore")).content().isEqualTo("build-output/\n");
    }

    @Test
    void a_workspace_module_from_a_template_gets_no_agent_files_graph_or_gitignore(@TempDir Path dir) throws Exception {
        Path template = template(dir, null);
        Path root = Files.createDirectories(dir.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "0.1.0"

                [workspace]
                modules = []
                """);
        Path target = root.resolve("made");

        assertThat(newFromTemplate(env(Map.of("CLAUDECODE", "1")), true, target, template))
                .isZero();

        assertThat(target.resolve("jk.toml")).isRegularFile();
        assertThat(target.resolve(AgentsGuide.FILE)).doesNotExist();
        assertThat(target.resolve("CLAUDE.md")).doesNotExist();
        assertThat(target.resolve(".mcp.json")).doesNotExist();
        assertThat(target.resolve(".jk/graph")).doesNotExist();
        assertThat(target.resolve(".gitignore")).doesNotExist();
    }

    @Test
    void a_workspace_module_from_a_template_is_listed_in_the_root_and_builds(@TempDir Path dir) throws Exception {
        Path template = template(dir, null);
        Path root = Files.createDirectories(dir.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "0.1.0"
                java    = 25

                [workspace]
                modules = []
                """);
        Path target = root.resolve("made");

        assertThat(newFromTemplate(env(Map.of()), true, target, template)).isZero();

        assertThat(root.resolve("jk.toml")).content().contains("modules = [\"made\"]");
        int[] built = new int[1];
        String out = Capture.stderr(() -> Capture.stdout(() -> {
            built[0] = Jk.execute(
                    "build",
                    "-C",
                    root.toString(),
                    "--skip-tests",
                    "--cache-dir",
                    dir.resolve("cache").toString());
        }));
        assertThat(built[0]).as(out).isZero();
        try (var files = Files.walk(target.resolve("target"))) {
            assertThat(files.map(p -> p.getFileName().toString())).contains("Lib.class");
        }
    }

    @Test
    void a_template_inside_a_plain_project_makes_it_a_workspace_root(@TempDir Path dir) throws Exception {
        Path template = template(dir, null);
        Path root = Files.createDirectories(dir.resolve("app"));
        Files.writeString(root.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "0.1.0"
                """);
        Path target = root.resolve("made");

        assertThat(newFromTemplate(env(Map.of()), true, target, template)).isZero();

        assertThat(root.resolve("jk.toml")).content().contains("[workspace]").contains("\"made\"");
    }

    /** An environment holding {@code vars} and a {@code PATH} with no agent CLI on it. */
    private static UnaryOperator<@Nullable String> env(Map<String, String> vars) {
        return name -> "PATH".equals(name) ? "" : vars.get(name);
    }

    /** {@code jk new <target> -t <template>}, with {@code -N} unless it lands in a workspace. */
    private static int newFromTemplate(
            UnaryOperator<@Nullable String> env, boolean module, Path target, Path template) {
        String[] args = module
                ? new String[] {"new", target.toString(), "-t", template.toString()}
                : new String[] {"new", target.toString(), "-t", template.toString(), "-N"};
        int[] code = new int[1];
        ScopedValue.where(AgentWiring.ENV, env)
                .run(() -> Capture.stderr(() -> Capture.stdout(() -> {
                    code[0] = Jk.execute(args);
                })));
        return code[0];
    }

    /** A one-class Java library template; {@code gitignore} non-null ships that {@code .gitignore}. */
    private static Path template(Path dir, @Nullable String gitignore) throws IOException {
        Path g8 = Files.createDirectories(dir.resolve("template/src/main/g8"));
        Files.writeString(g8.resolve("default.properties"), "name=fromtemplate\n");
        Files.writeString(g8.resolve("jk.toml"), """
                group = "com.example"
                name = "$name$"
                version = "0.1.0"
                java = 25
                """);
        Path src = Files.createDirectories(g8.resolve("src/main/java/com/example"));
        Files.writeString(src.resolve("Lib.java"), "package com.example;\n\npublic class Lib {}\n");
        if (gitignore != null) Files.writeString(g8.resolve(".gitignore"), gitignore);
        return dir.resolve("template");
    }
}
