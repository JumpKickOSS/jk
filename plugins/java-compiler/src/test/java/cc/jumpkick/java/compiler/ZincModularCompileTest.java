// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module with a {@code module-info.java} compiles on the module path: an explicit module in a
 * sibling's classes tree and an automatic module from a plain jar both satisfy its {@code requires},
 * and a test compile that patches the module reads its test classpath as the unnamed module.
 */
class ZincModularCompileTest {

    @Test
    void a_module_descriptor_resolves_its_requires_from_the_classpath_on_the_module_path(@TempDir Path dir)
            throws Exception {
        Path lib = explicitModule(dir);
        Path plain = automaticModuleJar(dir);
        Path src = dir.resolve("app/src");
        write(src, "module-info.java", "module com.acme.app { requires com.acme.lib; requires plainlib; }");
        write(src, "com/acme/app/App.java", """
                package com.acme.app;
                public class App {
                    public static String run() { return com.acme.lib.Lib.hi() + com.acme.plain.Plain.p(); }
                }
                """);
        Path classes = dir.resolve("app/classes");

        ZincJavaCompiler.Result r = ZincJavaCompiler.compileJava(
                job(src, List.of(lib, plain), classes, dir.resolve("app/zinc"), List.of()));

        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(classes.resolve("module-info.class")).isRegularFile();
        assertThat(classes.resolve("com/acme/app/App.class")).isRegularFile();
        assertThat(ZincJavaCompiler.planJava(job(src, List.of(lib, plain), classes, dir.resolve("app/zinc"), List.of()))
                        .full())
                .as("the forecast derives the same module-path options as the compile, so nothing is stale")
                .isFalse();
    }

    /**
     * An incremental compile keeps the module's descriptor: editing one class recompiles what it
     * invalidates and leaves {@code module-info.class} in the classes tree, where a jar and every
     * module that requires this one read it.
     */
    @Test
    void an_incremental_compile_keeps_the_module_descriptor(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("app/src");
        write(src, "module-info.java", "module com.acme.app { exports com.acme.a; exports com.acme.b; }");
        write(src, "com/acme/a/A.java", "package com.acme.a; public class A {}");
        write(
                src,
                "com/acme/b/B.java",
                "package com.acme.b; public class B { public com.acme.a.A a() { return null; } }");
        Path classes = dir.resolve("app/classes");
        Path zinc = dir.resolve("app/zinc");
        assertThat(ZincJavaCompiler.compileJava(job(src, List.of(), classes, zinc, List.of()))
                        .success())
                .isTrue();
        assertThat(classes.resolve("module-info.class")).isRegularFile();

        write(src, "com/acme/a/A.java", "package com.acme.a; public class A { public int x() { return 1; } }");
        ZincJavaCompiler.Result r = ZincJavaCompiler.compileJava(job(src, List.of(), classes, zinc, List.of()));

        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(classes.resolve("module-info.class"))
                .as("recompiled %s", r.compiledSources())
                .isRegularFile();
    }

    /**
     * A compile that finds nothing to recompile keeps the descriptor too: the engine runs one when a
     * dependency's API moved without touching anything this module uses, or when jk itself changed,
     * and the stale-class sweep before it must not take {@code module-info.class}, which is in no
     * Zinc analysis as a product, with nothing compiled afterwards to write it back.
     */
    @Test
    void a_compile_with_nothing_to_recompile_keeps_the_module_descriptor(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("app/src");
        write(src, "module-info.java", "module com.acme.app { exports com.acme.a; }");
        write(src, "com/acme/a/A.java", "package com.acme.a; public class A {}");
        Path classes = dir.resolve("app/classes");
        Path zinc = dir.resolve("app/zinc");
        assertThat(ZincJavaCompiler.compileJava(job(src, List.of(), classes, zinc, List.of()))
                        .success())
                .isTrue();

        ZincJavaCompiler.Result again = ZincJavaCompiler.compileJava(job(src, List.of(), classes, zinc, List.of()));

        assertThat(again.success()).as(again.diagnostics().toString()).isTrue();
        assertThat(classes.resolve("module-info.class"))
                .as("recompiled %s", again.compiledSources())
                .isRegularFile();
    }

    /** A classes tree that lost its descriptor gets it back on the next compile, whatever changed. */
    @Test
    void a_lost_module_descriptor_is_written_back_by_the_next_compile(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("app/src");
        write(src, "module-info.java", "module com.acme.app { exports com.acme.a; }");
        write(src, "com/acme/a/A.java", "package com.acme.a; public class A {}");
        Path classes = dir.resolve("app/classes");
        Path zinc = dir.resolve("app/zinc");
        assertThat(ZincJavaCompiler.compileJava(job(src, List.of(), classes, zinc, List.of()))
                        .success())
                .isTrue();
        Files.delete(classes.resolve("module-info.class"));

        ZincJavaCompiler.Result again = ZincJavaCompiler.compileJava(job(src, List.of(), classes, zinc, List.of()));

        assertThat(again.success()).as(again.diagnostics().toString()).isTrue();
        assertThat(classes.resolve("module-info.class")).isRegularFile();
        assertThat(classes.resolve("com/acme/a/A.class")).isRegularFile();
    }

