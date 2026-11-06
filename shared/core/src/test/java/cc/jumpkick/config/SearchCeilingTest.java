// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.SearchPath;
import cc.jumpkick.testing.Symlinks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchCeilingTest {

    private static final String OVERLAY = "jk.env." + SearchCeiling.ENV;

    private static final String WORKSPACE = """
            group   = "com.example"
            name    = "ws"
            version = "0.1.0"

            [workspace]
            modules = ["fence/app"]
            """;

    @Test
    void a_search_climbs_to_the_directory_below_a_ceiling_and_no_further(@TempDir Path tmp) {
        Path fence = tmp.resolve("fence");
        SearchCeiling ceiling = SearchCeiling.parse(fence.toString());

        assertThat(ceiling.above(fence.resolve("a/b"))).isEqualTo(fence.resolve("a"));
        assertThat(ceiling.above(fence.resolve("a"))).isNull();
        // A search that starts at the ceiling, or outside every ceiling, climbs as it would without one.
        assertThat(ceiling.above(fence)).isEqualTo(tmp);
        assertThat(ceiling.above(tmp.resolve("other/x"))).isEqualTo(tmp.resolve("other"));
    }

    @Test
    void entries_are_separated_by_the_path_separator_and_relative_or_empty_ones_are_ignored(@TempDir Path tmp) {
        Path one = tmp.resolve("one");
        Path two = tmp.resolve("two");
        SearchCeiling ceiling = SearchCeiling.parse(
                String.join(SearchPath.SEPARATOR, one.toString(), "", " relative/dir ", two.toString()));

        assertThat(ceiling.above(one.resolve("x"))).isNull();
        assertThat(ceiling.above(two.resolve("x"))).isNull();
        assertThat(ceiling.above(Path.of("relative/dir/x").toAbsolutePath()))
                .isEqualTo(Path.of("relative/dir").toAbsolutePath());
        assertThat(SearchCeiling.parse(null).above(one.resolve("x"))).isEqualTo(one);
        assertThat(SearchCeiling.parse("  ").above(one.resolve("x"))).isEqualTo(one);
    }

    @Test
    void a_ceiling_spelled_through_a_link_stops_a_search_through_its_target(@TempDir Path tmp) throws IOException {
        Path real = Files.createDirectories(tmp.resolve("real"));
        Path link = Symlinks.create(tmp.resolve("link"), real);
        assertThat(SearchCeiling.parse(link.toString()).above(real.toRealPath().resolve("x")))
                .isNull();
    }

    @Test
    void the_project_and_workspace_searches_stop_below_the_ceiling(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("jk.toml"), WORKSPACE);
        Path app = Files.createDirectories(tmp.resolve("fence/app"));
        Files.writeString(app.resolve("jk.toml"), "name = \"app\"\n");
        Path deep = Files.createDirectories(tmp.resolve("fence/scratch/deep"));

        // With no ceiling between them every search reaches the workspace at the top.
        assertThat(WorkspaceScan.findRoot(app)).contains(tmp);
        assertThat(WorkspaceScan.findEnclosingWorkspace(deep)).contains(tmp);
        assertThat(ConfigSources.findProjectConfig(deep)).isEqualTo(tmp.resolve("jk.toml"));

        System.setProperty(OVERLAY, tmp.resolve("fence").toString());
        try {
            assertThat(WorkspaceScan.findRoot(app)).isEmpty();
            assertThat(WorkspaceScan.owningRoot(app)).isEmpty();
            assertThat(WorkspaceScan.findEnclosingWorkspace(deep)).isEmpty();
            assertThat(ConfigSources.findProjectConfig(deep)).isNull();
            // The directory a search starts in is always examined.
            assertThat(ConfigSources.findProjectConfig(app)).isEqualTo(app.resolve("jk.toml"));
        } finally {
            System.clearProperty(OVERLAY);
        }
    }
}
