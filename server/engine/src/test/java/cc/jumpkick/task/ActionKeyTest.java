// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.BuildIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionKeyTest {

    /** Two checkouts of the same module at different paths: identical inputs, identical key. */
    @Test
    void the_key_does_not_depend_on_where_the_module_is_checked_out(@TempDir Path tempDir) throws IOException {
        Path a = module(tempDir.resolve("checkout-a/app"));
        Path b = module(tempDir.resolve("elsewhere/deeper/app"));
        String keyA = ActionKey.forJavac("compile-main", requestFor(a), "0.1.0");
        String keyB = ActionKey.forJavac("compile-main", requestFor(b), "0.1.0");
        assertThat(keyA).isEqualTo(keyB);

        Files.writeString(b.resolve("src/main/java/Hello.java"), "class Hello { void changed() {} }");
        assertThat(ActionKey.forJavac("compile-main", requestFor(b), "0.1.0"))
                .as("content still moves the key")
                .isNotEqualTo(keyA);
    }

    private static Path module(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("jk.toml"), "name = \"app\"\n");
        Files.writeString(root.resolve("src/main/java/Hello.java"), "class Hello {}");
        return root;
    }

    private static CompileRequest requestFor(Path module) {
        return CompileRequest.builder()
                .sources(List.of(module.resolve("src/main/java/Hello.java")))
                .outputDir(module.resolve("target/classes"))
                .release(25)
                .build();
    }

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
        SessionContext.runWhere(Session.defaults().withCacheDir(cache), () -> {
            try {
                FileHashMemo.reset();
                FileHashMemo.resetStats();
                String key = ActionKey.forJavac("compile-main", request, "0.1.0");
                var snap = ActionKey.snapshotInputs(request);
                assertThat(key).isNotBlank();
                assertThat(snap).containsKey(src.toAbsolutePath().normalize().toString());
                assertThat(FileHashMemo.contentReads())
                        .as("forJavac + snapshotInputs share one content read")
                        .isEqualTo(1);
                assertThat(FileHashMemo.memoHits()).isGreaterThanOrEqualTo(1);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Compile avoidance: the compile classpath is keyed by JVM ABI, so a dependency whose method
     * bodies changed keys the same compile, while a dependency whose API changed does not.
     */
    @Test
    void a_body_only_classpath_change_keeps_the_javac_key_and_an_api_change_moves_it(@TempDir Path tempDir)
            throws IOException {
        Path src = Files.writeString(tempDir.resolve("Hello.java"), "class Hello {}");
        Path dep = AbiJars.jar(tempDir.resolve("dep.jar"), AbiJars.classReturning(1));
        String before = ActionKey.forJavac("compile-main", withClasspath(src, tempDir, dep), "0.1.0");

        AbiJars.jar(dep, AbiJars.classReturning(2));
        assertThat(ActionKey.forJavac("compile-main", withClasspath(src, tempDir, dep), "0.1.0"))
                .as("a body-only change of a classpath jar is not a compile input")
                .isEqualTo(before);

        AbiJars.jar(dep, AbiJars.classWithMethods("n", "added"));
        String apiChanged = ActionKey.forJavac("compile-main", withClasspath(src, tempDir, dep), "0.1.0");
        assertThat(apiChanged).as("a new public method is").isNotEqualTo(before);

        AbiJars.jar(dep, AbiJars.classWithIntConst(1));
        String constOne = ActionKey.forJavac("compile-main", withClasspath(src, tempDir, dep), "0.1.0");
        AbiJars.jar(dep, AbiJars.classWithIntConst(2));
        assertThat(ActionKey.forJavac("compile-main", withClasspath(src, tempDir, dep), "0.1.0"))
                .as("an inlined constant is API: javac copies its value into the consumer")
                .isNotEqualTo(constOne);
    }

    /** The processor path stays full content: a processor's behaviour is its bodies. */
    @Test
    void a_body_only_processor_change_moves_the_javac_key(@TempDir Path tempDir) throws IOException {
        Path src = Files.writeString(tempDir.resolve("Hello.java"), "class Hello {}");
        Path processor = AbiJars.jar(tempDir.resolve("processor.jar"), AbiJars.classReturning(1));
        String before = ActionKey.forJavac("compile-main", withProcessor(src, tempDir, processor), "0.1.0");

        AbiJars.jar(processor, AbiJars.classReturning(2));
        assertThat(ActionKey.forJavac("compile-main", withProcessor(src, tempDir, processor), "0.1.0"))
                .isNotEqualTo(before);
    }

    /** The token lines are the key's classpath section, and the snapshot names each entry by them. */
    @Test
    void the_token_lines_spell_each_entry_by_abi_or_content_and_the_snapshot_carries_them(@TempDir Path tempDir)
            throws IOException {
        Path src = Files.writeString(tempDir.resolve("Hello.java"), "class Hello {}");
        Path dep = AbiJars.jar(tempDir.resolve("dep.jar"), AbiJars.classReturning(1));
        Path processor = AbiJars.jar(tempDir.resolve("processor.jar"), AbiJars.classReturning(1));
        CompileRequest request = CompileRequest.builder()
                .sources(List.of(src))
                .classpath(List.of(dep))
                .processorPath(List.of(processor))
                .outputDir(tempDir.resolve("out"))
                .release(25)
                .build();

        List<String> lines = ActionKey.javacClasspathTokens(request);
        assertThat(lines)
                .containsExactly("cp:" + ClasspathAbi.token(dep), "pp:" + ClasspathFingerprint.entry(processor));
        assertThat(lines.get(0)).startsWith("cp:abi:");
        assertThat(lines.get(1)).startsWith("pp:file:");

        var snapshot = ActionKey.snapshotInputs(request);
        assertThat(snapshot)
                .containsEntry("cp:" + dep.toAbsolutePath().normalize(), ClasspathAbi.token(dep))
                .containsEntry("pp:" + processor.toAbsolutePath().normalize(), ClasspathFingerprint.entry(processor));
    }

    private static CompileRequest withClasspath(Path src, Path tempDir, Path dep) {
        return CompileRequest.builder()
                .sources(List.of(src))
                .classpath(List.of(dep))
                .outputDir(tempDir.resolve("out"))
                .release(25)
                .build();
    }

    private static CompileRequest withProcessor(Path src, Path tempDir, Path processor) {
        return CompileRequest.builder()
                .sources(List.of(src))
                .processorPath(List.of(processor))
                .outputDir(tempDir.resolve("out"))
                .release(25)
                .build();
    }

    /** The engine that packages an artifact is one of its inputs: another engine, another key. */
    @Test
    void an_artifact_key_moves_with_the_producing_engines_identity() {
        List<String> tokens = List.of("classes:dir:abc", "manifest:Main");
        String derived = ActionKey.forArtifact("package-jar@1234", "0.1.0#3", tokens);
        try {
            BuildIdentity.overrideBuildIdForTests("000000000001");
            String first = ActionKey.forArtifact("package-jar@1234", "0.1.0#3", tokens);
            assertThat(first).isNotEqualTo(derived);
            assertThat(ActionKey.forArtifact("package-jar@1234", "0.1.0#3", tokens))
                    .as("the same engine keys the same artifact")
                    .isEqualTo(first);
            BuildIdentity.overrideBuildIdForTests("000000000002");
            assertThat(ActionKey.forArtifact("package-jar@1234", "0.1.0#3", tokens))
                    .as("a rebuilt engine under the same version is a different producer")
                    .isNotEqualTo(first);
        } finally {
            BuildIdentity.overrideBuildIdForTests(null);
        }
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
    void the_javac_key_carries_the_pinned_source_encoding(@TempDir Path tempDir) throws IOException {
        // Both javac front ends pin UTF-8, so this token can never separate two of today's keys.
        // It is in the preimage so that the day the pin moves, every artifact decoded under the old
        // charset stops being a cache hit. A source- and classpath-free request keeps the preimage
        // free of temp-dir paths, so the thing being hashed can be spelled out in full — which also
        // pins the `jdk:` token added, and the fact that a JDK-less request spells `none`
        // rather than dropping the line (a dropped line is a preimage two requests can share).
        CompileRequest request = CompileRequest.builder()
                .outputDir(tempDir.resolve("out"))
                .release(25)
                .build();

        assertThat(ActionKey.forJavac("compile-main", request, "0.1.0"))
                .isEqualTo(Hashing.sha256Hex(
                        "task:compile-main\njk:0.1.0\nrelease:25\nencoding:UTF-8\njdk:none\noptions:\n"));
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

        var base = new KotlincRequest(
                List.of(src),
                List.of(),
                tempDir.resolve("out"),
                17,
                List.of(worker),
                tempDir.resolve("jdk"),
                null,
                null,
                List.of(),
                List.of(new KotlincRequest.Plugin("all-open", pluginV1, List.of())),
                null);
        var upgraded = new KotlincRequest(
                List.of(src),
                List.of(),
                tempDir.resolve("out"),
                17,
                List.of(worker),
                tempDir.resolve("jdk"),
                null,
                null,
                List.of(),
                List.of(new KotlincRequest.Plugin("all-open", pluginV2, List.of())),
                null);

        assertThat(ActionKey.forKotlinc("compile-main", base, "0.1.0"))
                .isNotEqualTo(ActionKey.forKotlinc("compile-main", upgraded, "0.1.0"));
    }

    @Test
    void kotlin_jdk_home_is_part_of_action_key(@TempDir Path tempDir) throws IOException {
        // jk.toml moves `jdk = 17` to `jdk = 21` and leaves jvmTarget alone. kotlinc resolves the
        // platform classes it links against from -jdk-home, so the key MUST move — otherwise the
        // build restores bytecode compiled against the 17 platform.
        Path src = tempDir.resolve("Main.kt");
        Files.writeString(src, "fun main() {}");
        Path worker = tempDir.resolve("worker.jar");
        Files.writeString(worker, "worker");

        Path jdk17 = jdk(tempDir.resolve("temurin-17"), "17.0.12+7");
        Path jdk21 = jdk(tempDir.resolve("temurin-21"), "21.0.5+11");

        assertThat(ActionKey.forKotlinc("compile-kotlin", kotlin(src, worker, tempDir, jdk17), "0.1.0"))
                .isNotEqualTo(ActionKey.forKotlinc("compile-kotlin", kotlin(src, worker, tempDir, jdk21), "0.1.0"));
    }

    @Test
    void kotlin_jdk_identity_is_content_not_path(@TempDir Path tempDir) throws IOException {
        // A point release upgraded in place — same JAVA_HOME, different JDK. Keying the path
        // alone would restore bytecode linked against the superseded platform classes.
        Path src = tempDir.resolve("Main.kt");
        Files.writeString(src, "fun main() {}");
        Path worker = tempDir.resolve("worker.jar");
        Files.writeString(worker, "worker");
        Path jdk = jdk(tempDir.resolve("temurin-21"), "21.0.5+11");

        String before = ActionKey.forKotlinc("compile-kotlin", kotlin(src, worker, tempDir, jdk), "0.1.0");
        jdk(jdk, "21.0.6+11"); // same length: the token is the content, not the file size
        String after = ActionKey.forKotlinc("compile-kotlin", kotlin(src, worker, tempDir, jdk), "0.1.0");

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void javac_jdk_home_is_part_of_action_key(@TempDir Path tempDir) throws IOException {
        // The same defect forKotlinc had, one lane over: jk.toml moves `jdk = 17` to
        // `jdk = 21` and leaves `java = 17` alone, so --release does not move. ForkedJavac launches
        // javac out of this very home, so the compiler AND the platform classes change under a key
        // that never did — the build restores 17-compiled bytecode and calls it up to date.
        Path src = tempDir.resolve("Hello.java");
        Files.writeString(src, "class Hello {}");
        Path jdk17 = jdk(tempDir.resolve("temurin-17"), "17.0.12+7");
        Path jdk21 = jdk(tempDir.resolve("temurin-21"), "21.0.5+11");

        assertThat(ActionKey.forJavac("compile-main", javac(src, tempDir, jdk17), "0.1.0"))
                .isNotEqualTo(ActionKey.forJavac("compile-main", javac(src, tempDir, jdk21), "0.1.0"));
        // …and a request that names no JDK is its own value, not whichever home happened to be
        // resolved last: `none` is a token no real home can produce.
        assertThat(ActionKey.forJavac("compile-main", javac(src, tempDir, null), "0.1.0"))
                .isNotEqualTo(ActionKey.forJavac("compile-main", javac(src, tempDir, jdk17), "0.1.0"));
        assertThat(ActionKey.jdkToken(null)).isEqualTo("none");
    }

    @Test
    void javac_options_digest_moves_with_options_and_jdk_but_not_with_sources(@TempDir Path tempDir)
            throws IOException {
        // The freshness stamp records this digest: it has to move on every option-bearing input
        // the action key hashes, and on nothing the stamp already compares by mtime.
        Path src = tempDir.resolve("Hello.java");
        Files.writeString(src, "class Hello {}");
        Path jdk17 = jdk(tempDir.resolve("temurin-17"), "17.0.12+7");
        Path jdk21 = jdk(tempDir.resolve("temurin-21"), "21.0.5+11");
        String digest = ActionKey.javacOptionsDigest(optioned(src, tempDir, jdk17, 17, List.of(), List.of()));

        assertThat(ActionKey.javacOptionsDigest(optioned(src, tempDir, jdk17, 17, List.of(), List.of())))
                .isEqualTo(digest);
        assertThat(ActionKey.javacOptionsDigest(optioned(src, tempDir, jdk21, 17, List.of(), List.of())))
                .isNotEqualTo(digest);
        assertThat(ActionKey.javacOptionsDigest(optioned(src, tempDir, jdk17, 17, List.of("-parameters"), List.of())))
                .isNotEqualTo(digest);
        assertThat(ActionKey.javacOptionsDigest(optioned(src, tempDir, jdk17, 21, List.of(), List.of())))
                .isNotEqualTo(digest);
        // Sources and classpath are the stamp's own business — editing one leaves the digest alone.
        Files.writeString(src, "class Hello { void edited() {} }");
        assertThat(ActionKey.javacOptionsDigest(
                        optioned(src, tempDir, jdk17, 17, List.of(), List.of(tempDir.resolve("dep.jar")))))
                .isEqualTo(digest);
    }

    @Test
    void option_pairing_is_part_of_the_key(@TempDir Path tempDir) throws IOException {
        // The two argv hold the same words: a flag pairs with the value after it, so they are
        // different compiles and must not share a key.
        Path src = tempDir.resolve("Hello.java");
        Files.writeString(src, "class Hello {}");
        Path jdk = jdk(tempDir.resolve("temurin-21"), "21.0.5+11");
        CompileRequest ab =
                optioned(src, tempDir, jdk, 21, List.of("--add-modules", "a", "--limit-modules", "b"), List.of());
        CompileRequest ba =
                optioned(src, tempDir, jdk, 21, List.of("--add-modules", "b", "--limit-modules", "a"), List.of());

        assertThat(ActionKey.forJavac("compile-main", ab, "0.1.0"))
                .isNotEqualTo(ActionKey.forJavac("compile-main", ba, "0.1.0"));
        assertThat(ActionKey.javacOptionsDigest(ab)).isNotEqualTo(ActionKey.javacOptionsDigest(ba));
    }

    private static CompileRequest optioned(
            Path src, Path tempDir, Path javaHome, int release, List<String> options, List<Path> classpath) {
        return CompileRequest.builder()
                .sources(List.of(src))
                .classpath(classpath)
                .outputDir(tempDir.resolve("out"))
                .release(release)
                .extraOptions(options)
                .javaHome(javaHome)
                .build();
    }

    @Test
    void javac_jdk_identity_is_content_not_path(@TempDir Path tempDir) throws IOException {
        // A point release upgraded in place — same JAVA_HOME, different javac. One renderer
        // (ActionKey.jdkToken) serves forJavac, forKotlinc and both PlannerPlugin arms, so this
        // property holds for all four or none.
        Path src = tempDir.resolve("Hello.java");
        Files.writeString(src, "class Hello {}");
        Path jdk = jdk(tempDir.resolve("temurin-21"), "21.0.5+11");

        String before = ActionKey.forJavac("compile-main", javac(src, tempDir, jdk), "0.1.0");
        jdk(jdk, "21.0.6+11"); // same length: the token is the content, not the file size
        String after = ActionKey.forJavac("compile-main", javac(src, tempDir, jdk), "0.1.0");

        assertThat(after).isNotEqualTo(before);
        // why-rebuilt must be able to name the reason the key moved, or a JDK switch reads as
        // "nothing changed, rebuilt anyway".
        assertThat(ActionKey.snapshotInputs(javac(src, tempDir, jdk))).containsEntry("jdk", ActionKey.jdkToken(jdk));
    }

    @Test
    void artifact_input_tokens_include_worker_identity(@TempDir Path tempDir) {
        // Packaging / plugin-worker keys must change when the worker content token changes.
        String withWorkerA = ActionKey.forArtifact("package-jar", "0.1.0", List.of("worker-sha:aaa", "classes:bbb"));
        String withWorkerB = ActionKey.forArtifact("package-jar", "0.1.0", List.of("worker-sha:ccc", "classes:bbb"));
        assertThat(withWorkerA).isNotEqualTo(withWorkerB);
    }

    /** A JDK install skeleton: just the {@code release} file the key reads. */
    private static Path jdk(Path home, String version) throws IOException {
        Files.createDirectories(home);
        Files.writeString(
                home.resolve("release"),
                "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\nOS_ARCH=\"x86_64\"\n");
        return home;
    }

    private static CompileRequest javac(Path src, Path tempDir, @Nullable Path javaHome) {
        return CompileRequest.builder()
                .sources(List.of(src))
                .outputDir(tempDir.resolve("out"))
                .release(17) // held fixed on purpose: only the JDK moves
                .javaHome(javaHome)
                .build();
    }

    private static KotlincRequest kotlin(Path src, Path worker, Path tempDir, Path javaHome) {
        return KotlincRequest.builder()
                .sources(List.of(src))
                .outputDir(tempDir.resolve("out"))
                .jvmTarget(17) // held fixed on purpose: only the JDK moves
                .workerClasspath(List.of(worker))
                .javaHome(javaHome)
                .build();
    }
}
