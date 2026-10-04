// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.model.NodeTable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What {@link NodeProject#infer} reads from a node build's files, and what {@code [node]} overrides. */
class NodeProjectTest {

    @TempDir
    Path dir;

    private void write(String name, String body) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    private NodeProject infer(String packageJson, String... files) throws IOException {
        write("package.json", packageJson);
        for (String f : files) write(f, "");
        return NodeProject.infer(dir, NodeTable.EMPTY);
    }

    @Test
    void sveltekit_configured_in_vite_config_alone_is_sveltekit() throws IOException {
        // The current `sv create` shape: the kit plugin in vite.config.ts, no svelte.config.js.
        NodeProject p = infer("""
                {"scripts": {"dev": "vite dev", "build": "vite build"},
                 "devDependencies": {"@sveltejs/kit": "2", "@sveltejs/adapter-node": "5", "vite": "7"}}
                """, "vite.config.ts", "package-lock.json");
        assertThat(p.framework()).isEqualTo("sveltekit");
        assertThat(p.out()).isEqualTo("build");
        assertThat(p.start()).isEqualTo("node build/index.js");
    }

    @Test
    void a_vite_app_builds_dist_on_5173_with_vite_env() throws IOException {
        NodeProject p = infer("""
                {"scripts": {"dev": "vite", "build": "tsc -b && vite build", "test": "vitest run"}}
                """, "vite.config.ts", "package-lock.json");
        assertThat(p.framework()).isEqualTo("vite");
        assertThat(p.packageManager()).isEqualTo("npm");
        assertThat(p.build()).isEqualTo(NodeTable.Command.run("build"));
        assertThat(p.test()).isEqualTo("test");
        assertThat(p.dev()).isEqualTo("dev");
        assertThat(p.out()).isEqualTo("dist");
        assertThat(p.devPort()).isEqualTo(5173);
        assertThat(p.envPrefixes()).containsExactly("VITE_");
        assertThat(p.server()).isFalse();
    }

    @Test
    void vitest_alone_is_not_vite() throws IOException {
        assertThat(infer("{\"scripts\": {\"test\": \"vitest run\"}}", "package-lock.json")
                        .framework())
                .isEqualTo("plain");
    }

    @Test
    void next_is_a_server_unless_it_exports_and_standalone_runs_its_server_js() throws IOException {
        NodeProject plain = infer("{\"scripts\": {\"build\": \"next build\", \"start\": \"next start\"}}");
        assertThat(plain.framework()).isEqualTo("next");
        assertThat(plain.out()).isEqualTo(".next");
        assertThat(plain.start()).isEqualTo("next start");
        assertThat(plain.envPrefixes()).containsExactly("NEXT_PUBLIC_");

        write("next.config.mjs", "export default { output: 'standalone' }");
        assertThat(NodeProject.infer(dir, NodeTable.EMPTY).start()).isEqualTo("node .next/standalone/server.js");

        write("next.config.mjs", "export default { output: 'export' }");
        NodeProject export = NodeProject.infer(dir, NodeTable.EMPTY);
        assertThat(export.out()).isEqualTo("out");
        assertThat(export.server()).isFalse();
    }

    @Test
    void angular_with_no_build_script_builds_through_npx_into_its_browser_dir() throws IOException {
        write("angular.json", "{\"projects\": {\"storefront\": {}}}");
        NodeProject p = infer("{\"name\": \"shop\", \"scripts\": {\"start\": \"ng serve\"}}");
        assertThat(p.framework()).isEqualTo("angular");
        assertThat(p.build()).isEqualTo(NodeTable.Command.npx("ng build"));
        assertThat(p.out()).isEqualTo("dist/storefront/browser");
        assertThat(p.devPort()).isEqualTo(4200);
        assertThat(p.dev()).isEqualTo("start");
    }

