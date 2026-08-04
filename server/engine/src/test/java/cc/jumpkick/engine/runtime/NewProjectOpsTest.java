// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewProjectOpsTest {

    @Test
    void creates_plain_java_project(@TempDir Path temp) throws Exception {
        // temp is under java.io.tmpdir → allowed parent
        var result = NewProjectOps.create(new NewProjectOps.Request(
                "widget", temp.toString(), "com.acme", "java", "simple", null, true, null));
        Path root = result.path();
        assertThat(root).isEqualTo(temp.resolve("widget"));
        assertThat(root.resolve("jk.toml")).exists();
        String toml = Files.readString(root.resolve("jk.toml"));
        assertThat(toml).contains("name     = \"widget\"");
        assertThat(toml).contains("group    = \"com.acme\"");
        assertThat(root.resolve("src")).isDirectory();
    }

    @Test
    void rejects_existing_project(@TempDir Path temp) throws Exception {
        NewProjectOps.create(new NewProjectOps.Request(
                "dup", temp.toString(), "com.example", "java", "simple", null, false, null));
        assertThatThrownBy(() -> NewProjectOps.create(new NewProjectOps.Request(
                        "dup", temp.toString(), "com.example", "java", "simple", null, false, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void rejects_bad_name(@TempDir Path temp) {
        assertThatThrownBy(() -> NewProjectOps.create(new NewProjectOps.Request(
                        "../evil", temp.toString(), "com.example", "java", "simple", null, true, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_path_outside_home_and_tmp() {
        assertThatThrownBy(() -> NewProjectOps.create(new NewProjectOps.Request(
                        "x", "/etc", "com.example", "java", "simple", null, true, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HOME");
    }

    @Test
    void extract_from_jar_reuses_one_tree_and_cleans_up_misses(@TempDir Path temp) throws Exception {
        // JK-1457: one extraction per short name per engine run; a missing prefix leaves no tree.
        Path jar = temp.resolve("templates.jar");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.zip.ZipEntry("giter8/acme-jar-demo/default.properties"));
            out.write("name=demo\n".getBytes());
            out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("giter8/acme-jar-demo/src/main/g8/jk.toml"));
            out.write("name=demo\n".getBytes());
            out.closeEntry();
        }
        java.net.URI jarUri = java.net.URI.create("jar:" + jar.toUri());
        Path first = NewProjectOps.extractFromJar(jarUri, "giter8/acme-jar-demo", "acme-jar-demo");
        Path second = NewProjectOps.extractFromJar(jarUri, "giter8/acme-jar-demo", "acme-jar-demo");
        assertThat(first).isNotNull();
        assertThat(Files.isRegularFile(first.resolve("default.properties"))).isTrue();
        assertThat(second).isEqualTo(first); // reused, not re-extracted

        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        long before;
        try (var s = Files.list(tmpDir)) {
            before = s.filter(p -> p.getFileName().toString().startsWith("jk-g8-acme-jar-miss-")).count();
        }
        assertThat(NewProjectOps.extractFromJar(jarUri, "giter8/no-such-prefix", "acme-jar-miss")).isNull();
        try (var s = Files.list(tmpDir)) {
            long after = s.filter(p -> p.getFileName().toString().startsWith("jk-g8-acme-jar-miss-")).count();
            assertThat(after).isEqualTo(before); // miss left no temp tree behind
        }
    }

    @Test
    void resolve_template_finds_dogfood_short_name(@TempDir Path temp) throws Exception {
        // Unique short name so the official cache cannot steal the hit.
        Path templates = temp.resolve("templates");
        Path g8 = templates.resolve("acme-demo.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(
                g8.resolve("default.properties"),
                "name=demo\njk_languages=java\njk_layout=traditional\n");
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), "name=demo\n");
        Path parent = temp.resolve("apps");
        Files.createDirectories(parent);
        Path resolved = NewProjectOps.resolveTemplate("acme-demo", parent);
        assertThat(resolved).isEqualTo(g8.toAbsolutePath().normalize());
    }
}
