// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.CompileRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionKeyTest {

    @Test
    void same_inputs_produce_same_key(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("Hello.java");
        Files.writeString(src, "class Hello {}");
        CompileRequest request = CompileRequest.builder()
                .sources(List.of(src))
                .outputDir(tempDir.resolve("out"))
                .release(25)
                .build();

        String a = ActionKey.forJavac("compile-main", request, "0.1.0");
        String b = ActionKey.forJavac("compile-main", request, "0.1.0");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void forJavac_then_snapshotInputs_hashes_each_source_once(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("Hello.java");
        Files.writeString(src, "class Hello {}");
        long mtime = System.currentTimeMillis() - 60_000;
        Files.setLastModifiedTime(src, FileTime.fromMillis(mtime));
        CompileRequest request = CompileRequest.builder()
                .sources(List.of(src))
                .outputDir(tempDir.resolve("out"))
                .release(25)
                .build();
        Path cache = tempDir.resolve("cache");
        Files.createDirectories(cache);
        cc.jumpkick.config.SessionContext.runWhere(
                cc.jumpkick.config.Session.defaults().withCacheDir(cache), () -> {
                    try {
                        FileHashMemo.reset();
                        FileHashMemo.resetStats();
                        String key = ActionKey.forJavac("compile-main", request, "0.1.0");
                        var snap = ActionKey.snapshotInputs(request);
                        assertThat(key).isNotBlank();
                        assertThat(snap)
                                .containsKey(src.toAbsolutePath().normalize().toString());
                        assertThat(FileHashMemo.contentReads())
                                .as("forJavac + snapshotInputs share one content read")
                                .isEqualTo(1);
                        assertThat(FileHashMemo.memoHits()).isGreaterThanOrEqualTo(1);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    @Test
    void editing_a_source_changes_the_key(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("Hello.java");
        Files.writeString(src, "class Hello {}");
        CompileRequest request = CompileRequest.builder()
                .sources(List.of(src))
                .outputDir(tempDir.resolve("out"))
                .release(25)
                .build();
        String before = ActionKey.forJavac("compile-main", request, "0.1.0");

        Files.writeString(src, "class Hello { void f() {} }");
        String after = ActionKey.forJavac("compile-main", request, "0.1.0");

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void task_id_part_of_key(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("Hello.java");
        Files.writeString(src, "class Hello {}");
        CompileRequest request = CompileRequest.builder()
                .sources(List.of(src))
                .outputDir(tempDir.resolve("out"))
                .release(25)
                .build();
        String a = ActionKey.forJavac("compile-main", request, "0.1.0");
        String b = ActionKey.forJavac("compile-test", request, "0.1.0");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void release_part_of_key(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("Hello.java");
        Files.writeString(src, "class Hello {}");
        CompileRequest base = CompileRequest.builder()
                .sources(List.of(src))
                .outputDir(tempDir.resolve("out"))
                .release(25)
                .build();
        CompileRequest other = CompileRequest.builder()
                .sources(List.of(src))
                .outputDir(tempDir.resolve("out"))
                .release(21)
                .build();
        assertThat(ActionKey.forJavac("compile-main", base, "0.1.0"))
                .isNotEqualTo(ActionKey.forJavac("compile-main", other, "0.1.0"));
    }

    @Test
    void qualified_task_id_differs_per_module_and_is_stable() {
        Path a = Path.of("/work/projA/target/build/classes/main");
        Path b = Path.of("/work/projB/target/build/classes/main");
        String qa = ActionKey.qualifiedTaskId("compile-main", a);
        String qb = ActionKey.qualifiedTaskId("compile-main", b);

        assertThat(qa).startsWith("compile-main@");
        assertThat(qa).isNotEqualTo(qb); // different modules → no pointer collision
        assertThat(qa).isEqualTo(ActionKey.qualifiedTaskId("compile-main", a)); // stable
        // compile-main vs compile-test in the same module stay distinct too.
        assertThat(qa).isNotEqualTo(ActionKey.qualifiedTaskId("compile-test", a));
    }

    @Test
    void kotlin_plugin_jar_content_is_part_of_action_key(@TempDir Path tempDir) throws IOException {
        // Invariant: upgrading a compiler plugin jar invalidates the action key.
        Path src = tempDir.resolve("Main.kt");
        Files.writeString(src, "fun main() {}");
        Path pluginV1 = tempDir.resolve("plugin-v1.jar");
        Path pluginV2 = tempDir.resolve("plugin-v2.jar");
        Files.writeString(pluginV1, "plugin-bytes-v1");
        Files.writeString(pluginV2, "plugin-bytes-v2");
        Path worker = tempDir.resolve("worker.jar");
        Files.writeString(worker, "worker");

        var base = new cc.jumpkick.compile.KotlincRequest(
                List.of(src),
                List.of(),
                tempDir.resolve("out"),
                17,
                List.of(worker),
                tempDir.resolve("jdk"),
                null,
                null,
                List.of(),
                List.of(new cc.jumpkick.compile.KotlincRequest.Plugin("all-open", pluginV1, List.of())),
                null);
        var upgraded = new cc.jumpkick.compile.KotlincRequest(
                List.of(src),
                List.of(),
                tempDir.resolve("out"),
                17,
                List.of(worker),
                tempDir.resolve("jdk"),
                null,
                null,
                List.of(),
                List.of(new cc.jumpkick.compile.KotlincRequest.Plugin("all-open", pluginV2, List.of())),
                null);

        assertThat(ActionKey.forKotlinc("compile-main", base, "0.1.0"))
                .isNotEqualTo(ActionKey.forKotlinc("compile-main", upgraded, "0.1.0"));
    }

    @Test
    void artifact_input_tokens_include_worker_identity(@TempDir Path tempDir) {
        // Packaging / plugin-worker keys must change when the worker content token changes.
        String withWorkerA = ActionKey.forArtifact("package-jar", "0.1.0", List.of("worker-sha:aaa", "classes:bbb"));
        String withWorkerB = ActionKey.forArtifact("package-jar", "0.1.0", List.of("worker-sha:ccc", "classes:bbb"));
        assertThat(withWorkerA).isNotEqualTo(withWorkerB);
    }
}
