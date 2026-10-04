// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.node.PackageManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a node module's image ships: which files, at which paths, started how. */
class NodeImageContentTest {

    private static final String DISTROLESS = "gcr.io/distroless/nodejs24-debian13";

    @Test
    void the_default_base_is_distroless_at_the_locked_major() {
        assertThat(NodeImageContent.defaultBase(new NodePin("24.21.0", null, null, Map.of())))
                .isEqualTo(DISTROLESS);
    }

    @Test
    void a_plain_server_ships_its_sources_and_its_production_dependencies_and_starts_node(@TempDir Path dir)
            throws Exception {
        write(dir, "package.json", """
                {"name": "api", "scripts": {"start": "node server.js"}, "dependencies": {"greet": "file:./greet"}}
                """);
        write(dir, "server.js", "require('greet')");
        write(dir, "lib/util.js", "x");
        write(dir, "node_modules/dev-only/index.js", "dev");
        write(dir, "target/stale.txt", "old");

        NodeImageContent.Content content =
                NodeImageContent.server(project(dir), dir, DISTROLESS, (node, nodeDir, scratch) -> {
                    Path modules = scratch.resolve("prod/node_modules");
                    write(modules, "greet/index.js", "module.exports = 1");
                    return modules;
                });

        assertThat(content.layers()).extracting(NodeImageContent.Layer::name).containsExactly("dependencies", "app");
        assertThat(content.layers().get(0).dest()).isEqualTo("/app/node_modules");
        Path app = content.layers().get(1).src();
        assertThat(app.resolve("server.js")).exists();
        assertThat(app.resolve("lib/util.js")).exists();
        assertThat(app.resolve("node_modules")).as("the dev tree never ships").doesNotExist();
        assertThat(app.resolve("target")).doesNotExist();
        assertThat(content.entrypoint()).containsExactly("/nodejs/bin/node", "server.js");
        assertThat(content.workingDir()).isEqualTo("/app");
    }

    @Test
    void a_module_without_dependencies_has_no_dependency_layer_and_a_plain_base_runs_node_from_path(@TempDir Path dir)
            throws Exception {
        write(dir, "package.json", """
                {"name": "api", "scripts": {"start": "node server.js"}}
                """);
        write(dir, "server.js", "");

        NodeImageContent.Content content = NodeImageContent.server(project(dir), dir, "node:24-slim", (n, d, s) -> {
            throw new AssertionError("nothing to install");
        });

        assertThat(content.layers()).extracting(NodeImageContent.Layer::name).containsExactly("app");
        assertThat(content.entrypoint()).containsExactly("node", "server.js");
    }

    @Test
    void a_nitro_output_is_self_contained(@TempDir Path dir) throws Exception {
        write(dir, "package.json", """
                {"name": "shop", "dependencies": {"nuxt": "4.0.0"}, "scripts": {"build": "nuxt build"}}
                """);
        write(dir, "nuxt.config.ts", "export default {}");
        write(dir, ".output/server/index.mjs", "");

        NodeImageContent.Content content = NodeImageContent.server(project(dir), dir, DISTROLESS, (n, d, s) -> {
            throw new AssertionError("Nitro bundles its dependencies");
        });

        assertThat(content.layers()).extracting(NodeImageContent.Layer::name).containsExactly("app");
        assertThat(content.layers().get(0).src().resolve(".output/server/index.mjs"))
                .exists();
        assertThat(content.entrypoint()).containsExactly("/nodejs/bin/node", ".output/server/index.mjs");
    }

    @Test
    void a_start_command_that_is_a_package_bin_runs_that_bin_script(@TempDir Path dir) throws IOException {
        Path modules = dir.resolve("node_modules");
        write(modules, "@react-router/serve/package.json", """
                {"name": "@react-router/serve", "bin": {"react-router-serve": "./bin.js"}}
                """);

        assertThat(NodeImageContent.entrypoint(
                        List.of("react-router-serve", "build/server/index.js"), DISTROLESS, modules))
                .containsExactly(
                        "/nodejs/bin/node", "node_modules/@react-router/serve/bin.js", "build/server/index.js");
        assertThatThrownBy(() -> NodeImageContent.entrypoint(List.of("missing"), DISTROLESS, modules))
                .hasMessageContaining("[node] start");
    }

    @Test
    void a_static_site_is_served_by_nginx_with_a_single_page_fallback(@TempDir Path dir) throws Exception {
        write(dir, "package.json", """
                {"name": "spa", "scripts": {"build": "vite build"}, "devDependencies": {"vite": "7.0.0"}}
                """);
        write(dir, "dist/index.html", "<html/>");

        NodeImageContent.Content content = NodeImageContent.staticSite(project(dir), dir);

        assertThat(content.layers())
                .extracting(NodeImageContent.Layer::dest)
                .containsExactly("/usr/share/nginx/html", "/etc/nginx/conf.d/default.conf");
        assertThat(Files.readString(content.layers().get(1).src()))
                .contains("try_files $uri $uri/ /index.html")
                .contains("immutable");
        assertThat(content.entrypoint()).as("nginx's own").isEmpty();
    }

    @Test
    void each_manager_installs_production_dependencies_without_links() {
        assertThat(NodeImageContent.productionInstall(PackageManager.NPM)).contains("--omit=dev", "--install-links");
        assertThat(NodeImageContent.productionInstall(PackageManager.PNPM))
                .contains("--prod", "--config.node-linker=hoisted");
        assertThat(NodeImageContent.productionInstall(PackageManager.YARN))
                .containsExactly("workspaces", "focus", "--all", "--production");
        assertThat(NodeImageContent.productionInstall(PackageManager.BUN)).contains("--production");
    }

    private static JkBuild project(Path dir) throws IOException {
        write(dir, "jk.toml", "name = \"m\"\ngroup = \"g\"\nversion = \"1.0\"\nnode = 24\n");
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }

    private static void write(Path root, String name, String body) throws IOException {
        Path f = root.resolve(name);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body);
    }
}
