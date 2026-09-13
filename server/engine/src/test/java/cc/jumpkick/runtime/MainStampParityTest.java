// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.task.AbiJars;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathAbi;
import cc.jumpkick.task.FreshnessStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The compile-main freshness stamp is derived from the one request the action key hashes
 * ({@link PlannerCompile#mainCompileRequest}): its classpath section is {@link
 * ActionKey#javacClasspathTokens} and its option digest is {@link ActionKey#javacOptionsDigest},
 * on the live check, in {@code write-stamp} and in the forecast alike. Two hand-maintained input
 * recipes drifted before (the forecast missed the mixed-language classpath entries, write-stamp
 * missed the processor path); one request cannot.
 */
class MainStampParityTest {

    private static final int RELEASE = 21;

    private static BuildLayout layout(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
            group = "com.example"
            name  = "mixed"
            version = "1.0.0"
            jdk = 25
            java = 25
            """);
        return BuildLayout.of(dir, JkBuildParser.parse(dir.resolve("jk.toml")));
    }

    /** Backdate an input so same-millisecond creation never trips the {@code >=} mtime check. */
    private static Path aged(Path p) throws Exception {
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        return p;
    }

    private static Path source(Path dir) throws Exception {
        Path src = dir.resolve("src/main/java/A.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "public class A {}");
        return aged(src);
    }

    @Test
    void a_mixed_kotlin_stamp_written_from_the_request_is_fresh_under_the_same_request(@TempDir Path dir)
            throws Exception {
        BuildLayout layout = layout(dir);
        List<Path> sources = List.of(source(dir));
        Path dep = AbiJars.jar(dir.resolve("dep.jar"), AbiJars.classReturning(1));
        Path processor = AbiJars.jar(dir.resolve("processor.jar"), AbiJars.classReturning(1));
        // A compiled mixed module has the sibling compiler's output dir.
        Files.createDirectories(layout.kotlinClassesDir());
        Path out = layout.classesDir();
        Path javaHome = Path.of(System.getProperty("java.home"));

        CompileRequest written = PlannerCompile.mainCompileRequest(
                mainCompile(sources, List.of(dep), List.of(processor), layout, out, List.of(), javaHome, true));
        write(out, sources, written);

        CompileRequest recomputed = PlannerCompile.mainCompileRequest(
                mainCompile(sources, List.of(dep), List.of(processor), layout, out, List.of(), javaHome, true));
        assertThat(fresh(out, sources, recomputed)).isTrue();
        assertThat(ActionKey.javacClasspathTokens(recomputed))
                .as("the sibling compiler's output dir is a classpath token")
                .contains("cp:" + ClasspathAbi.token(layout.kotlinClassesDir()));

        // A request without the Kotlin classes dir hashes a different classpath → never fresh.
        CompileRequest javaOnly = PlannerCompile.mainCompileRequest(
                mainCompile(sources, List.of(dep), List.of(processor), layout, out, List.of(), javaHome, false));
        assertThat(fresh(out, sources, javaOnly)).isFalse();
    }

    /**
     * A classpath jar rewritten with the same API keeps the stamp — that is compile avoidance —
     * while a processor jar rewritten the same way busts it, because the processor path is keyed by
     * content.
     */
    @Test
    void a_body_only_dependency_rewrite_keeps_the_stamp_and_a_processor_rewrite_busts_it(@TempDir Path dir)
            throws Exception {
        BuildLayout layout = layout(dir);
        List<Path> sources = List.of(source(dir));
        Path dep = AbiJars.jar(dir.resolve("dep.jar"), AbiJars.classReturning(1));
        Path processor = AbiJars.jar(dir.resolve("processor.jar"), AbiJars.classReturning(1));
        Path out = layout.classesDir();
        Path javaHome = Path.of(System.getProperty("java.home"));

        write(out, sources, request(sources, dep, processor, layout, out, javaHome));
        assertThat(fresh(out, sources, request(sources, dep, processor, layout, out, javaHome)))
                .isTrue();

        AbiJars.jar(dep, AbiJars.classReturning(2));
        assertThat(fresh(out, sources, request(sources, dep, processor, layout, out, javaHome)))
                .as("same API, new bytes: the consumer's compile is not due")
                .isTrue();

        AbiJars.jar(processor, AbiJars.classReturning(2));
        assertThat(fresh(out, sources, request(sources, dep, processor, layout, out, javaHome)))
                .as("a processor's behaviour is its bodies")
                .isFalse();
    }

    /**
     * The build and the forecast derive the stamp's option digest from the same compile-main
     * request, so a {@code [javac] args} edit with untouched sources reads stale on both sides,
     * and an unchanged manifest reads fresh on both.
     */
    @Test
    void javac_args_bust_the_stamp_through_the_shared_request(@TempDir Path dir) throws Exception {
        BuildLayout layout = layout(dir);
        List<Path> sources = List.of(source(dir));
        Path out = layout.classesDir();
        Path javaHome = Path.of(System.getProperty("java.home"));

        CompileRequest lenient = PlannerCompile.mainCompileRequest(
                mainCompile(sources, List.of(), List.of(), layout, out, List.of(), javaHome, false));
        write(out, sources, lenient);

        CompileRequest same = PlannerCompile.mainCompileRequest(
                mainCompile(sources, List.of(), List.of(), layout, out, List.of(), javaHome, false));
        assertThat(fresh(out, sources, same)).isTrue();

        CompileRequest strict = PlannerCompile.mainCompileRequest(mainCompile(
                sources, List.of(), List.of(), layout, out, List.of("-Xlint:all", "-Werror"), javaHome, false));
        assertThat(fresh(out, sources, strict))
                .as("changed [javac] args with untouched sources recompile")
                .isFalse();
    }

    @Test
    void a_mixed_groovy_request_folds_the_classes_dir_and_the_groovy_jar_into_the_tokens(@TempDir Path dir)
            throws Exception {
        BuildLayout layout = layout(dir);
        Path dep = AbiJars.jar(dir.resolve("dep.jar"), AbiJars.classReturning(1));
        Path groovyJar = AbiJars.jar(dir.resolve("groovy.jar"), AbiJars.classReturning(1));
        Files.createDirectories(layout.groovyClassesDir());

        CompileRequest req = PlannerCompile.mainCompileRequest(new PlannerCompile.MainCompile(
                List.of(source(dir)),
                List.of(dep),
                List.of(),
                layout,
                layout.classesDir(),
                RELEASE,
                List.of(),
                JavacConfig.EMPTY,
                Path.of(System.getProperty("java.home")),
                false,
                true,
                groovyJar,
                null));

        assertThat(ActionKey.javacClasspathTokens(req))
                .containsExactlyInAnyOrder(
                        "cp:" + ClasspathAbi.token(dep),
                        "cp:" + ClasspathAbi.token(layout.groovyClassesDir()),
                        "cp:" + ClasspathAbi.token(groovyJar));
    }

    private static void write(Path out, List<Path> sources, CompileRequest request) throws IOException {
        FreshnessStamp.write(
                out,
                BuildStamps.JAVA,
                "compile-main",
                "",
                sources,
                FreshnessStamp.ClasspathTokens.of(ActionKey.javacClasspathTokens(request)),
                RELEASE,
                ActionKey.javacOptionsDigest(request));
    }

    private static boolean fresh(Path out, List<Path> sources, CompileRequest request) throws IOException {
        return FreshnessStamp.isFresh(
                out,
                BuildStamps.JAVA,
                sources,
                FreshnessStamp.ClasspathTokens.of(ActionKey.javacClasspathTokens(request)),
                RELEASE,
                ActionKey.javacOptionsDigest(request));
    }

    private static CompileRequest request(
            List<Path> sources, Path dep, Path processor, BuildLayout layout, Path out, Path javaHome) {
        return PlannerCompile.mainCompileRequest(
                mainCompile(sources, List.of(dep), List.of(processor), layout, out, List.of(), javaHome, false));
    }

    private static PlannerCompile.MainCompile mainCompile(
            List<Path> sources,
            List<Path> classpath,
            List<Path> processorPath,
            BuildLayout layout,
            Path out,
            List<String> javacArgs,
            Path javaHome,
            boolean mixedKotlin) {
        return new PlannerCompile.MainCompile(
                sources,
                classpath,
                processorPath,
                layout,
                out,
                RELEASE,
                javacArgs,
                JavacConfig.EMPTY,
                javaHome,
                mixedKotlin,
                false,
                null,
                null);
    }
}
