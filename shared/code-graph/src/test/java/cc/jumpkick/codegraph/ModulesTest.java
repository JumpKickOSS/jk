// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class ModulesTest {

    private final Modules modules = new Modules(List.of(
            new Modules.Module("", false),
            new Modules.Module("server/engine/", false),
            new Modules.Module("tools/", true)));

    @Test
    void theLongestPrefixHoldsAFile() {
        assertThat(modules.of("server/engine/src/main/java/A.java"))
                .extracting(Modules.Module::prefix)
                .isEqualTo("server/engine/");
        assertThat(modules.of("README.md")).extracting(Modules.Module::prefix).isEqualTo("");
    }

    @Test
    void sourceSetFollowsTheLayout() {
        assertThat(modules.sourceSet("server/engine/src/main/java/A.java")).isEqualTo(Modules.SourceSet.MAIN);
        assertThat(modules.sourceSet("server/engine/src/test/java/ATest.java")).isEqualTo(Modules.SourceSet.TEST);
        assertThat(modules.sourceSet("server/engine/src/integration/java/AIT.java"))
                .isEqualTo(Modules.SourceSet.TEST);
        assertThat(modules.sourceSet("server/engine/jk.toml")).isEqualTo(Modules.SourceSet.OTHER);
        assertThat(modules.sourceSet("tools/src/a/Main.java")).isEqualTo(Modules.SourceSet.MAIN);
        assertThat(modules.sourceSet("tools/test/src/a/MainTest.java")).isEqualTo(Modules.SourceSet.TEST);
        assertThat(Modules.NONE.sourceSet("lib/src/test/java/X.java")).isEqualTo(Modules.SourceSet.TEST);
    }

    @Test
    void roundTripsThroughTheSnapshot() throws Exception {
        CodeGraph g = CodeGraph.merge(List.of(new Fragment(
                List.of(Decl.with(
                        new GraphNode("a", "A", "code", "src/main/java/A.java", "L1"),
                        Map.of(Decl.KIND, "class", Decl.END, 4))),
                List.of())));
        g.modules(modules);
        var bytes = new ByteArrayOutputStream();
        GraphSnapshot.write(g, new GraphSnapshot.Key("x", 1, "c", null), bytes);
        var loaded = Objects.requireNonNull(GraphSnapshot.read(new ByteArrayInputStream(bytes.toByteArray())));
        assertThat(loaded.graph().modules()).isEqualTo(modules);
        assertThat(Decl.kind(Objects.requireNonNull(loaded.graph().node("a")))).isEqualTo("class");
        assertThat(Decl.end(Objects.requireNonNull(loaded.graph().node("a")))).isEqualTo(4);
    }
}
