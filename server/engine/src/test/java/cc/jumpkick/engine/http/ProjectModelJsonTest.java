// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.base.IdeOps;
import cc.jumpkick.runtime.base.ProvidedJars;
import cc.jumpkick.testing.FakeJdk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code GET /api/project/model}'s body: one JDT project per workspace module, its source roots on
 * disk, the sibling it depends on and its JRE container, all absolute.
 */
class ProjectModelJsonTest {

    @Test
    void a_workspace_is_one_project_per_module_with_its_roots_and_sibling(@TempDir Path tmp) throws Exception {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        FakeJdk.create(jdks.resolve("temurin-25.0.1"), "25.0.1");
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws.resolve("lib/src/main/java"));
        Files.createDirectories(ws.resolve("app/src/main/java"));
        Files.createDirectories(ws.resolve("app/src/test/java"));
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["lib", "app"]
                """);
        Files.writeString(ws.resolve("lib/jk.toml"), """
                group = "com.example"
                name = "lib"
                version = "1.0.0"
                java = 21
                """);
        Files.writeString(ws.resolve("app/jk.toml"), """
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                lib = { workspace = true }
                """);

        Map<String, Object> body =
                ProjectModelJson.of(IdeOps.ideModel(ws, tmp.resolve("cache"), jdks, null, ProvidedJars.NONE));

        assertThat(body).doesNotContainKey("error").containsEntry("workspace", true);
        List<?> projects = (List<?>) Objects.requireNonNull(body.get("projects"));
        assertThat(projects).hasSize(2);
        Map<?, ?> lib = (Map<?, ?>) projects.get(0);
        Map<?, ?> app = (Map<?, ?>) projects.get(1);
        assertThat(lib.get("name")).isEqualTo("lib");
        assertThat(lib.get("compliance")).isEqualTo("21");
        assertThat(app.get("name")).isEqualTo("app");
        assertThat(app.get("dir")).isEqualTo(ws.resolve("app").toString());
        assertThat(app.get("compliance")).isEqualTo("25");

        List<Object> entries = List.copyOf((List<?>) Objects.requireNonNull(app.get("entries")));
        assertThat(entries)
                .contains(
                        Map.of(
                                "kind",
                                "source",
                                "path",
                                ws.resolve("app/src/main/java").toString(),
                                "output",
                                ws.resolve("app/target/jdt/classes/main").toString(),
                                "test",
                                false,
                                "generated",
                                false),
                        Map.of(
                                "kind",
                                "source",
                                "path",
                                ws.resolve("app/src/test/java").toString(),
                                "output",
                                ws.resolve("app/target/jdt/classes/test").toString(),
                                "test",
                                true,
                                "generated",
                                false),
                        Map.of("kind", "project", "name", "lib", "test", false));
        assertThat(entries).anySatisfy(e -> assertThat(((Map<?, ?>) e).get("path"))
                .isEqualTo("org.eclipse.jdt.launching.JRE_CONTAINER/"
                        + "org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-25"));
    }

    @Test
    void a_folder_without_jk_toml_is_an_error(@TempDir Path tmp) {
        Map<String, Object> body =
                ProjectModelJson.of(IdeOps.ideModel(tmp, tmp.resolve("cache"), tmp, null, ProvidedJars.NONE));
        assertThat(body).containsOnlyKeys("error");
        assertThat(String.valueOf(body.get("error"))).contains("no jk.toml");
    }
}
