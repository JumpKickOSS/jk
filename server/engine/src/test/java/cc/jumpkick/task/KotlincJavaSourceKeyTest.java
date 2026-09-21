// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.KotlincRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A mixed module's Java sources are kotlinc inputs: it reads their declarations through {@code
 * -Xjava-source-roots}. The Kotlin action key therefore hashes each one's declaration digest — a
 * Java signature edit misses, a Java body-only edit hits — and the record's inputs name the file
 * whose declarations moved.
 */
class KotlincJavaSourceKeyTest {

    private static final String UTIL = """
            package com.example;

            public final class Util {
                private Util() {}

                public static int twice(int n) {
                    return n * 2;
                }
            }
            """;

    @Test
    void a_java_signature_edit_moves_the_kotlin_key_and_a_body_edit_does_not(@TempDir Path dir) throws IOException {
        Path javaRoot = Files.createDirectories(dir.resolve("src"));
        Path util = Files.writeString(javaRoot.resolve("Util.java"), UTIL);
        Path kt = Files.writeString(
                javaRoot.resolve("App.kt"), "package com.example\nobject App { fun six() = Util.twice(3) }");
        KotlincRequest request = request(dir, kt, javaRoot);

        String initial = ActionKey.forKotlinc("compile-kotlin", request, "0.1.0", KotlinClasspathAbi.MEMOIZED_ONLY);

        Files.writeString(util, UTIL.replace("return n * 2;", "return n + n;"));
        assertThat(ActionKey.forKotlinc("compile-kotlin", request, "0.1.0", KotlinClasspathAbi.MEMOIZED_ONLY))
                .as("a body-only Java edit leaves what kotlinc reads unchanged")
                .isEqualTo(initial);

        Files.writeString(util, UTIL.replace("public static int twice", "public static long twice"));
        assertThat(ActionKey.forKotlinc("compile-kotlin", request, "0.1.0", KotlinClasspathAbi.MEMOIZED_ONLY))
                .as("a Java signature edit is a kotlinc input")
                .isNotEqualTo(initial);

        // A Java file added under the roots is read too.
        Files.writeString(util, UTIL);
        Files.writeString(javaRoot.resolve("More.java"), "package com.example; public final class More {}");
        assertThat(ActionKey.forKotlinc("compile-kotlin", request, "0.1.0", KotlinClasspathAbi.MEMOIZED_ONLY))
                .isNotEqualTo(initial);
    }

    @Test
    void a_kotlin_only_request_hashes_no_java_lines(@TempDir Path dir) throws IOException {
        Path kt = Files.writeString(dir.resolve("App.kt"), "package com.example\nobject App");
        KotlincRequest request = request(dir, kt, null);
        assertThat(ActionKey.kotlincJavaSourceTokens(request)).isEmpty();
        assertThat(ActionKey.kotlincInputs(request, KotlinClasspathAbi.MEMOIZED_ONLY)
                        .keySet())
                .noneMatch(k -> k.startsWith("java-api:"));
    }

    @Test
    void the_record_inputs_name_each_java_source_by_its_declaration_digest(@TempDir Path dir) throws IOException {
        Path javaRoot = Files.createDirectories(dir.resolve("src"));
        Path util = Files.writeString(javaRoot.resolve("Util.java"), UTIL);
        Path kt = Files.writeString(javaRoot.resolve("App.kt"), "package com.example\nobject App");
        KotlincRequest request = request(dir, kt, javaRoot);

        Map<String, String> inputs = ActionKey.kotlincInputs(request, KotlinClasspathAbi.MEMOIZED_ONLY);
        String key = "java-api:" + PortablePath.of(util);
        assertThat(inputs).containsEntry(key, JavaSourceApi.digest(util));
        assertThat(ActionKey.kotlincJavaSourceTokens(request))
                .containsExactly("java-api:" + PortablePath.of(util) + ":" + JavaSourceApi.digest(util));

        Files.writeString(util, UTIL.replace("public static int twice", "public static long twice"));
        assertThat(ActionKey.kotlincInputs(request, KotlinClasspathAbi.MEMOIZED_ONLY))
                .as("why-rebuilt diffs the file whose declarations moved")
                .doesNotContainEntry(key, inputs.get(key));
    }

