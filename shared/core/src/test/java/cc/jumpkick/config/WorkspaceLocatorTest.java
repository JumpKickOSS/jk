// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceLocatorTest {

    private static final String ROOT = """
            group    = "cc.jumpkick"
            name     = "jk"
            version  = "0.1.0"

            [workspace]
            modules = ["core"]
            """;

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * A sandbox OUTSIDE the repo tree. The build points {@code java.io.tmpdir} at {@code build/tmp}
     * (inside the checkout), so a plain {@code @TempDir} has the repo's own workspace {@code jk.toml}
     * as an ancestor — which strict-ancestor "no enclosing workspace" assertions would wrongly find
     *. Rooting under the user home escapes the checkout.
     */
    private static Path isolatedRoot() throws IOException {
        return Files.createTempDirectory(Path.of(System.getProperty("user.home")), ".jk-wsl-test-");
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }

    @Test
    void finds_root_for_a_module_listed_through_a_glob(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                group = "com.example"
                name = "root"
                version = "0.1.0"

                [workspace]
                modules = ["libs/*"]
                """);
        Path core = tmp.resolve("libs/core");
        write(core.resolve("jk.toml"), "name = \"core\"\n");
        assertThat(WorkspaceLocator.findRoot(core))
                .contains(tmp.toAbsolutePath().normalize());
        Path stray = tmp.resolve("apps/stray");
        write(stray.resolve("jk.toml"), "name = \"stray\"\n");
        assertThat(WorkspaceLocator.findRoot(stray)).isEmpty();
    }

    @Test
    void a_pom_only_reactor_is_a_workspace_root_and_its_leaves_find_it(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("pom.xml"), pom("reactor", """
                  <modules>
                    <module>api</module>
                  </modules>
                  <profiles>
                    <profile>
                      <id>extras</id>
                      <modules>
                        <module>tools/bench</module>
                      </modules>
                    </profile>
                  </profiles>
                """));
        Path api = tmp.resolve("api");
        write(api.resolve("pom.xml"), pom("api", ""));
        Path bench = tmp.resolve("tools/bench");
        write(bench.resolve("pom.xml"), pom("bench", ""));
        Path root = tmp.toAbsolutePath().normalize();

        assertThat(WorkspaceScan.isWorkspaceRoot(tmp)).isTrue();
        assertThat(WorkspaceScan.isWorkspaceRoot(api)).isFalse();
        assertThat(WorkspaceLocator.findRoot(api)).contains(root);
        assertThat(WorkspaceLocator.findRoot(bench)).as("a profile's module").contains(root);
        assertThat(WorkspaceLocator.owningRoot(tmp)).contains(root);
        assertThat(PomReactorScan.memberDirs(tmp)).containsExactly("api", "tools/bench");
        assertThat(PomReactorScan.profilesListing(tmp, bench)).containsExactly("extras");
        assertThat(PomReactorScan.profilesListing(tmp, api))
                .as("a top-level module")
                .isEmpty();
        // Registering a module needs a jk.toml to write into; a POM root offers none.
        assertThat(WorkspaceLocator.findEnclosingWorkspace(api).orElse(null)).isNotEqualTo(root);

        Path stray = tmp.resolve("stray");
        write(stray.resolve("pom.xml"), pom("stray", ""));
        assertThat(WorkspaceLocator.findRoot(stray)).isEmpty();
    }

    @Test
    void a_jk_toml_beside_the_pom_speaks_for_the_directory(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("pom.xml"), pom("reactor", """
                  <modules>
                    <module>api</module>
                  </modules>
                """));
        write(tmp.resolve("jk.toml"), "group = \"com.example\"\nname = \"root\"\nversion = \"1\"\n");
        assertThat(WorkspaceScan.isWorkspaceRoot(tmp)).isFalse();
        assertThat(PomReactorScan.declaresModules(tmp.resolve("pom.xml"))).isTrue();
    }

    private static String pom(String artifactId, String body) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                %s</project>
                """.formatted(artifactId, body);
    }

    @Test
    void finds_enclosing_workspace_for_unlisted_module(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), ROOT);
        // `app` is NOT in modules yet — findEnclosingWorkspace must still find the root.
        Path app = Files.createDirectories(tmp.resolve("app"));

        assertThat(WorkspaceLocator.findEnclosingWorkspace(app))
                .contains(tmp.toAbsolutePath().normalize());
    }

    @Test
    void finds_enclosing_workspace_for_nested_path(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), ROOT);
        Path nested = Files.createDirectories(tmp.resolve("packages/foo"));

        assertThat(WorkspaceLocator.findEnclosingWorkspace(nested))
                .contains(tmp.toAbsolutePath().normalize());
    }

    @Test
    void workspace_root_is_not_its_own_enclosing_workspace() throws IOException {
        Path tmp = isolatedRoot();
        try {
            write(tmp.resolve("jk.toml"), ROOT);
            // Strict-ancestor search: the root dir itself has no enclosing workspace.
            assertThat(WorkspaceLocator.findEnclosingWorkspace(tmp)).isEmpty();
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void standalone_project_has_no_enclosing_workspace() throws IOException {
        Path tmp = isolatedRoot();
        try {
            write(tmp.resolve("jk.toml"), """
                    group    = "com.example"
                    name     = "widget"
                    version  = "0.1.0"
                    """);
            Path sub = Files.createDirectories(tmp.resolve("sub"));
            assertThat(WorkspaceLocator.findEnclosingWorkspace(sub)).isEmpty();
        } finally {
            deleteRecursively(tmp);
        }
    }
}
