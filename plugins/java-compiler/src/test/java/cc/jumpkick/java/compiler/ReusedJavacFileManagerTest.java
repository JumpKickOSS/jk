// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A file manager is held across compiles so the classpath is not re-opened and re-indexed for each
 * one, which is a fixed per-compile cost that dominates on Windows. Holding it is only sound if a
 * later compile still sees the classpath as it is <em>now</em>, so the tests that matter here are
 * the staleness ones: an upstream output directory that gained or lost a class between two compiles
 * must be seen by the second, or a module would compile against a neighbour's previous ABI and the
 * failure would surface as an impossible "cannot find symbol" much later.
 */
class ReusedJavacFileManagerTest {

    private static final JavaCompiler JAVAC = ToolProvider.getSystemJavaCompiler();

    @Test
    void the_same_thread_gets_one_manager_back_rather_than_a_new_one() {
        StandardJavaFileManager first = ReusedJavacFileManager.acquire(JAVAC, StandardCharsets.UTF_8, d -> {}, false);
        StandardJavaFileManager second = ReusedJavacFileManager.acquire(JAVAC, StandardCharsets.UTF_8, d -> {}, false);

        assertThat(second).isSameAs(first);
    }

    /**
     * The charset a manager is built with is what decodes sources and cannot be changed afterwards,
     * so a request for a different one has to be a different manager rather than the cached one.
     */
    @Test
    void a_different_charset_replaces_the_manager_instead_of_reusing_it() {
        StandardJavaFileManager utf8 = ReusedJavacFileManager.acquire(JAVAC, StandardCharsets.UTF_8, d -> {}, false);
        StandardJavaFileManager latin1 =
                ReusedJavacFileManager.acquire(JAVAC, StandardCharsets.ISO_8859_1, d -> {}, false);

        assertThat(latin1).isNotSameAs(utf8);
        assertThat(ReusedJavacFileManager.acquire(JAVAC, StandardCharsets.UTF_8, d -> {}, false))
                .isNotSameAs(utf8);
    }

    /** Not thread-safe, so a second compile thread must get its own rather than share this one. */
    @Test
    void another_thread_gets_its_own_manager() throws Exception {
        StandardJavaFileManager mine = ReusedJavacFileManager.acquire(JAVAC, StandardCharsets.UTF_8, d -> {}, false);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<StandardJavaFileManager> theirs =
                    pool.submit(() -> ReusedJavacFileManager.acquire(JAVAC, StandardCharsets.UTF_8, d -> {}, false));

            assertThat(theirs.get()).isNotSameAs(mine);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The upstream-ABI-changed case, which is what makes reuse safe to ship: a class added to a
     * directory already on the classpath of an earlier compile resolves in a later one.
     */
    @Test
    void a_class_added_to_a_classpath_directory_since_the_last_compile_is_seen(@TempDir Path dir) throws Exception {
        Path dep = Files.createDirectories(dir.resolve("dep"));
        Path out = Files.createDirectories(dir.resolve("out"));
        buildDep(dir, dep, "public int v() { return 1; }");

        StandardJavaFileManager fm = ReusedJavacFileManager.acquire(JAVAC, StandardCharsets.UTF_8, d -> {}, false);
        assertThat(compileAgainst(fm, dir, out, dep, "v")).isTrue();

        buildDep(dir, dep, "public int v() { return 1; } public int w() { return 2; }");

        assertThat(compileAgainst(fm, dir, out, dep, "w")).isTrue();
    }

    /** The other half: a class removed from that directory stops resolving rather than lingering. */
    @Test
    void a_class_removed_from_a_classpath_directory_stops_resolving(@TempDir Path dir) throws Exception {
        Path dep = Files.createDirectories(dir.resolve("dep"));
        Path out = Files.createDirectories(dir.resolve("out"));
        buildDep(dir, dep, "public int v() { return 1; }");

        StandardJavaFileManager fm = ReusedJavacFileManager.acquire(JAVAC, StandardCharsets.UTF_8, d -> {}, false);
        assertThat(compileAgainst(fm, dir, out, dep, "v")).isTrue();

        deleteTree(dep);
        Files.createDirectories(dep);

        assertThat(compileAgainst(fm, dir, out, dep, "v")).isFalse();
    }

    /** Compiles {@code pkg.Dep} into {@code dep} with its own manager, so nothing here is cached. */
    private static void buildDep(Path dir, Path dep, String body) throws Exception {
        Path src = dir.resolve("depsrc/pkg/Dep.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package pkg; public class Dep { " + body + " }");
        int rc = JAVAC.run(null, null, null, "-nowarn", "-d", dep.toString(), src.toString());
        assertThat(rc).isZero();
    }

    /** Compiles a fresh source calling {@code method} on {@code pkg.Dep}, through {@code fm}. */
    private static boolean compileAgainst(StandardJavaFileManager fm, Path dir, Path out, Path dep, String method)
            throws Exception {
        String name = "Use" + method + System.nanoTime();
        Path src = dir.resolve("src/" + name + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "public class " + name + " { int go(pkg.Dep d) { return d." + method + "(); } }");
        fm.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of(dep));
        fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(out));
        return Boolean.TRUE.equals(JAVAC.getTask(
                        null,
                        fm,
                        d -> {},
                        List.of("-nowarn", "-proc:none"),
                        null,
                        fm.getJavaFileObjectsFromPaths(List.of(src)))
                .call());
    }

    private static void deleteTree(Path dir) throws Exception {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
