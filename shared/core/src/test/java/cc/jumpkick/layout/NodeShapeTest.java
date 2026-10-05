// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which modules have a node build, and the refusals that name their one-line fix. */
class NodeShapeTest {

    @TempDir
    Path tmp;

    private Path module(String name, String extraToml) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                name = "%s"
                group = "g"
                version = "1.0"
                %s
                """.formatted(name, extraToml));
        return dir;
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    private static JkBuild parse(Path dir) throws IOException {
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }

    @Test
    void package_json_with_a_node_version_and_no_jvm_sources_is_a_node_module() throws IOException {
        Path web = module("web", "node = 24");
        write(web.resolve("package.json"), "{\"scripts\": {\"build\": \"vite build\"}}");
        write(web.resolve("package-lock.json"), "{}");
        write(web.resolve("src/main.ts"), "");
        JkBuild build = parse(web);
        assertThat(NodeShape.kind(build, web)).isEqualTo(NodeShape.Kind.MODULE);
        assertThat(NodeShape.nodeDir(build, web)).isEqualTo(web);
        assertThat(Languages.resolve(build.project(), web)).isEqualTo(Languages.NODE);
    }

    @Test
    void a_node_module_compiles_no_java_even_when_it_inherits_a_java_level() throws IOException {
        write(tmp.resolve("jk.toml"), """
                name = "ws"
                group = "g"
                version = "1.0"
                java = 25
                node = 24

                [workspace]
                modules = ["web"]
                """);
        Path web = Files.createDirectories(tmp.resolve("web"));
        write(web.resolve("jk.toml"), "name = \"web\"\n");
        write(web.resolve("package.json"), "{}");
        write(web.resolve("package-lock.json"), "{}");
        assertThat(Languages.resolve(parse(web).project(), web).java()).isFalse();
    }

    @Test
    void src_main_node_beside_java_sources_is_a_side_by_side_node_build() throws IOException {
        Path app = module("app", "node = 24");
        write(app.resolve("src/main/java/app/App.java"), "class App {}");
        write(app.resolve("src/main/node/package.json"), "{}");
        write(app.resolve("src/main/node/package-lock.json"), "{}");
        JkBuild build = parse(app);
        assertThat(NodeShape.kind(build, app)).isEqualTo(NodeShape.Kind.SIDE_BY_SIDE);
        assertThat(NodeShape.nodeDir(build, app)).isEqualTo(app.resolve("src/main/node"));
        assertThat(Languages.resolve(build.project(), app).java()).isTrue();
    }

    @Test
    void node_dir_moves_the_side_by_side_build() throws IOException {
        Path app = module("app", "[node]\nversion = 24\ndir = \"src/main/frontend\"");
        write(app.resolve("src/main/java/app/App.java"), "class App {}");
        write(app.resolve("src/main/frontend/package.json"), "{}");
        write(app.resolve("src/main/frontend/package-lock.json"), "{}");
        assertThat(NodeShape.nodeDir(parse(app), app)).isEqualTo(app.resolve("src/main/frontend"));
    }

    @Test
    void a_tooling_package_json_beside_java_sources_is_not_a_node_build() throws IOException {
        Path app = module("app", "");
        write(app.resolve("src/main/java/app/App.java"), "class App {}");
        write(app.resolve("package.json"), "{\"devDependencies\": {\"prettier\": \"3\"}}");
        JkBuild build = parse(app);
        assertThat(NodeShape.kind(build, app)).isEqualTo(NodeShape.Kind.NONE);
        assertThat(Languages.resolve(build.project(), app).java()).isTrue();
    }

    @Test
    void a_node_table_over_a_package_json_beside_java_sources_is_refused() throws IOException {
        Path app = module("app", "[node]\nversion = 24\nframework = \"vite\"");
        write(app.resolve("src/main/java/app/App.java"), "class App {}");
        write(app.resolve("package.json"), "{}");
        assertThatThrownBy(() -> parse(app))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("app/jk.toml: package.json sits beside the JVM sources — move the node build into"
                        + " src/main/node, or set [node] dir");
    }

    @Test
    void a_node_table_with_no_package_json_is_refused() throws IOException {
        Path web = module("web", "[node]\nversion = 24\nframework = \"vite\"");
        assertThatThrownBy(() -> parse(web))
                .hasMessage("web/jk.toml: [node] is set but ./package.json does not exist — add the node build"
                        + " there, or remove [node]");
    }

    @Test
    void a_node_table_with_no_node_declared_is_refused_with_the_version_the_tree_proposes() throws IOException {
        Path web = module("web", "[node]\nframework = \"vite\"");
        write(web.resolve("package.json"), "{}");
        assertThatThrownBy(() -> parse(web))
                .hasMessage("web/jk.toml: package.json found but no node toolchain is declared — add node = 24");
        write(web.resolve(".nvmrc"), "v22.11.0\n");
        assertThatThrownBy(() -> parse(web)).hasMessageEndingWith("add node = 22 (from .nvmrc)");
    }

    @Test
    void a_module_whose_only_sources_are_a_package_json_must_declare_node() throws IOException {
        Path web = module("web", "");
        write(web.resolve("package.json"), "{\"scripts\": {\"build\": \"vite build\"}}");
        write(web.resolve(".nvmrc"), "22\n");
        assertThatThrownBy(() -> parse(web))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("web/jk.toml: package.json found but no node toolchain is declared — add node = 22"
                        + " (from .nvmrc)");
    }

    @Test
    void the_proposal_reads_the_ecosystem_files_in_order() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("p"));
        write(dir.resolve("package.json"), """
                {"engines": {"node": ">=20"}, "volta": {"node": "21.7.3"},
                 "devEngines": {"runtime": {"name": "node", "version": "^23"}}}
                """);
        assertThat(NodeShape.propose(dir)).isEqualTo(new NodeShape.Proposal("23", "package.json devEngines.runtime"));
        write(dir.resolve(".node-version"), "lts/iron");
        assertThat(NodeShape.propose(dir)).isEqualTo(new NodeShape.Proposal("\"lts\"", ".node-version"));
        write(dir.resolve(".nvmrc"), "18");
        assertThat(NodeShape.propose(dir)).isEqualTo(new NodeShape.Proposal("18", ".nvmrc"));
        write(dir.resolve("package.json"), "{\"engines\": {\"node\": \">=20.9\"}}");
        Files.delete(dir.resolve(".nvmrc"));
        Files.delete(dir.resolve(".node-version"));
        assertThat(NodeShape.propose(dir)).isEqualTo(new NodeShape.Proposal("20", "package.json engines.node"));
        write(dir.resolve("package.json"), "{\"volta\": {\"node\": \"21.7.3\"}}");
        assertThat(NodeShape.propose(dir)).isEqualTo(new NodeShape.Proposal("21", "package.json volta.node"));
    }

    @Test
    void yarn_1_in_a_node_module_is_refused_when_the_manifest_is_read() throws IOException {
        Path web = module("web", "node = 24");
        write(web.resolve("package.json"), "{}");
        write(web.resolve("yarn.lock"), "# yarn lockfile v1\n");
        assertThatThrownBy(() -> parse(web)).hasMessageContaining("Yarn 1 is not supported");
    }

    @Test
    void a_workspace_root_s_package_json_is_left_alone() throws IOException {
        write(tmp.resolve("jk.toml"), """
                name = "ws"
                group = "g"
                version = "1.0"

