// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectIdentity;
import cc.jumpkick.builds.ProjectIds;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionKeyTest {

    /**
     * Two checkouts of the same project at different paths: identical inputs, identical key and
     * identical task pointer, through the production task id. The project id is the committed
     * lock's, so the tag names the output within the project and never the checkout.
     */
    @Test
    void the_key_does_not_depend_on_where_the_module_is_checked_out(@TempDir Path tempDir) throws IOException {
        Path a = module(tempDir.resolve("checkout-a/app"));
        Path b = module(tempDir.resolve("elsewhere/deeper/app"));
        String taskA = ActionKey.qualifiedTaskId(TaskNames.COMPILE_MAIN, a.resolve("target/classes"));
        String taskB = ActionKey.qualifiedTaskId(TaskNames.COMPILE_MAIN, b.resolve("target/classes"));
        assertThat(taskA).as("the task pointer is shared across checkouts").isEqualTo(taskB);
        String keyA = ActionKey.forJavac(taskA, requestFor(a), "0.1.0");
        String keyB = ActionKey.forJavac(taskB, requestFor(b), "0.1.0");
        assertThat(keyA).isEqualTo(keyB);

        Files.writeString(b.resolve("src/main/java/Hello.java"), "class Hello { void changed() {} }");
        assertThat(ActionKey.forJavac(taskB, requestFor(b), "0.1.0"))
                .as("content still moves the key")
                .isNotEqualTo(keyA);
    }

    /**
     * Every key family, from two checkouts of one project: kotlinc, groovyc, an artifact bag and
     * the run-tests stamp with its compile-test keys folded in. A factory that hashed a location
     * would separate the two.
     */
    @Test
    void every_key_family_is_equal_across_two_checkouts_of_one_project(@TempDir Path tempDir) throws IOException {
        Path a = module(tempDir.resolve("one/app"));
        Path b = module(tempDir.resolve("two/nested/app"));
        for (Path m : List.of(a, b)) {
            Files.writeString(m.resolve("src/main/java/App.kt"), "object App");
            Files.writeString(m.resolve("src/main/java/Script.groovy"), "class Script {}");
            Files.writeString(m.resolve("worker.jar"), "worker");
            Files.createDirectories(m.resolve("src/test/java"));
            Files.writeString(m.resolve("src/test/java/AppTest.java"), "class AppTest {}");
            Files.createDirectories(m.resolve("target/classes/main"));
            Files.writeString(m.resolve("target/classes/main/Hello.class"), "bytes");
        }
        Path javaHome = Path.of(System.getProperty("java.home"));

        String ktA = ActionKey.forKotlinc(
                ActionKey.qualifiedTaskId(TaskNames.COMPILE_KOTLIN, a.resolve("target/classes/main")),
                kotlinc(a, javaHome),
                "0.1.0",
                KotlinClasspathAbi.MEMOIZED_ONLY);
        String ktB = ActionKey.forKotlinc(
                ActionKey.qualifiedTaskId(TaskNames.COMPILE_KOTLIN, b.resolve("target/classes/main")),
                kotlinc(b, javaHome),
                "0.1.0",
                KotlinClasspathAbi.MEMOIZED_ONLY);
        assertThat(ktA).as("kotlinc").isEqualTo(ktB);

        String gvA = ActionKey.forGroovyc(
                ActionKey.qualifiedTaskId(TaskNames.COMPILE_GROOVY, a.resolve("target/classes/main")),
                groovyc(a),
                "0.1.0");
        String gvB = ActionKey.forGroovyc(
                ActionKey.qualifiedTaskId(TaskNames.COMPILE_GROOVY, b.resolve("target/classes/main")),
                groovyc(b),
                "0.1.0");
        assertThat(gvA).as("groovyc").isEqualTo(gvB);

        List<String> tokens = List.of("classes:dir:abc", "main:App", "manifest:");
        String jarA = ActionKey.forArtifact(
                ActionKey.qualifiedTaskId(TaskNames.PACKAGE_JAR, a.resolve("target/app-1.0.jar")), "0.1.0", tokens);
        String jarB = ActionKey.forArtifact(
                ActionKey.qualifiedTaskId(TaskNames.PACKAGE_JAR, b.resolve("target/app-1.0.jar")), "0.1.0", tokens);
        assertThat(jarA).as("artifact").isEqualTo(jarB);

        String javacA = ActionKey.forJavac(
                ActionKey.qualifiedTaskId(TaskNames.COMPILE_TEST, a.resolve("target/classes/test")),
                requestFor(a),
                "0.1.0");
        String javacB = ActionKey.forJavac(
                ActionKey.qualifiedTaskId(TaskNames.COMPILE_TEST, b.resolve("target/classes/test")),
                requestFor(b),
                "0.1.0");
        List<String> extrasA =
                TestStamp.withCompileTest(List.of("jk:0.1.0"), new TestStamp.CompileTestKeys(javacA, null, null));
        List<String> extrasB =
                TestStamp.withCompileTest(List.of("jk:0.1.0"), new TestStamp.CompileTestKeys(javacB, null, null));
        String stampA = TestStamp.computeKey(
                List.of(a.resolve("src/test/java/AppTest.java")),
                a.resolve("target/classes/main"),
                List.of(),
                a.resolve("jk-lock.toml"),
                List.of(a.resolve("target/classes/main")),
                extrasA);
        String stampB = TestStamp.computeKey(
                List.of(b.resolve("src/test/java/AppTest.java")),
                b.resolve("target/classes/main"),
                List.of(),
                b.resolve("jk-lock.toml"),
                List.of(b.resolve("target/classes/main")),
                extrasB);
        assertThat(stampA).as("run-tests stamp").isNotNull().isEqualTo(stampB);
    }

    private static KotlincRequest kotlinc(Path module, Path javaHome) {
        return KotlincRequest.builder()
                .sources(List.of(module.resolve("src/main/java/App.kt")))
                .outputDir(module.resolve("target/classes/main"))
                .jvmTarget(21)
                .workerClasspath(List.of(module.resolve("worker.jar")))
                .javaHome(javaHome)
                .build();
    }

    private static GroovycRequest groovyc(Path module) {
        return GroovycRequest.builder()
                .sources(List.of(module.resolve("src/main/java/Script.groovy")))
                .classpath(List.of())
                .outputDir(module.resolve("target/classes/main"))
                .stubsOut(module.resolve("target/stubs"))
                .jvmTarget(21)
                .workerClasspath(List.of(module.resolve("worker.jar")))
                .build();
    }

    /**
     * Compiler argv that names absolute paths — javac's Groovy-stub source path and JPMS patch
     * roots, kotlinc's Java source roots — keys the same from two checkouts, while the argv the
     * compiler receives keeps its absolute paths.
     */
    @Test
    void path_bearing_compiler_options_key_the_same_from_two_checkouts(@TempDir Path tempDir) throws IOException {
        Path a = module(tempDir.resolve("one/app"));
        Path b = module(tempDir.resolve("two/deeper/app"));
        String keyA = ActionKey.forJavac("compile-test", pathOptions(a), "0.1.0");
        String keyB = ActionKey.forJavac("compile-test", pathOptions(b), "0.1.0");
        assertThat(keyA).isEqualTo(keyB);
        assertThat(ActionKey.javacOptionsDigest(pathOptions(a)))
                .isEqualTo(ActionKey.javacOptionsDigest(pathOptions(b)));
        assertThat(ActionKey.snapshotInputs(pathOptions(a)).get("options"))
                .isEqualTo(ActionKey.snapshotInputs(pathOptions(b)).get("options"))
                .contains("--source-path,target/groovy-stubs")
                .contains("--patch-module,app=test/src")
                .doesNotContain(tempDir.toString());
        assertThat(ActionKey.forJavac("compile-test", pathOptions(a, "--source-path", "other"), "0.1.0"))
                .as("a different option still moves the key")
                .isNotEqualTo(keyA);

        Path javaHome = Path.of(System.getProperty("java.home"));
        Files.writeString(a.resolve("worker.jar"), "worker");
        Files.writeString(b.resolve("worker.jar"), "worker");
        Files.writeString(a.resolve("src/main/java/App.kt"), "object App");
        Files.writeString(b.resolve("src/main/java/App.kt"), "object App");
        KotlincRequest ktA = KotlincRequest.builder()
                .sources(List.of(a.resolve("src/main/java/App.kt")))
                .outputDir(a.resolve("target/classes/main"))
                .jvmTarget(21)
                .workerClasspath(List.of(a.resolve("worker.jar")))
                .javaHome(javaHome)
                .javaSourceRoots(List.of(a.resolve("src/main/java")))
                .extraArgs(List.of(
                        "-Xjava-source-roots=" + a.resolve("src/main/java").toAbsolutePath()))
                .build();
        KotlincRequest ktB = KotlincRequest.builder()
                .sources(List.of(b.resolve("src/main/java/App.kt")))
                .outputDir(b.resolve("target/classes/main"))
                .jvmTarget(21)
                .workerClasspath(List.of(b.resolve("worker.jar")))
                .javaHome(javaHome)
                .javaSourceRoots(List.of(b.resolve("src/main/java")))
                .extraArgs(List.of(
                        "-Xjava-source-roots=" + b.resolve("src/main/java").toAbsolutePath()))
                .build();
        assertThat(ActionKey.forKotlinc("compile-kotlin", ktA, "0.1.0", KotlinClasspathAbi.MEMOIZED_ONLY))
                .isEqualTo(ActionKey.forKotlinc("compile-kotlin", ktB, "0.1.0", KotlinClasspathAbi.MEMOIZED_ONLY));
        assertThat(ActionKey.kotlincInputs(ktA, KotlinClasspathAbi.MEMOIZED_ONLY)
                        .get("args"))
                .isEqualTo("-Xjava-source-roots=src/main/java");
    }

    /** Tokens that are not paths pass through; paths inside lists are spelled portably. */
    @Test
    void portable_argument_rewrites_only_absolute_paths(@TempDir Path tempDir) throws IOException {
        Path m = module(tempDir.resolve("m"));
        String root = m.resolve("src").toAbsolutePath().toString();
        assertThat(ActionKey.portableArgument("-parameters")).isEqualTo("-parameters");
        assertThat(ActionKey.portableArgument("-Xplugin:Lombok")).isEqualTo("-Xplugin:Lombok");
        assertThat(ActionKey.portableArgument("app=" + root)).isEqualTo("app=src");
        assertThat(ActionKey.portableArgument(root + "," + root)).isEqualTo("src,src");
        assertThat(ActionKey.portableArgument("-H:ConfigurationFileDirectories=" + root))
                .isEqualTo("-H:ConfigurationFileDirectories=src");
    }

    private static CompileRequest pathOptions(Path module) {
        return pathOptions(
                module,
                "--source-path",
                module.resolve("target/groovy-stubs").toAbsolutePath().toString(),
                "--patch-module",
                "app=" + module.resolve("test/src").toAbsolutePath());
    }

    private static CompileRequest pathOptions(Path module, String... options) {
        return CompileRequest.builder()
                .sources(List.of(module.resolve("src/main/java/Hello.java")))
                .outputDir(module.resolve("target/classes/test"))
                .release(25)
                .extraOptions(List.of(options))
                .build();
    }

    /** The incremental compiler state, whose analysis holds absolute paths, stays per checkout. */
    @Test
    void the_state_dir_names_the_checkout_and_the_task_pointer_does_not(@TempDir Path tempDir) throws IOException {
        Path a = module(tempDir.resolve("checkout-a/app"));
        Path b = module(tempDir.resolve("checkout-b/app"));
        Path root = tempDir.resolve("incremental-java");
        assertThat(ActionKey.stateDir(root, TaskNames.COMPILE_MAIN, a.resolve("target/classes")))
                .isNotEqualTo(ActionKey.stateDir(root, TaskNames.COMPILE_MAIN, b.resolve("target/classes")));
        assertThat(ActionKey.stateDir(root, TaskNames.COMPILE_MAIN, a.resolve("target/classes")))
                .isEqualTo(ActionKey.stateDir(root, TaskNames.COMPILE_MAIN, a.resolve("target/classes")));
        assertThat(ActionKey.checkoutTag(a.resolve("target/classes")))
                .isNotEqualTo(ActionKey.taskTag(a.resolve("target/classes")));
    }

    /**
     * Two sources under no project whose last two segments agree: the record keeps both, and a
     * compile that compares its request against that record finds nothing moved. A key that fell
     * onto one entry for both would drop every such compile's record and its incremental state.
     */
    @Test
    void two_no_root_sources_with_the_same_tail_keep_separate_record_entries(@TempDir Path tempDir) throws IOException {
        Path user = tempDir.resolve("user/model/Event.java");
        Path order = tempDir.resolve("order/model/Event.java");
        Files.createDirectories(user.getParent());
        Files.createDirectories(order.getParent());
        Files.writeString(user, "class Event { int user; }");
        Files.writeString(order, "class Event { int order; }");
        List<Path> sources = List.of(user, order);

        CompileRequest javac = CompileRequest.builder()
                .sources(sources)
                .outputDir(tempDir.resolve("out"))
                .release(25)
                .build();
        var snapshot = ActionKey.snapshotInputs(javac);
        assertThat(snapshot).containsKey(PortablePath.key(user)).containsKey(PortablePath.key(order));
        assertThat(PortablePath.key(user)).isNotEqualTo(PortablePath.key(order));
        assertThat(ActionKey.changedSources(sources, snapshot)).isEmpty();

        Path worker = tempDir.resolve("worker.jar");
        Files.writeString(worker, "worker");
        KotlincRequest kotlinc = KotlincRequest.builder()
                .sources(sources)
                .outputDir(tempDir.resolve("out"))
                .jvmTarget(25)
                .workerClasspath(List.of(worker))
                .javaHome(jdk(tempDir.resolve("jdk"), "25"))
                .build();
        assertThat(ActionKey.changedSources(
                        sources, ActionKey.kotlincInputs(kotlinc, KotlinClasspathAbi.MEMOIZED_ONLY)))
                .isEmpty();
    }

    /**
     * A module built in place from its {@code pom.xml} has no {@code jk.toml}; its shadow manifest
     * and lock live under {@code target/}. The POM dir is still the module root, so two checkouts
     * share the task pointer and the key, and the record spells each source against the module.
     */
    @Test
    void a_pom_only_module_is_a_root_shared_across_checkouts(@TempDir Path tempDir) throws IOException {
        Path a = pomModule(tempDir.resolve("checkout-a/app"));
        Path b = pomModule(tempDir.resolve("elsewhere/deeper/app"));
        String taskA = ActionKey.qualifiedTaskId(TaskNames.COMPILE_MAIN, a.resolve("target/classes"));
        String taskB = ActionKey.qualifiedTaskId(TaskNames.COMPILE_MAIN, b.resolve("target/classes"));
        assertThat(taskA).as("the task pointer is shared across checkouts").isEqualTo(taskB);
        assertThat(ActionKey.taskTag(a.resolve("target/classes")))
                .isNotEqualTo(ActionKey.checkoutTag(a.resolve("target/classes")));
        assertThat(ActionKey.forJavac(taskA, pomRequest(a), "0.1.0"))
                .isEqualTo(ActionKey.forJavac(taskB, pomRequest(b), "0.1.0"));
        assertThat(ActionKey.snapshotInputs(pomRequest(a)))
                .containsKey("src/main/java/com/acme/user/model/Event.java")
                .containsKey("src/main/java/com/acme/order/model/Event.java");
    }

    /** A pom-only module with a lock beside its shadow: every checkout of it carries the same {@code project-id}. */
    private static Path pomModule(Path root) throws IOException {
        Files.writeString(Files.createDirectories(root).resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                </project>
                """);
        Path shadow = Files.createDirectories(root.resolve("target/jk/shadow"));
        Files.writeString(
                shadow.resolve("jk-lock.toml"), "version = 1\nproject-id = \"fedcba9876543210fedcba9876543210\"\n");
        for (String rel : List.of("user", "order")) {
            Path src = root.resolve("src/main/java/com/acme").resolve(rel).resolve("model/Event.java");
            Files.createDirectories(src.getParent());
            Files.writeString(src, "package com.acme." + rel + ".model; class Event {}");
        }
        return root;
    }

    private static CompileRequest pomRequest(Path module) {
        return CompileRequest.builder()
                .sources(List.of(
                        module.resolve("src/main/java/com/acme/user/model/Event.java"),
                        module.resolve("src/main/java/com/acme/order/model/Event.java")))
                .outputDir(module.resolve("target/classes"))
                .release(25)
                .build();
    }

    /**
     * A home with no release file keys its install directory, not the {@code Contents/Home} every
     * macOS bundle ends in, so two such JDKs on one machine do not collapse onto one token.
     */
    @Test
    void release_less_jdk_homes_key_their_install_directory(@TempDir Path tempDir) throws IOException {
        Path jdk21 = Files.createDirectories(tempDir.resolve("JavaVirtualMachines/temurin-21.jdk/Contents/Home"));
        Path jdk25 = Files.createDirectories(tempDir.resolve("JavaVirtualMachines/temurin-25.jdk/Contents/Home"));
        assertThat(ActionKey.jdkToken(jdk21)).isNotEqualTo(ActionKey.jdkToken(jdk25));
        assertThat(ActionKey.jdkToken(jdk21))
                .isEqualTo(ActionKey.jdkToken(jdk21))
                .doesNotContain(tempDir.toString());

        Path modules = Files.createDirectories(jdk21.resolve("lib")).resolve("modules");
        Files.writeString(modules, "image");
        String small = ActionKey.jdkToken(jdk21);
        Files.writeString(modules, "a larger image");
        assertThat(ActionKey.jdkToken(jdk21)).as("the runtime image counts").isNotEqualTo(small);
    }

    /** A project with a lock: every checkout of it carries the same {@code project-id}. */
    private static Path module(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("jk.toml"), "name = \"app\"\n");
        Files.writeString(
                root.resolve("jk-lock.toml"), "version = 1\nproject-id = \"0123456789abcdef0123456789abcdef\"\n");
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
                assertThat(snap).containsKey(PortablePath.key(src));
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
                .containsEntry("cp:" + PortablePath.key(dep), ClasspathAbi.token(dep))
                .containsEntry("pp:" + PortablePath.key(processor), ClasspathFingerprint.entry(processor));
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
                .isEqualTo(
                        Hashing.sha256Hex(
                                "task:compile-main\njk:0.1.0\nrelease:25\nencoding:UTF-8\njdk:none\noptions:\nmirrored:excluded\n"));
    }

    /**
     * The tag tells modules and projects apart without naming a location: two members of one
     * workspace differ by their workspace-relative output path, two projects by their id, and a
     * directory under no project falls back to the checkout tag.
     */
    @Test
    void qualified_task_id_differs_per_module_and_project_and_is_stable(@TempDir Path tempDir) throws IOException {
        Path ws = tempDir.resolve("ws");
        Files.createDirectories(ws.resolve("lib"));
        Files.createDirectories(ws.resolve("app"));
        Files.writeString(ws.resolve("jk.toml"), "name = \"ws\"\n[workspace]\nmodules = [\"lib\", \"app\"]\n");
        Files.writeString(
                ws.resolve("jk-lock.toml"), "version = 1\nproject-id = \"0123456789abcdef0123456789abcdef\"\n");
        Files.writeString(ws.resolve("lib/jk.toml"), "name = \"lib\"\n");
        Files.writeString(ws.resolve("app/jk.toml"), "name = \"app\"\n");
        Path lib = ws.resolve("target/lib/classes/main");
        Path app = ws.resolve("target/app/classes/main");

        String ql = ActionKey.qualifiedTaskId("compile-main", lib);
        assertThat(ql).startsWith("compile-main@");
        assertThat(ql).isNotEqualTo(ActionKey.qualifiedTaskId("compile-main", app));
        assertThat(ql).isEqualTo(ActionKey.qualifiedTaskId("compile-main", lib));
        assertThat(ql).isNotEqualTo(ActionKey.qualifiedTaskId("compile-test", lib));
        // The module dir itself (a guard lane's tag) resolves to the same workspace root.
        assertThat(ActionKey.qualifiedTaskId("guard", ws.resolve("lib")))
                .isNotEqualTo(ActionKey.qualifiedTaskId("guard", ws.resolve("app")));

        Path other = module(tempDir.resolve("other"));
        Files.writeString(
                other.resolve("jk-lock.toml"), "version = 1\nproject-id = \"fedcba9876543210fedcba9876543210\"\n");
        assertThat(ActionKey.qualifiedTaskId("compile-main", other.resolve("target/classes/main")))
                .as("another project's identical relative output is another pointer")
                .isNotEqualTo(ActionKey.qualifiedTaskId("compile-main", tempDir.resolve("ws/target/classes/main")));

        Path bare = tempDir.resolve("nowhere/target/classes/main");
        assertThat(ActionKey.taskTag(bare)).isEqualTo(ActionKey.checkoutTag(bare));
    }

    /**
     * A fresh project's first build tags its early steps with the lockless id, then its lock step
     * mints {@code project-id}. Every tag computed after the write must already carry the lock's id,
     * or the keys that build stores are unreachable from the next one.
     */
    @Test
    void the_task_tag_follows_the_id_the_lock_step_mints(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("fresh");
        Files.createDirectories(root);
        Files.writeString(root.resolve("jk.toml"), "name = \"fresh\"\n");
        Path classes = root.resolve("target/classes/main");
        ProjectIds.clear();
        String before = ActionKey.taskTag(classes);

        LockfileWriter.write(Lockfile.empty("test"), root.resolve("jk-lock.toml"));
        String minted = ProjectIdentity.recordedId(root.resolve("jk-lock.toml")).orElseThrow();

        assertThat(ActionKey.taskTag(classes))
                .isNotEqualTo(before)
                .isEqualTo(Hashing.sha256Hex(minted + "\ntarget/classes/main").substring(0, 12));
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
                null,
                List.of());
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
                null,
                List.of());

        assertThat(ActionKey.forKotlinc("compile-main", base, "0.1.0", KotlinClasspathAbi.MEMOIZED_ONLY))
                .isNotEqualTo(
                        ActionKey.forKotlinc("compile-main", upgraded, "0.1.0", KotlinClasspathAbi.MEMOIZED_ONLY));
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

        assertThat(ActionKey.forKotlinc(
                        "compile-kotlin",
                        kotlin(src, worker, tempDir, jdk17),
                        "0.1.0",
                        KotlinClasspathAbi.MEMOIZED_ONLY))
                .isNotEqualTo(ActionKey.forKotlinc(
                        "compile-kotlin",
                        kotlin(src, worker, tempDir, jdk21),
                        "0.1.0",
                        KotlinClasspathAbi.MEMOIZED_ONLY));
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

        String before = ActionKey.forKotlinc(
                "compile-kotlin", kotlin(src, worker, tempDir, jdk), "0.1.0", KotlinClasspathAbi.MEMOIZED_ONLY);
        jdk(jdk, "21.0.6+11"); // same length: the token is the content, not the file size
        String after = ActionKey.forKotlinc(
                "compile-kotlin", kotlin(src, worker, tempDir, jdk), "0.1.0", KotlinClasspathAbi.MEMOIZED_ONLY);

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
