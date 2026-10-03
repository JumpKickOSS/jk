// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.NodeTable;
import cc.jumpkick.model.ToolchainSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code node = …} and the {@code [node]} table as {@code jk.toml} writes them. */
class ManifestNodeTest {

    @TempDir
    Path tmp;

    private JkBuild parse(String toml) {
        return JkBuildParser.parse("name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\n" + toml);
    }

    @Test
    void a_major_is_a_floor_a_point_release_a_suggestion_and_an_equals_an_exact_pin() {
        assertThat(parse("node = 24\n").project().nodeSpec()).isEqualTo(new ToolchainSpec("", "24", "", ""));
        assertThat(parse("node = \"24.21.0\"\n").project().nodeSpec())
                .isEqualTo(new ToolchainSpec("", "24.21.0", "", ""));
        assertThat(parse("node = \"=24.21.0\"\n").project().nodeSpec())
                .isEqualTo(new ToolchainSpec("", "", "", "24.21.0"));
        assertThat(parse("node = \"lts\"\n").project().nodeSpec().version()).isEqualTo("lts");
    }

    @Test
    void the_version_may_sit_in_the_node_table_when_the_table_carries_settings() {
        JkBuild build = parse("[node]\nversion = 24\nframework = \"vite\"\n");
        assertThat(build.project().nodeSpec().version()).isEqualTo("24");
        assertThat(build.node().framework()).isEqualTo("vite");
        assertThat(build.declaresNodeTable()).isTrue();
    }

    @Test
    void a_version_alone_is_the_toolchain_and_no_node_table() {
        assertThat(parse("node = 24\n").declaresNodeTable()).isFalse();
        assertThat(parse("[node]\nversion = 24\n").declaresNodeTable()).isFalse();
        assertThat(parse("node = 24\n").node()).isEqualTo(NodeTable.EMPTY);
    }

