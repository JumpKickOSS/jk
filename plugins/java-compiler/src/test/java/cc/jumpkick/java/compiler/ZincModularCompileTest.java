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
