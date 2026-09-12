// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.JavacDefaults;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.DebugInfo;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginModule;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The derived values `jk explain` and the build have to agree on, asserted at the one body that
 * derives each — the shape concluded with after a prefix-set guard proved unable to
 * see a value drift behind an agreed token.
 *
 * <p>What is NOT asserted here: that the forecast calls these owners. A test cannot show that
 * without re-deriving the answer itself, which is the very duplication being removed —
 * {@code checkForecastKeyParity} arms A2 and C read the call sites instead, and go red when either
 * side stops going through the owner.
 */
class ForecastKeyOwnerTest {

    @Test
    void compile_main_request_carries_every_field_forJavac_hashes(@TempDir Path tmp) throws Exception {
        // : the forecast built its CompileRequest independently and set none of the Scala
        // fields, none of the sibling-language classpath entries, and not the Groovy stubs
        // --source-path — all of which forJavac hashes. One body now derives all of it, so the
        // question is whether that body actually puts them in.
        Path module = Files.createDirectories(tmp.resolve("m"));
        Files.writeString(module.resolve("jk.toml"), """
                group = "t"
                name = "m"
                version = "0.1.0"
                jdk = 25
                """);
        JkBuild project = JkBuildParser.parse(module.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(module, project);
        Files.createDirectories(layout.groovyStubsDir());
        Path src = Files.writeString(module.resolve("A.java"), "class A {}");
        Path stdlib = Files.writeString(tmp.resolve("scala-library.jar"), "stdlib");
        Path compiler = Files.writeString(tmp.resolve("scala3-compiler.jar"), "compiler");
        Path groovyJar = Files.writeString(tmp.resolve("groovy.jar"), "groovy");
        Path jdk = Files.createDirectories(tmp.resolve("jdk21"));
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"21.0.5+11\"\n");
        var scala = new ScalaCompile.Setup("3.8.4", List.of(compiler), List.of(stdlib), compiler, compiler);

        CompileRequest full = PlannerCompile.mainCompileRequest(new PlannerCompile.MainCompile(
                List.of(src),
                List.of(),
                List.of(),
                layout,
                layout.classesDir(),
                25,
                List.of("-Xlint:all"),
                JavacConfig.EMPTY,
                jdk,
                true,
                true,
                groovyJar,
                scala));

        assertThat(full.javaHome()).isEqualTo(jdk);
        assertThat(full.scalaVersion()).isEqualTo("3.8.4");
        assertThat(full.compilerClasspath()).containsExactly(compiler);
        assertThat(full.mixedScala()).isTrue();
        assertThat(full.extraOptions())
                .containsSequence(
                        "--source-path",
                        layout.groovyStubsDir().toAbsolutePath().toString());
        assertThat(full.classpath()).contains(layout.kotlinClassesDir(), layout.groovyClassesDir(), groovyJar, stdlib);

        // Load-bearing, not decorative: drop the toolchain and the sibling languages and forJavac
        // computes a different key. That is exactly the drift the forecast used to ship.
        CompileRequest bare = PlannerCompile.mainCompileRequest(new PlannerCompile.MainCompile(
                List.of(src),
                List.of(),
                List.of(),
                layout,
                layout.classesDir(),
                25,
                List.of("-Xlint:all"),
                JavacConfig.EMPTY,
                jdk,
                false,
                false,
                null,
                null));
        assertThat(ActionKey.forJavac("compile-main", full, "0.1.0"))
                .isNotEqualTo(ActionKey.forJavac("compile-main", bare, "0.1.0"));
    }

    @Test
    void compile_test_request_carries_every_field_forJavac_hashes(@TempDir Path tmp) throws Exception {
        // The same shape for compile-test: the toolchain and the Scala fields are part of the
        // derivation, so a request built without them keys differently — and both the build and
        // the forecast get theirs from this one body.
        Path src = Files.writeString(tmp.resolve("ATest.java"), "class ATest {}");
        Path stdlib = Files.writeString(tmp.resolve("scala-library.jar"), "stdlib");
        Path compiler = Files.writeString(tmp.resolve("scala3-compiler.jar"), "compiler");
        Path jdk = Files.createDirectories(tmp.resolve("jdk21"));
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"21.0.5+11\"\n");
        Path out = tmp.resolve("classes/test");
        var scala = new ScalaCompile.Setup("3.8.4", List.of(compiler), List.of(stdlib), compiler, compiler);

