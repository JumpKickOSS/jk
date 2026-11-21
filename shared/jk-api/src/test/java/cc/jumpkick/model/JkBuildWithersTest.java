// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A wither replaces its own table and carries every other one over unchanged. */
class JkBuildWithersTest {

    private static PluginConfig config(String id) {
        return new PluginConfig(id, Map.of("k", id), Map.of());
    }

    private static final JkBuild BASE = JkBuild.builder(
                    Project.builder("g", "app", "1.0").build())
            .repositories(List.of(new RepositorySpec("corp", URI.create("https://repo.example/maven"))))
            .manifest(Map.of("X-Built-By", "jk"))
            .application(new JkBuild.Application("app.Main", false))
            .pluginConfig(config("first"))
            .pluginConfig(config("second"))
            .publish(PomMetadata.EMPTY)
            .build();

    @Test
    void each_table_wither_replaces_only_its_table() {
        JkBuild manifest = BASE.withManifest(Map.of("X-Other", "1"));
        assertThat(manifest.manifest()).containsExactly(Map.entry("X-Other", "1"));
        assertThat(manifest.withManifest(BASE.manifest())).isEqualTo(BASE);

        JkBuild repos = BASE.withRepositories(List.of());
        assertThat(repos.repositories()).isEmpty();
        assertThat(repos.withRepositories(BASE.repositories())).isEqualTo(BASE);

        assertThat(BASE.withBuild(BuildBlock.EMPTY)).isEqualTo(BASE);

        JkBuild publish = BASE.withPublish(null);
        assertThat(publish.publish()).isNull();
        assertThat(publish.withPublish(BASE.publish())).isEqualTo(BASE);

        JkBuild plugins = BASE.withPlugins(List.of());
        assertThat(plugins).isEqualTo(BASE);
    }

    @Test
    void a_plugin_config_is_replaced_in_place_and_removed_by_id() {
        PluginConfig replaced = new PluginConfig("first", Map.of("k", "again"), Map.of());

        JkBuild with = BASE.withPluginConfig(replaced);
        assertThat(with.pluginConfigs().keySet()).containsExactly("first", "second");
        assertThat(with.pluginConfigs().get("first")).isEqualTo(replaced);
        assertThat(with.withPluginConfig(config("first"))).isEqualTo(BASE);

        JkBuild without = BASE.withoutPluginConfig("first");
        assertThat(without.pluginConfigs().keySet()).containsExactly("second");
        assertThat(without.withoutPluginConfig("absent")).isSameAs(without);
        assertThat(without.application()).isEqualTo(BASE.application());
        assertThat(BASE.pluginConfigs()).as("the original is untouched").hasSize(2);
    }

    @Test
    void artifacts_rewrite_the_application_and_keep_its_main() {
        JkBuild fat = BASE.withArtifacts(true, false);

        assertThat(fat.assembly()).isTrue();
        assertThat(fat.mainClass()).isEqualTo("app.Main");
        assertThat(fat.withArtifacts(false, false)).isEqualTo(BASE);
        assertThat(BASE.withArtifacts(false, false)).isSameAs(BASE);
    }
}