    @Test
    void a_vendor_an_equals_major_and_a_keyword_other_than_lts_are_refused() {
        assertThatThrownBy(() -> parse("node = \"temurin-24\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("node takes a version, not a vendor");
        assertThatThrownBy(() -> parse("node = \"=24\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("pins nothing");
        assertThatThrownBy(() -> parse("node = \"latest\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("node must be a version or lts");
        assertThatThrownBy(() -> parse("[node]\nversion = 24\nworkspace = true\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("keep one");
    }

    @Test
    void a_member_without_its_own_node_inherits_the_workspace_root_s() throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                name = "ws"
                group = "g"
                version = "1.0"
                node = 24

                [workspace]
                modules = ["web", "pinned"]
                """);
        for (String m : List.of("web", "pinned")) Files.createDirectories(tmp.resolve(m));
        Files.writeString(tmp.resolve("web/jk.toml"), "name = \"web\"\n\n[node]\nframework = \"vite\"\n");
        Files.writeString(tmp.resolve("web/package.json"), "{}");
        Files.writeString(tmp.resolve("web/package-lock.json"), "{}");
        Files.writeString(tmp.resolve("pinned/jk.toml"), "name = \"pinned\"\nnode = \"=22.20.0\"\n");

        assertThat(JkBuildParser.parse(tmp.resolve("web/jk.toml"))
                        .project()
                        .nodeSpec()
                        .version())
                .isEqualTo("24");
        assertThat(JkBuildParser.parse(tmp.resolve("pinned/jk.toml"))
                        .project()
                        .nodeSpec()
                        .version())
                .isEqualTo("22.20.0");
    }

    @Test
    void every_node_table_key_parses() {
        JkBuild build = parse("""
                [node]
                version = 24
                package-manager = "pnpm"
                framework = "angular"
                install = "pnpm install --frozen-lockfile --ignore-scripts"
                build = { npx = "ng build --configuration production" }
                test = "test:ci"
                dev = "start"
                start = "node dist/server/server.mjs"
                out = "dist/app/browser"
                classpath-root = "public"
                webapp-root = "jsbundles"
                env-prefixes = ["NG_APP_"]
                dev-port = 4300
                dir = "frontend"
                skip = true
                exports = { android-assets = "out/android" }

                [[node.steps]]
                name = "api-types"
                npx = "openapi-typescript ../api/openapi.yaml -o src/api.ts"
                before = "build"
                inputs = ["../api/openapi.yaml"]
                outputs = ["src/api.ts"]

                [[node.steps]]
                name = "lint"
                run = "lint:ci"
                tier = "test"
                allow-unlocked = true
                """);
        NodeTable node = build.node();
        assertThat(node.packageManager()).isEqualTo("pnpm");
        assertThat(node.framework()).isEqualTo("angular");
        assertThat(node.install()).isEqualTo("pnpm install --frozen-lockfile --ignore-scripts");
        assertThat(node.build()).isEqualTo(NodeTable.Command.npx("ng build --configuration production"));
        assertThat(node.test()).isEqualTo("test:ci");
        assertThat(node.dev()).isEqualTo("start");
        assertThat(node.start()).isEqualTo("node dist/server/server.mjs");
        assertThat(node.out()).isEqualTo("dist/app/browser");
        assertThat(node.classpathRoot()).isEqualTo("public");
        assertThat(node.webappRoot()).isEqualTo("jsbundles");
        assertThat(node.envPrefixes()).containsExactly("NG_APP_");
        assertThat(node.devPort()).isEqualTo(4300);
        assertThat(node.dir()).isEqualTo("frontend");
        assertThat(node.skip()).isTrue();
        assertThat(node.exports()).isEqualTo(Map.of("android-assets", "out/android"));
        assertThat(node.steps())
                .containsExactly(
                        new NodeTable.Step(
                                "api-types",
                                NodeTable.Command.npx("openapi-typescript ../api/openapi.yaml -o src/api.ts"),
                                NodeTable.Before.BUILD,
                                NodeTable.Tier.BUILD,
                                List.of("../api/openapi.yaml"),
                                List.of("src/api.ts"),
                                false),
                        new NodeTable.Step(
                                "lint",
                                NodeTable.Command.run("lint:ci"),
                                NodeTable.Before.BUILD,
                                NodeTable.Tier.TEST,
                                List.of(),
                                List.of(),
                                true));
    }

    @Test
    void a_build_script_name_is_a_run_command() {
        assertThat(parse("[node]\nbuild = \"build:prod\"\n").node().build())
                .isEqualTo(NodeTable.Command.run("build:prod"));
    }

    @Test
    void an_unknown_key_names_its_line_and_column() {
        assertThatThrownBy(() -> parse("\n[node]\nframework = \"vite\"\noutdir = \"dist\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[node] unknown key `outdir` at line 7, column 1");
        assertThatThrownBy(() -> parse("[[node.steps]]\nname = \"x\"\nrun = \"x\"\nwhen = \"always\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[[node.steps]] #1 unknown key `when`");
    }

    @Test
    void bad_values_are_refused_with_what_is_allowed() {
        assertThatThrownBy(() -> parse("[node]\npackage-manager = \"cnpm\"\n"))
                .hasMessageContaining("[node] package-manager must be one of npm, pnpm, yarn, bun");
        assertThatThrownBy(() -> parse("[node]\nframework = \"gatsby\"\n"))
                .hasMessageContaining("[node] framework must be one of");
        assertThatThrownBy(() -> parse("[node]\ndev-port = 70000\n")).hasMessageContaining("dev-port");
        assertThatThrownBy(() -> parse("[node]\ndir = \"../web\"\n")).hasMessageContaining("inside the module");
        assertThatThrownBy(() -> parse("[node]\nbuild = { npx = \"a\", run = \"b\" }\n"))
                .hasMessageContaining("more than one of run, npx, exec");
        assertThatThrownBy(() -> parse("[[node.steps]]\nname = \"x\"\n"))
                .hasMessageContaining("needs one of run, npx or exec");
        assertThatThrownBy(() ->
                        parse("[[node.steps]]\nname = \"x\"\nrun = \"a\"\n[[node.steps]]\nname = \"x\"\nrun = \"b\"\n"))
                .hasMessageContaining("used twice");
        assertThatThrownBy(() -> parse("[[node.steps]]\nname = \"x\"\nrun = \"a\"\nbefore = \"deploy\"\n"))
                .hasMessageContaining("before must be one of build, test, package");
    }
}