    /**
     * Only what the descriptor requires joins the module path. Two automatic modules the module
     * never names, splitting one package between them, stay on the classpath: on the module path,
     * resolving any automatic module resolves them all, and javac fails on the split package.
     */
    @Test
    void automatic_modules_the_descriptor_does_not_require_stay_off_the_module_path(@TempDir Path dir)
            throws Exception {
        Path lib = explicitModule(dir);
        Path plain = automaticModuleJar(dir);
        Path splitA = splitJar(dir, "split-a", "A");
        Path splitB = splitJar(dir, "split-b", "B");
        Path src = dir.resolve("app/src");
        write(src, "module-info.java", "module com.acme.app { requires com.acme.lib; requires plainlib; }");
        write(src, "com/acme/app/App.java", """
                package com.acme.app;
                public class App {
                    public static String run() { return com.acme.lib.Lib.hi() + com.acme.plain.Plain.p(); }
                }
                """);
        Path classes = dir.resolve("app/classes");

        ZincJavaCompiler.Result r = ZincJavaCompiler.compileJava(
                job(src, List.of(lib, splitA, plain, splitB), classes, dir.resolve("app/zinc"), List.of()));

        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(classes.resolve("module-info.class")).isRegularFile();
    }

    /**
     * A dependency's {@code requires static transitive} joins the module path when it is on the
     * classpath: javac resolves it for the modules that read the dependency, as JUnit's API does
     * with {@code org.apiguardian.api}.
     */
    @Test
    void a_dependencys_static_transitive_requires_joins_the_module_path(@TempDir Path dir) throws Exception {
        Path guardSrc = dir.resolve("guard/src");
        write(guardSrc, "module-info.java", "module ex.guard { exports ex.guard; }");
        write(guardSrc, "ex/guard/Api.java", "package ex.guard; public @interface Api {}");
        Path guard = dir.resolve("guard/classes");
        javac(guardSrc, guard, List.of());
        Path apiSrc = dir.resolve("api/src");
        write(apiSrc, "module-info.java", "module ex.api { requires static transitive ex.guard; exports ex.api; }");
        write(apiSrc, "ex/api/Thing.java", "package ex.api; @ex.guard.Api public class Thing {}");
        Path api = dir.resolve("api/classes");
        javac(apiSrc, api, List.of("--module-path", guard.toString()));
        Path src = dir.resolve("app/src");
        write(src, "module-info.java", "module ex.app { requires ex.api; }");
        write(src, "ex/app/App.java", "package ex.app; public class App { ex.api.Thing t; }");
        Path classes = dir.resolve("app/classes");

        ZincJavaCompiler.Result r = ZincJavaCompiler.compileJava(
                job(src, List.of(api, guard), classes, dir.resolve("app/zinc"), List.of()));

        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
    }

    /** {@code <name>-1.0.jar}, an automatic module holding {@code org.split.<type>}: two of them split the package. */
    private static Path splitJar(Path dir, String name, String type) throws IOException {
        Path src = dir.resolve(name + "/src");
        write(src, "org/split/" + type + ".java", "package org.split; public class " + type + " {}");
        Path out = dir.resolve(name + "/classes");
        javac(src, out, List.of());
        return jar(out, dir.resolve(name + "-1.0.jar"));
    }

    @Test
    void a_patched_test_compile_reads_the_unnamed_module(@TempDir Path dir) throws Exception {
        Path lib = explicitModule(dir);
        Path plain = automaticModuleJar(dir);
        Path src = dir.resolve("app/src");
        write(src, "module-info.java", "module com.acme.app { requires com.acme.lib; requires plainlib; }");
        write(src, "com/acme/app/App.java", """
                package com.acme.app;
                public class App {
                    static String secret() { return "package-private"; }
                    public static String run() { return com.acme.lib.Lib.hi() + com.acme.plain.Plain.p(); }
                }
                """);
        Path classes = dir.resolve("app/classes");
        assertThat(ZincJavaCompiler.compileJava(
                                job(src, List.of(lib, plain), classes, dir.resolve("app/zinc"), List.of()))
                        .success())
                .isTrue();
        Path helper = classpathOnlyJar(dir);
        Path testSrc = dir.resolve("app/test");
        write(testSrc, "com/acme/app/AppTest.java", """
                package com.acme.app;
                public class AppTest {
                    public static String t() { return App.secret() + App.run() + org.helper.Helper.h(); }
                }
                """);
        List<String> patch = List.of(
                "--patch-module",
                "com.acme.app=" + testSrc.toAbsolutePath(),
                "--add-reads",
                "com.acme.app=ALL-UNNAMED");

        ZincJavaCompiler.Result r = ZincJavaCompiler.compileJava(job(
                testSrc,
                List.of(classes, lib, plain, helper),
                dir.resolve("app/test-classes"),
                dir.resolve("app/zinc-test"),
                patch));

        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(dir.resolve("app/test-classes/com/acme/app/AppTest.class")).isRegularFile();
    }