    @Test
    void every_framework_row_of_the_matrix() throws IOException {
        record Row(
                String packageJson,
                String file,
                String framework,
                String out,
                @Nullable Integer port,
                @Nullable String start) {}
        List<Row> rows = List.of(
                new Row(
                        "{\"scripts\":{\"dev\":\"nuxt dev\"}}",
                        "nuxt.config.ts",
                        "nuxt",
                        ".output",
                        3000,
                        "node .output/server/index.mjs"),
                new Row(
                        "{\"devDependencies\":{\"@sveltejs/kit\":\"2\",\"@sveltejs/adapter-node\":\"5\"}}",
                        "svelte.config.js",
                        "sveltekit",
                        "build",
                        5173,
                        "node build/index.js"),
                new Row("{\"scripts\":{\"dev\":\"astro dev\"}}", "astro.config.mjs", "astro", "dist", 4321, null),
                new Row(
                        "{\"scripts\":{\"dev\":\"react-router dev\"}}",
                        "react-router.config.ts",
                        "react-router",
                        "build",
                        5173,
                        "react-router-serve build/server/index.js"),
                new Row(
                        "{\"dependencies\":{\"@tanstack/react-start\":\"1\"}}",
                        "app.config.ts",
                        "tanstack-start",
                        ".output",
                        3000,
                        "node .output/server/index.mjs"),
                new Row(
                        "{\"dependencies\":{\"@solidjs/start\":\"1\"}}",
                        "app.config.ts",
                        "solid-start",
                        ".output",
                        3000,
                        "node .output/server/index.mjs"),
                new Row("{\"scripts\":{\"build\":\"tsc\"}}", "tsconfig.json", "plain", "dist", null, null));
        for (Row row : rows) {
            try (var files = Files.list(dir)) {
                for (Path f : files.toList()) Files.delete(f);
            }
            NodeProject p = infer(row.packageJson(), row.file());
            assertThat(p.framework()).as(row.framework()).isEqualTo(row.framework());
            assertThat(p.out()).as(row.framework()).isEqualTo(row.out());
            assertThat(p.devPort()).as(row.framework()).isEqualTo(row.port());
            assertThat(p.start()).as(row.framework()).isEqualTo(row.start());
        }
    }

    @Test
    void the_lockfile_names_the_manager_and_package_manager_its_version() throws IOException {
        assertThat(infer("{}", "pnpm-lock.yaml").packageManager()).isEqualTo("pnpm");
        Files.delete(dir.resolve("pnpm-lock.yaml"));
        assertThat(infer("{}", "bun.lock").packageManager()).isEqualTo("bun");
        Files.delete(dir.resolve("bun.lock"));
        NodeProject berry = infer("{\"packageManager\": \"yarn@4.18.0+sha512.abc\"}", "yarn.lock", ".yarnrc.yml");
        assertThat(berry.packageManager()).isEqualTo("yarn");
        assertThat(berry.packageManagerVersion()).isEqualTo("4.18.0");
        Files.delete(dir.resolve("yarn.lock"));
        Files.delete(dir.resolve(".yarnrc.yml"));
        NodeProject declared =
                infer("{\"devEngines\": {\"packageManager\": {\"name\": \"pnpm\", \"version\": \"10.18.1\"}}}");
        assertThat(declared.packageManager()).isEqualTo("pnpm");
        assertThat(declared.packageManagerVersion()).isEqualTo("10.18.1");
        assertThat(infer("{}").packageManager()).isEqualTo("npm");
    }

    @Test
    void yarn_1_is_refused_with_the_migration_command() throws IOException {
        String fix = "Yarn 1 is not supported — run yarn set version stable && yarn install, then commit";
        assertThatThrownBy(() -> infer("{}", "yarn.lock"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining(fix);
        assertThatThrownBy(() -> infer("{\"packageManager\": \"yarn@1.22.22\"}", "yarn.lock", ".yarnrc.yml"))
                .hasMessageContaining(fix);
        write("package.json", "{}");
        write("yarn.lock", "# THIS IS AN AUTOGENERATED FILE.\n# yarn lockfile v1\n");
        write(".yarnrc.yml", "");
        assertThatThrownBy(() -> NodeProject.infer(dir, NodeTable.EMPTY)).hasMessageContaining(fix);
    }

    @Test
    void npm_s_placeholder_test_is_no_test() throws IOException {
        assertThat(infer("{\"scripts\": {\"test\": \"echo \\\"Error: no test specified\\\" && exit 1\"}}")
                        .test())
                .isNull();
    }

    @Test
    void the_node_table_wins_over_inference() throws IOException {
        write("package.json", "{\"scripts\": {\"dev\": \"vite\", \"build\": \"vite build\"}}");
        write("vite.config.ts", "");
        NodeTable table = new NodeTable(
                "pnpm",
                "plain",
                null,
                NodeTable.Command.npx("vite build --mode staging"),
                "unit",
                "serve",
                "node server.js",
                "public",
                null,
                null,
                List.of(),
                8080,
                null,
                false,
                List.of(),
                Map.of());
        NodeProject p = NodeProject.infer(dir, table);
        assertThat(p.packageManager()).isEqualTo("pnpm");
        assertThat(p.framework()).isEqualTo("plain");
        assertThat(p.build()).isEqualTo(NodeTable.Command.npx("vite build --mode staging"));
        assertThat(p.test()).isEqualTo("unit");
        assertThat(p.dev()).isEqualTo("serve");
        assertThat(p.start()).isEqualTo("node server.js");
        assertThat(p.out()).isEqualTo("public");
        assertThat(p.devPort()).isEqualTo(8080);
        assertThat(p.envPrefixes()).isEmpty();
    }
}