                [workspace]
                modules = ["lib"]
                """);
        write(tmp.resolve("lib/jk.toml"), "name = \"lib\"\n");
        write(tmp.resolve("package.json"), "{}");
        JkBuild root = parse(tmp);
        assertThat(NodeShape.kind(root, tmp)).isEqualTo(NodeShape.Kind.NONE);
    }

    @Test
    void a_war_builds_a_front_end_at_the_workspace_root_above_it() throws IOException {
        write(
                tmp.resolve("jk.toml"),
                "name = \"ws\"\ngroup = \"g\"\nversion = \"1.0\"\n[workspace]\nmodules = [\"war\"]\n");
        write(tmp.resolve("package.json"), "{\"scripts\": {\"build\": \"webpack\"}}");
        Path war = module("war", """
                [war]

                [node]
                version = 24
                dir = ".."
                out = "war/src/main/webapp/jsbundles"
                webapp-root = "jsbundles"
                """);
        JkBuild build = parse(war);
        assertThat(NodeShape.kind(build, war))
                .as("a war with no JVM sources still packages its node output")
                .isEqualTo(NodeShape.Kind.SIDE_BY_SIDE);
        assertThat(NodeShape.nodeDir(build, war)).isEqualTo(tmp.toAbsolutePath().normalize());
    }

    @Test
    void a_dir_or_out_that_leaves_the_module_for_anything_but_a_project_above_it_is_refused() throws IOException {
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        write(elsewhere.resolve("package.json"), "{}");
        Path app = module("app", "[node]\nversion = 24\ndir = \"../elsewhere\"\n");
        assertThatThrownBy(() -> parse(app))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("leaves the module");
        Path web = module("web", "[node]\nversion = 24\nout = \"../../dist\"\n");
        write(web.resolve("package.json"), "{\"scripts\": {\"build\": \"vite build\"}}");
        assertThatThrownBy(() -> parse(web))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("lands outside both");
    }

    @Test
    void a_pom_built_module_has_the_node_build_its_shadow_declares_and_no_other() throws IOException {
        write(tmp.resolve("pom.xml"), "<project/>");
        write(tmp.resolve("package.json"), "{\"scripts\": {\"build\": \"webpack\"}}");
        Path war = Files.createDirectories(tmp.resolve("war"));
        write(war.resolve("pom.xml"), "<project/>");
        write(war.resolve("package.json"), "{\"devDependencies\": {\"prettier\": \"3\"}}");
        JkBuild placed = JkBuildParser.parse("""
                name = "war"
                group = "g"
                version = "1.0"

                [war]

                [node]
                version = 24
                dir = ".."
                out = "war/src/main/webapp/jsbundles"
                """);
        JkBuild plain = JkBuildParser.parse("name = \"war\"\ngroup = \"g\"\nversion = \"1.0\"\nnode = 24\n");

        assertThat(NodeShape.kind(placed, war)).isEqualTo(NodeShape.Kind.SIDE_BY_SIDE);
        assertThat(NodeShape.kind(plain, war))
                .as("a tooling package.json beside a Maven module is not a node build")
                .isEqualTo(NodeShape.Kind.NONE);
    }
}
