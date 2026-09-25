// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.testing.Symlinks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewProjectOpsTest {

    @Test
    void creates_plain_java_project(@TempDir Path temp) throws Exception {
        // temp is under java.io.tmpdir → allowed parent
        var result = NewProjectOps.create(
                new NewProjectOps.Request("widget", temp.toString(), "com.acme", "java", "simple", null, true));
        Path root = result.path();
        assertThat(root).isEqualTo(temp.resolve("widget"));
        assertThat(root.resolve("jk.toml")).exists();
        assertThat(root.resolve("AGENTS.md")).exists();
        assertThat(Files.readString(root.resolve("AGENTS.md"))).contains("jk skill");
        String toml = Files.readString(root.resolve("jk.toml"));
        assertThat(toml).contains("name     = \"widget\"");
        assertThat(toml).contains("group    = \"com.acme\"");
        assertThat(root.resolve("src")).isDirectory();
    }

    @Test
    void rejects_existing_project(@TempDir Path temp) throws Exception {
        NewProjectOps.create(
                new NewProjectOps.Request("dup", temp.toString(), "com.example", "java", "simple", null, false));
        assertThatThrownBy(() -> NewProjectOps.create(new NewProjectOps.Request(
                        "dup", temp.toString(), "com.example", "java", "simple", null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void rejects_bad_name(@TempDir Path temp) {
        assertThatThrownBy(() -> NewProjectOps.create(new NewProjectOps.Request(
                        "../evil", temp.toString(), "com.example", "java", "simple", null, true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_unknown_layout(@TempDir Path temp) {
        assertThatThrownBy(() -> NewProjectOps.create(new NewProjectOps.Request(
                        "widget", temp.toString(), "com.example", "java", "mill", null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("layout must be");
    }

    @Test
    void auto_layout_scaffolds_traditional_tree(@TempDir Path temp) throws Exception {
        var result = NewProjectOps.create(
                new NewProjectOps.Request("widget", temp.toString(), "com.acme", "java", "auto", null, false));
        Path root = result.path();
        assertThat(root.resolve("src/main/java")).isDirectory();
        assertThat(Files.readString(root.resolve("jk.toml"))).doesNotContain("layout");
    }

    @Test
    void target_dir_scaffolds_into_the_requested_directory(@TempDir Path temp) throws Exception {
        // targetDir wins over parentDir/name for the write location.
        Path custom = temp.resolve("elsewhere/custom-home");
        var created = NewProjectOps.create(new NewProjectOps.Request(
                "x",
                temp.toString(),
                "com.example",
                "java",
                "simple",
                null,
                true,
                null,
                0,
                false,
                false,
                false,
                null,
                List.of(),
                true,
                true,
                Map.of(),
                true,
                custom.toString()));
        assertThat(created.path()).isEqualTo(custom.toAbsolutePath().normalize());
        assertThat(custom.resolve("jk.toml")).exists();
        assertThat(temp.resolve("x")).doesNotExist();
    }

    @Test
    void target_dir_outside_the_allowlist_is_refused_without_relax(@TempDir Path temp) {
        assertThatThrownBy(() -> NewProjectOps.create(new NewProjectOps.Request(
                        "x",
                        temp.toString(),
                        "com.example",
                        "java",
                        "simple",
                        null,
                        true,
                        null,
                        0,
                        false,
                        false,
                        false,
                        null,
                        List.of(),
                        true,
                        true,
                        Map.of(),
                        false,
                        "/etc/pwned")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HOME");
    }

    @Test
    void rejects_path_outside_home_and_tmp() {
        assertThatThrownBy(() -> NewProjectOps.create(
                        new NewProjectOps.Request("x", "/etc", "com.example", "java", "simple", null, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HOME");
    }

    @Test
    void symlinked_parent_pointing_outside_the_allowlist_is_refused(@TempDir Path temp) throws Exception {
        // Java.io.tmpdir is world-writable, so another local user can plant a link there;
        // a lexical check would accept it and the scaffolder would write through it. The target
        // must be genuinely outside both roots — @TempDir usually lives under /tmp, so it is not.
        Path outside = Path.of("/etc");
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(outside)
                        && !outside.startsWith(home)
                        && !outside.startsWith(tmpDir.toAbsolutePath().normalize()),
                "needs a directory outside $HOME and the temp dir");
        Path link = tmpDir.resolve("jk-test-escape-" + ProcessHandle.current().pid());
        Symlinks.create(link, outside);
        try {
            assertThatThrownBy(() -> NewProjectOps.assertAllowedParent(link))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be under");
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void a_real_directory_under_tmp_is_still_allowed(@TempDir Path temp) throws Exception {
        Path real = Files.createDirectories(Path.of(
                System.getProperty("java.io.tmpdir"),
                "jk-test-ok-" + ProcessHandle.current().pid()));
        try {
            NewProjectOps.assertAllowedParent(real); // must not throw
        } finally {
            Files.deleteIfExists(real);
        }
    }

    @Test
    void template_apply_seeds_agents_md_when_missing(@TempDir Path temp) throws Exception {
        Path g8 = temp.resolve("seed-agents.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve("default.properties"), "name=demo\n");
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), "name = \"$name$\"\n");
        Path parent = temp.resolve("apps");
        Files.createDirectories(parent);
        var result = NewProjectOps.create(new NewProjectOps.Request(
                "widget",
                parent.toString(),
                "com.acme",
                "java",
                "traditional",
                g8.toAbsolutePath().toString(),
                true));
        assertThat(result.path().resolve("jk.toml")).exists();
        assertThat(result.path().resolve("AGENTS.md")).exists();
        assertThat(Files.readString(result.path().resolve("AGENTS.md"))).contains("jk skill");
    }

    @Test
    void failed_template_apply_cleans_the_target_so_retry_works(@TempDir Path temp) throws Exception {
        Path g8 = temp.resolve("no-jktoml.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve("default.properties"), "name=demo\n");
        Files.writeString(g8.resolve("src/main/g8/README.md"), "# $name$\n");
        Path parent = temp.resolve("apps");
        Files.createDirectories(parent);
        var request = new NewProjectOps.Request(
                "widget",
                parent.toString(),
                "com.acme",
                "java",
                "traditional",
                g8.toAbsolutePath().toString(),
                true);

        assertThatThrownBy(() -> NewProjectOps.create(request)).hasMessageContaining("did not produce jk.toml");
        assertThat(parent.resolve("widget"))
                .as("failed scaffold must not leave a half-written target")
                .doesNotExist();

        // Fixing the template makes the same request succeed — no manual rm -rf in between.
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), "name = \"$name$\"\n");
        assertThat(NewProjectOps.create(request).path().resolve("jk.toml")).exists();
    }

    @Test
    void layout_simple_reaches_path_templates(@TempDir Path temp) throws Exception {
        // Path refs have no indexed metadata (spec empty) — the flag must still set simple=yes.
        Path g8 = temp.resolve("dual.g8");
        String src = "src/main/g8/src/$if(simple.truthy)$.$else$main$endif$/$if(simple.truthy)$.$else$java$endif$";
        Files.createDirectories(g8.resolve(src));
        Files.writeString(g8.resolve("default.properties"), "name=demo\n");
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), "name = \"$name$\"\n");
        Files.writeString(g8.resolve(src + "/Main.java"), "class Main {}\n");
        Path parent = temp.resolve("apps");
        Files.createDirectories(parent);

        var result = NewProjectOps.create(new NewProjectOps.Request(
                "widget",
                parent.toString(),
                "com.acme",
                "java",
                "simple",
                g8.toAbsolutePath().toString(),
                true));

        assertThat(result.path().resolve("src/Main.java")).exists();
        assertThat(result.path().resolve("src/main/java")).doesNotExist();
    }

    @Test
    void layout_simple_refused_when_template_metadata_omits_it(@TempDir Path temp) throws Exception {
        Path templates = temp.resolve("templates");
        Path g8 = templates.resolve("java").resolve("none").resolve("acme-trad-only.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve(".jk-template.toml"), """
                language = "java"
                framework = "none"
                name = "acme-trad-only"
                description = "demo"
                layouts = ["traditional"]
                """);
        Files.writeString(g8.resolve("default.properties"), "name=demo\n");
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), "name = \"$name$\"\n");
        Path parent = temp.resolve("apps");
        Files.createDirectories(parent);

        assertThatThrownBy(() -> NewProjectOps.create(new NewProjectOps.Request(
                        "widget", parent.toString(), "com.acme", "java", "simple", "acme-trad-only", true)))
                .hasMessageContaining("does not support --layout simple")
                .hasMessageContaining("traditional");
        assertThat(parent.resolve("widget")).doesNotExist();
    }

    @Test
    void resolve_template_finds_dogfood_short_name(@TempDir Path temp) throws Exception {
        // Unique short name so the official cache cannot steal the hit.
        Path templates = temp.resolve("templates");
        Path g8 = templates.resolve("java").resolve("none").resolve("acme-demo.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve(".jk-template.toml"), """
                language = "java"
                framework = "none"
                name = "acme-demo"
                description = "demo"
                layouts = ["traditional"]
                """);
        Files.writeString(g8.resolve("default.properties"), "name=demo\n");
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), "name=demo\n");
        Path parent = temp.resolve("apps");
        Files.createDirectories(parent);
        Path resolved = NewProjectOps.resolveTemplate("acme-demo", parent);
        assertThat(resolved).isEqualTo(g8.toAbsolutePath().normalize());
    }
}
