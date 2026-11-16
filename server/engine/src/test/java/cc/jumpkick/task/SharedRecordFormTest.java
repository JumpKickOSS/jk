// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.compile.CompileRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The form of a record that may leave the machine: a javac record whose classpath holds store jars
 * (absolute paths under no module) keeps its outputs and its module-relative inputs, and names no
 * absolute path and no user home.
 */
class SharedRecordFormTest {

    @Test
    void a_record_with_store_jar_inputs_shares_no_absolute_path_and_no_home(@TempDir Path tmp) throws IOException {
        Path module = Files.createDirectories(tmp.resolve("checkout/app"));
        Files.writeString(module.resolve("jk.toml"), "name = \"app\"\n");
        Path src = Files.createDirectories(module.resolve("src/main/java")).resolve("Hello.java");
        Files.writeString(src, "class Hello {}");
        Path store = Files.createDirectories(tmp.resolve("home/someone/.jk/store/repos/central/org/acme/lib/1.0"));
        Path jar = jar(store.resolve("lib-1.0.jar"));
        CompileRequest request = CompileRequest.builder()
                .sources(List.of(src))
                .classpath(List.of(jar))
                .outputDir(module.resolve("target/classes"))
                .release(21)
                .build();

        Map<String, String> inputs = new LinkedHashMap<>(ActionKey.snapshotInputs(request));
        String home = System.getProperty("user.home");
        inputs.put(Path.of(home, ".jk/store/sha256/ab/cdef").toString().replace('\\', '/'), "file:abc");
        assertThat(String.join("\n", inputs.keySet()))
                .as("the local record names the store jar by its absolute path")
                .contains(store.toString().replace('\\', '/'));
        String local = ActionRecordText.render(
                "compile-main@0123456789abcdef0123456789abcdef/target/classes",
                "k".repeat(64),
                inputs,
                Map.of("Hello.class", "a".repeat(64)),
                Set.of(),
                List.of(src + ":1: warning: [serial] something"),
                ActionRecordText.Meta.now(
                        "java:compile", ActionRecordText.Writes.OUTPUTS, "jk/test", "linux-x86_64", -1, Map.of()));

        String shared = ActionRecordText.renderShared(ActionRecordText.parse(local, "k".repeat(64)));

        assertThat(shared)
                .doesNotContain(tmp.toString())
                .doesNotContain(tmp.toString().replace('\\', '/'))
                .doesNotContain(home)
                .doesNotContain("SAID ");
        ActionRecordText.Parsed back = ActionRecordText.parse(shared, "k".repeat(64));
        assertThat(back.outputs()).containsEntry("Hello.class", "a".repeat(64));
        assertThat(back.inputs())
                .containsKey("src/main/java/Hello.java")
                .containsKey("release")
                .containsKey("jdk")
                .noneSatisfy((name, value) -> assertThat(name).startsWith("/"));
    }

    private static Path jar(Path path) throws IOException {
        try (OutputStream out = Files.newOutputStream(path);
                JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry("lib.txt"));
            jar.write("lib".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return path;
    }
}
