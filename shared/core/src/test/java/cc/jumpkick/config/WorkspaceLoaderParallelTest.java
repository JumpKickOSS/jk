// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A wide workspace's members parse side by side and still load, and fail, as a serial loop did. */
class WorkspaceLoaderParallelTest {

    private static final int MEMBERS = 20;

    @Test
    void members_come_back_in_module_order(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp);
        Map<Path, JkBuild> loaded =
                WorkspaceLoader.loadModules(root, JkBuildParser.parseLocal(root.resolve("jk.toml")));
        List<String> names = new ArrayList<>();
        for (JkBuild member : loaded.values()) names.add(member.project().name());
        assertThat(names).containsExactlyElementsOf(names(MEMBERS));
    }

    @Test
    void every_missing_manifest_is_named_in_module_order(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp);
        Files.delete(root.resolve("mods/m07/jk.toml"));
        Files.delete(root.resolve("mods/m03/jk.toml"));
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(root, JkBuildParser.parseLocal(root.resolve("jk.toml"))))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[mods/m03, mods/m07]");
    }

    @Test
    void the_first_failing_member_in_module_order_is_the_one_raised(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp);
        for (String nested : List.of("m12", "m05")) {
            Path manifest = root.resolve("mods").resolve(nested).resolve("jk.toml");
            Files.writeString(manifest, """
                    group = "com.example"
                    name = "%s"
                    version = "1.0.0"

                    [workspace]
                    modules = ["sub"]
                    """.formatted(nested));
        }
        Throwable thrown = catchThrowable(
                () -> WorkspaceLoader.loadModules(root, JkBuildParser.parseLocal(root.resolve("jk.toml"))));
        assertThat(thrown).as(String.valueOf(thrown)).isInstanceOf(JkBuildParseException.class);
        assertThat(thrown).hasMessageContaining("`mods/m05`");
    }

    private static Path workspace(Path tmp) throws IOException {
        Path root = tmp.resolve("ws");
        StringBuilder modules = new StringBuilder();
        for (String name : names(MEMBERS)) {
            Path dir = Files.createDirectories(root.resolve("mods").resolve(name));
            Files.writeString(dir.resolve("jk.toml"), "name = \"" + name + "\"\n");
            modules.append(modules.isEmpty() ? "" : ", ")
                    .append("\"mods/")
                    .append(name)
                    .append('"');
        }
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = [%s]
                """.formatted(modules));
        return root;
    }

    private static List<String> names(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add("m" + (i < 10 ? "0" + i : Integer.toString(i)));
        return out;
    }
}