    /**
     * The incremental state cannot see a Java declaration edit, so the compile starts it over
     * exactly when the recorded {@code java-api:} inputs differ from the current ones.
     */
    @Test
    void the_incremental_state_restarts_when_java_declarations_moved(@TempDir Path dir) throws IOException {
        Path javaRoot = Files.createDirectories(dir.resolve("src"));
        Path util = Files.writeString(javaRoot.resolve("Util.java"), UTIL);
        Path kt = Files.writeString(javaRoot.resolve("App.kt"), "package com.example\nobject App");
        KotlincRequest request = request(dir, kt, javaRoot);
        Map<String, String> recorded = ActionKey.kotlincInputs(request, KotlinClasspathAbi.MEMOIZED_ONLY);

        assertThat(LangCompile.javaDeclarationsMoved(recorded, request)).isFalse();
        Files.writeString(util, UTIL.replace("return n * 2;", "return n + n;"));
        assertThat(LangCompile.javaDeclarationsMoved(recorded, request))
                .as("a body-only edit leaves the state valid")
                .isFalse();
        Files.writeString(util, UTIL.replace("public static int twice", "public static long twice"));
        assertThat(LangCompile.javaDeclarationsMoved(recorded, request)).isTrue();
        Files.writeString(util, UTIL);
        Files.writeString(javaRoot.resolve("More.java"), "package com.example; public final class More {}");
        assertThat(LangCompile.javaDeclarationsMoved(recorded, request))
                .as("a new Java file is a new declaration")
                .isTrue();
        assertThat(LangCompile.javaDeclarationsMoved(Map.of(), request(dir, kt, null)))
                .as("a Kotlin-only module has no Java declarations to move")
                .isFalse();
    }

    /**
     * The compile that vouches for a checkout's state is the one the state's own ledger names. The
     * task pointer is shared by every checkout of the project, so the record it names can carry
     * this checkout's current Java declarations while the state here was linked against older
     * ones; reading it would keep a state that must start over.
     */
    @Test
    void the_state_is_vouched_for_by_its_own_compile_not_the_shared_pointer(@TempDir Path dir) throws IOException {
        Path javaRoot = Files.createDirectories(dir.resolve("src"));
        Path util = Files.writeString(javaRoot.resolve("Util.java"), UTIL);
        Path kt = Files.writeString(javaRoot.resolve("App.kt"), "package com.example\nobject App");
        KotlincRequest request = request(dir, kt, javaRoot);
        ActionCache cache = new ActionCache(new Cas(dir.resolve("cas")), dir.resolve("actions"));
        Path state = Files.createDirectories(dir.resolve("state"));
        String task = "compile-kotlin@t";
        Map<String, String> outputs = Map.of("com/example/App.class", "aa");

        // This checkout's compile read Util as declared above and left the state linked against it.
        Map<String, String> linked = ActionKey.kotlincInputs(request, KotlinClasspathAbi.MEMOIZED_ONLY);
        cache.storeWithOutputs(task, "own", linked, outputs);
        LangCompile.recordTree(state, "own", outputs);

        // Another checkout compiled the new signature and flipped the shared pointer to its record.
        Files.writeString(util, UTIL.replace("public static int twice", "public static long twice"));
        Map<String, String> current = ActionKey.kotlincInputs(request, KotlinClasspathAbi.MEMOIZED_ONLY);
        cache.storeWithOutputs(task, "foreign", current, outputs);
        assertThat(LangCompile.javaDeclarationsMoved(
                        cache.lastFor(task).orElseThrow().inputs(), request))
                .as("the pointer's record agrees with the sources on disk")
                .isFalse();

        assertThat(LangCompile.javaDeclarationsMoved(cache, state, request))
                .as("the state's own compile read another declaration: start over")
                .isTrue();
        LangCompile.recordTree(state, "foreign", outputs);
        assertThat(LangCompile.javaDeclarationsMoved(cache, state, request))
                .as("a state whose compile read the current declarations stays")
                .isFalse();
        LangCompile.recordTree(state, "pruned", outputs);
        assertThat(LangCompile.javaDeclarationsMoved(cache, state, request))
                .as("a ledger naming a record that is gone vouches for nothing")
                .isTrue();
        Files.delete(state.resolve(LangCompile.TREE_LEDGER));
        assertThat(LangCompile.javaDeclarationsMoved(cache, state, request))
                .as("a state with no ledger vouches for nothing")
                .isTrue();
        assertThat(LangCompile.javaDeclarationsMoved(cache, state, request(dir, kt, null)))
                .as("a Kotlin-only module has no Java declarations to move")
                .isFalse();
    }

    private static KotlincRequest request(Path dir, Path kt, @Nullable Path javaRoot) throws IOException {
        Path worker = Files.writeString(dir.resolve("worker.jar"), "worker");
        KotlincRequest.KotlincRequestBuilder b = KotlincRequest.builder()
                .sources(List.of(kt))
                .outputDir(dir.resolve("out"))
                .jvmTarget(21)
                .workerClasspath(List.of(worker))
                .javaHome(Path.of(System.getProperty("java.home")));
        if (javaRoot != null) {
            b.javaSourceRoots(List.of(javaRoot)).extraArgs(List.of("-Xjava-source-roots=" + javaRoot));
        }
        return b.build();
    }
}