    @Test
    void a_test_descriptor_for_the_main_module_compiles_with_the_main_classes_patched_in(@TempDir Path dir)
            throws Exception {
        Path lib = explicitModule(dir);
        Path src = dir.resolve("app/src");
        write(src, "module-info.java", "module com.acme.app { requires com.acme.lib; exports com.acme.app; }");
        write(src, "com/acme/app/App.java", """
                package com.acme.app;
                public class App {
                    static String secret() { return com.acme.lib.Lib.hi(); }
                }
                """);
        Path classes = dir.resolve("app/classes");
        assertThat(ZincJavaCompiler.compileJava(job(src, List.of(lib), classes, dir.resolve("app/zinc"), List.of()))
                        .success())
                .isTrue();
        Path helper = classpathOnlyJar(dir);
        Path testSrc = dir.resolve("app/test");
        write(testSrc, "module-info.java", """
                module com.acme.app { requires com.acme.lib; requires helper; exports com.acme.app; }
                """);
        write(testSrc, "com/acme/app/AppTest.java", """
                package com.acme.app;
                public class AppTest {
                    public static String t() { return App.secret() + org.helper.Helper.h(); }
                }
                """);
        List<String> patch = List.of("--patch-module", "com.acme.app=" + classes.toAbsolutePath());

        ZincJavaCompiler.Result r = ZincJavaCompiler.compileJava(job(
                testSrc,
                List.of(classes, lib, helper),
                dir.resolve("app/test-classes"),
                dir.resolve("app/zinc-test"),
                patch));

        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(dir.resolve("app/test-classes/com/acme/app/AppTest.class")).isRegularFile();
    }

    /** {@code com.acme.lib}, an explicit module compiled to a classes directory. */
    private static Path explicitModule(Path dir) throws IOException {
        Path src = dir.resolve("lib/src");
        write(src, "module-info.java", "module com.acme.lib { exports com.acme.lib; }");
        write(
                src,
                "com/acme/lib/Lib.java",
                "package com.acme.lib; public class Lib { public static String hi() { return \"hi\"; } }");
        Path out = dir.resolve("lib/classes");
        javac(src, out, List.of());
        return out;
    }

    /** {@code plainlib-1.0.jar}: no descriptor, so the module system derives {@code plainlib}. */
    private static Path automaticModuleJar(Path dir) throws IOException {
        Path src = dir.resolve("plain/src");
        write(
                src,
                "com/acme/plain/Plain.java",
                "package com.acme.plain; public class Plain { public static String p() { return \"p\"; } }");
        Path out = dir.resolve("plain/classes");
        javac(src, out, List.of());
        return jar(out, dir.resolve("plainlib-1.0.jar"));
    }

    /** A test-only dependency, read through the unnamed module. */
    private static Path classpathOnlyJar(Path dir) throws IOException {
        Path src = dir.resolve("helper/src");
        write(
                src,
                "org/helper/Helper.java",
                "package org.helper; public class Helper { public static String h() { return \"h\"; } }");
        Path out = dir.resolve("helper/classes");
        javac(src, out, List.of());
        return jar(out, dir.resolve("helper-2.0.jar"));
    }

    private static JavaCompileJob job(Path src, List<Path> classpath, Path classes, Path workdir, List<String> extra)
            throws IOException {
        return new JavaCompileJob(sources(src), classpath, classes, workdir, null, 25, extra, List.of());
    }

    private static List<Path> sources(Path src) throws IOException {
        try (var walk = Files.walk(src)) {
            return walk.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static void javac(Path src, Path out, List<String> options) throws IOException {
        Files.createDirectories(out);
        List<String> args = new ArrayList<>(options);
        args.add("-d");
        args.add(out.toString());
        for (Path s : sources(src)) args.add(s.toString());
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
        assertThat(rc).isZero();
    }

    private static Path jar(Path classes, Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file);
                JarOutputStream jar = new JarOutputStream(out);
                var walk = Files.walk(classes)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                jar.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                jar.write(Files.readAllBytes(p));
                jar.closeEntry();
            }
        }
        return file;
    }

    private static void write(Path root, String rel, String body) throws IOException {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body);
    }
}