        CompileRequest full = PlannerCompile.testCompileRequest(new PlannerCompile.TestCompile(
                List.of(src), List.of(), List.of(), out, 25, List.of("-Xlint:all"), JavacConfig.EMPTY, jdk, scala));

        assertThat(full.javaHome()).isEqualTo(jdk);
        assertThat(full.scalaVersion()).isEqualTo("3.8.4");
        assertThat(full.compilerClasspath()).containsExactly(compiler);
        assertThat(full.classpath())
                .as("the Scala stdlib joins the classpath here, not at a caller")
                .contains(stdlib);

        CompileRequest bare = PlannerCompile.testCompileRequest(new PlannerCompile.TestCompile(
                List.of(src), List.of(), List.of(), out, 25, List.of("-Xlint:all"), JavacConfig.EMPTY, jdk, null));
        assertThat(ActionKey.forJavac("compile-test", full, "0.1.0"))
                .isNotEqualTo(ActionKey.forJavac("compile-test", bare, "0.1.0"));
    }

    @Test
    void a_javac_plugin_is_one_argv_element_in_both_requests_and_its_options_move_the_key(@TempDir Path tmp)
            throws Exception {
        // [javac] is lowered by the two request owners, so the build and the forecast see the same
        // argv: the plugin and its options as javac's single -Xplugin: argument, then [javac] args
        // last. An option change is a key change, or a NullAway severity bump restores stale classes.
        Path module = Files.createDirectories(tmp.resolve("m"));
        Files.writeString(module.resolve("jk.toml"), """
                group = "t"
                name = "m"
                version = "0.1.0"
                java = 25

                [javac]
                plugins = { ErrorProne = { options = ["-Xep:NullAway:ERROR", "-XepOpt:NullAway:AnnotatedPackages=t"] } }
                args    = ["-XDcompilePolicy=simple", "--should-stop=ifError=FLOW"]
                """);
        JkBuild project = JkBuildParser.parse(module.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(module, project);
        Path src = Files.writeString(module.resolve("A.java"), "class A {}");
        Path jdk = Files.createDirectories(tmp.resolve("jdk25"));
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"25.0.1+9\"\n");
        String plugin = "-Xplugin:ErrorProne -Xep:NullAway:ERROR -XepOpt:NullAway:AnnotatedPackages=t";

        CompileRequest main = PlannerCompile.mainCompileRequest(new PlannerCompile.MainCompile(
                List.of(src),
                List.of(),
                List.of(),
                layout,
                layout.classesDir(),
                25,
                List.of("-Xlint:all"),
                project.build().javac(),
                jdk,
                false,
                false,
                null,
                null));
        assertThat(main.extraOptions())
                .containsExactly("-Xlint:all", plugin, "-XDcompilePolicy=simple", "--should-stop=ifError=FLOW");
        assertThat(PlannerCompile.pluginNames(main)).containsExactly("ErrorProne");

        CompileRequest test = PlannerCompile.testCompileRequest(new PlannerCompile.TestCompile(
                List.of(src),
                List.of(),
                List.of(),
                layout.testClassesDir(),
                25,
                List.of("-Xlint:all"),
                project.build().javac(),
                jdk,
                null));
        assertThat(test.extraOptions()).as("compile-test runs the same plugins").isEqualTo(main.extraOptions());

        JavacConfig warn = new JavacConfig(
                Map.of("ErrorProne", List.of("-Xep:NullAway:WARN", "-XepOpt:NullAway:AnnotatedPackages=t")),
                project.build().javac().args());
        CompileRequest relaxed = PlannerCompile.mainCompileRequest(new PlannerCompile.MainCompile(
                List.of(src),
                List.of(),
                List.of(),
                layout,
                layout.classesDir(),
                25,
                List.of("-Xlint:all"),
                warn,
                jdk,
                false,
                false,
                null,
                null));
        assertThat(ActionKey.forJavac("compile-main", main, "0.1.0"))
                .isNotEqualTo(ActionKey.forJavac("compile-main", relaxed, "0.1.0"));
    }

    /** The debug-info level rides the javac argv, so changing it is a compile-main key move. */
    @Test
    void the_debug_info_level_is_a_compile_input(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        Files.writeString(module.resolve("jk.toml"), """
                group = "t"
                name = "m"
                version = "0.1.0"
                java = 25
                """);
        JkBuild project = JkBuildParser.parse(module.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(module, project);
        Path src = Files.writeString(module.resolve("A.java"), "class A {}");
        Path jdk = Files.createDirectories(tmp.resolve("jdk25"));
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"25.0.1+9\"\n");

        CompileRequest full =
                debugRequest(project, layout, src, jdk, project.build().debug());
        CompileRequest none = debugRequest(project, layout, src, jdk, DebugInfo.NONE);
        assertThat(full.extraOptions())
                .as("the default is full debug info, as Gradle and Maven compile")
                .startsWith("-g");
        assertThat(none.extraOptions()).startsWith("-g:none");
        assertThat(ActionKey.forJavac("compile-main", full, "0.1.0"))
                .isNotEqualTo(ActionKey.forJavac("compile-main", none, "0.1.0"));
    }

    private static CompileRequest debugRequest(
            JkBuild project, BuildLayout layout, Path src, Path jdk, DebugInfo debug) {
        return PlannerCompile.mainCompileRequest(new PlannerCompile.MainCompile(
                List.of(src),
                List.of(),
                List.of(),
                layout,
                layout.classesDir(),
                25,
                JavacDefaults.effectiveArgs(project.build().lint(), debug, List.of(), List.of()),
                project.build().javac(),
                jdk,
                false,
                false,
                null,
                null));
    }

    @Test
    void compile_main_sources_are_the_union_the_build_compiles(@TempDir Path tmp) throws Exception {
        // The other half of the same key: the forecast walked src/main/java only, so a Scala module
        // (whose .scala files ride javac's request through Zinc) and any module with a [build]
        // extra-src overlay keyed a source set the build never compiles.
        Path module = Files.createDirectories(tmp.resolve("m"));
        Files.writeString(module.resolve("jk.toml"), """
                group = "t"
                name = "m"
                version = "0.1.0"
                jdk = 25

                [build]
                extra-src = ["src/overlay"]
                """);
        JkBuild project = JkBuildParser.parse(module.resolve("jk.toml"));
        Path main = Files.createDirectories(module.resolve("src/main/java"));
        Path overlay = Files.createDirectories(module.resolve("src/overlay"));
        Path scalaRoot = Files.createDirectories(module.resolve("src/main/scala"));
        Path a = Files.writeString(main.resolve("A.java"), "class A {}");
        Path b = Files.writeString(overlay.resolve("B.java"), "class B {}");
        Path c = Files.writeString(scalaRoot.resolve("C.scala"), "class C");

        assertThat(PlannerCompile.javaAndScalaSources(project, module, false, CompileSupport.collectJavaSources(main)))
                .containsExactlyInAnyOrder(a, b, c);
    }

    @Test
    void assembly_key_is_one_body_the_build_and_the_forecast_share(@TempDir Path tmp) throws Exception {
        // : both package-assembly sites emitted `main:`, the build from project.mainClass
        // and the forecast from PluginModule.mainClass(dir, project). One body now derives the whole
        // bag; the expectation below is spelled out by hand rather than taken from either side, so
        // it pins the values and not merely the agreement.
        Path module = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(module.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                jdk = 25

                [application]
                main = "t.Main"
                assembly = true
                """);
        Files.writeString(module.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "test"
                resolution-algorithm = "pubgrub-v1"
                """);
        JkBuild project = JkBuildParser.parse(module.resolve("jk.toml"));
        assertThat(project.assembly()).isTrue();
        BuildLayout layout = BuildLayout.of(module, project);
        Path classes = Files.createDirectories(layout.classesDir());
        Files.write(classes.resolve("Main.class"), new byte[] {1, 2, 3});

        List<String> expectedTokens = List.of(
                "classes:" + ClasspathFingerprint.entry(classes),
                "contrib:" + PlannerSupport.contributionsToken(List.of()),
                "deps:" + ClasspathFingerprint.of(List.of()),
                "main:t.Main",
                "manifest:" + project.manifest(),
                "packaging:fat");
        String expectedTask = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_ASSEMBLY, layout.assemblyJar());
        String expectedKey = ActionKey.forArtifact(expectedTask, BuildIdentity.cacheKeyVersion(), expectedTokens);

        // Build side — PlannerTails.assemblyStep calls exactly this.
        PackagingKeys.Keyed keyed = PackagingKeys.assembly(
                layout.assemblyJar(),
                module,
                project,
                ClasspathFingerprint.entry(classes),
                PlannerSupport.contributionsToken(List.of()),
                ClasspathFingerprint.of(List.of()));
        assertThat(keyed.tokens()).isEqualTo(expectedTokens);
        assertThat(keyed.key()).isEqualTo(expectedKey);

        // Forecast side: with that record in the action cache, explain reports up-to-date.
        Path cache = tmp.resolve("cache");
        ActionCache actionCache = new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache));
        byte[] jarBytes = "assembly".getBytes(StandardCharsets.UTF_8);
        String sha = Hashing.sha256Hex(jarBytes);
        actionCache.cas().put(jarBytes, sha);
        actionCache.storeWithOutputs(
                expectedTask,
                expectedKey,
                Map.of(),
                Map.of(layout.assemblyJar().getFileName().toString(), sha),
                Map.of());

        assertThat(PackagingKeys.assemblyActionCached(
                        module,
                        project,
                        layout,
                        module.resolve("jk-lock.toml"),
                        actionCache,
                        cache,
                        null,
                        Map.of(),
                        false))
                .as("the forecast recomputes the key the build stored")
                .isTrue();
    }

    @Test
    void the_packaged_main_class_is_the_worker_entry_for_a_plugin_worker(@TempDir Path tmp) throws Exception {
        // The value the two assembly sites must agree on. It is worth pinning even though
        // the disagreement is currently unreachable: JkBuildParser refuses an [application] table on
        // a plugin worker ("already implies main cc.jumpkick.plugin.process.PluginMain"), and
        // `assembly` only exists inside [application] — so no worker module can be assembly = true
        // today, and's reported symptom (a permanent package-assembly [run]) cannot occur.
        // What was real is the second derivation; this is the one that remains.
        Path worker = Files.createDirectories(tmp.resolve("w"));
        Files.writeString(worker.resolve("jk.toml"), """
                group = "t"
                name = "w"
                version = "0.1.0"
                jdk = 25
                """);
        Path services = Files.createDirectories(worker.resolve("src/main/resources/META-INF/services"));
        Files.writeString(services.resolve("cc.jumpkick.plugin.Plugin"), "t.Worker\n");
        JkBuild project = JkBuildParser.parse(worker.resolve("jk.toml"));

        assertThat(project.mainClass()).isNull();
        assertThat(PackagingKeys.mainClass(worker, project)).isEqualTo(PluginModule.WORKER_MAIN);
        // …and for an ordinary module it is the authored main, so one owner serves both.
        Path app = Files.createDirectories(tmp.resolve("a"));
        Files.writeString(app.resolve("jk.toml"), """
                group = "t"
                name = "a"
                version = "0.1.0"
                jdk = 25

                [application]
                main = "t.Main"
                """);
        JkBuild appBuild = JkBuildParser.parse(app.resolve("jk.toml"));
        assertThat(PackagingKeys.mainClass(app, appBuild)).isEqualTo("t.Main");
    }
}
