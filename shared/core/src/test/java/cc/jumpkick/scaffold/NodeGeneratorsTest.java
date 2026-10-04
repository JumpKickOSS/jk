// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class NodeGeneratorsTest {

    private static List<String> argv(String id, String... params) {
        return NodeGenerators.argv(NodeGenerators.find(id).orElseThrow(), "web", List.of(params));
    }

    @Test
    void every_framework_runs_its_own_generator_through_npx_into_the_directory() {
        assertThat(NodeGenerators.all())
                .extracting(NodeGenerators.Framework::id)
                .containsExactly(
                        "vite-react",
                        "vite-vue",
                        "vite-preact",
                        "vite-solid",
                        "vite-svelte",
                        "next",
                        "angular",
                        "nuxt",
                        "sveltekit",
                        "astro",
                        "react-router",
                        "tanstack-start");
        for (NodeGenerators.Framework f : NodeGenerators.all()) {
            List<String> argv = NodeGenerators.argv(f, "web", List.of());
            assertThat(argv)
                    .as(f.id())
                    .startsWith("npx", "--yes")
                    .contains("web")
                    .doesNotContain("{dir}");
        }
    }

    @Test
    void each_generator_is_asked_for_a_typescript_starter_without_prompts() {
        assertThat(argv("vite-react"))
                .containsExactly(
                        "npx", "--yes", "create-vite@latest", "web", "--template", "react-ts", "--no-interactive");
        assertThat(argv("vite-svelte")).contains("svelte-ts");
        assertThat(argv("next")).contains("create-next-app@latest", "--ts", "--use-npm", "--yes");
        assertThat(argv("angular"))
                .contains("@angular/cli@latest", "new", "web", "--defaults", "--package-manager=npm");
        assertThat(argv("sveltekit")).contains("sv@latest", "create", "web", "--types=ts", "--install=npm");
        assertThat(NodeGenerators.find("NEXT")).isPresent();
        assertThat(NodeGenerators.find("gatsby")).isEmpty();
    }

    @Test
    void params_pass_through_as_flags() {
        assertThat(argv("astro", "template=minimal", "skip-houston", "--typescript=strict"))
                .endsWith("--template=minimal", "--skip-houston", "--typescript=strict");
    }

    @Test
    void the_manifest_pins_node_and_carries_identity_only_when_standalone() {
        assertThat(NodeGenerators.manifest("web", "24", "com.acme", null))
                .isEqualTo("name = \"web\"\ngroup = \"com.acme\"\nversion = \"0.1.0\"\n\nnode = 24\n");
        assertThat(NodeGenerators.manifest("web", "lts", null, ".nvmrc"))
                .isEqualTo("name = \"web\"\n\n# node from .nvmrc\nnode = \"lts\"\n");
        assertThat(NodeGenerators.find("next").orElseThrow().shape()).isEqualTo(NodeGenerators.Shape.SERVER);
        assertThat(NodeGenerators.find("vite-vue").orElseThrow().shape()).isEqualTo(NodeGenerators.Shape.STATIC);
    }
}
