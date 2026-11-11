// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
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

    /** A pool of this test's own, so no other test's managers are in it. */
    private final ReusedJavacFileManager pool = new ReusedJavacFileManager(ReusedJavacFileManager.CAP);

    @Test
    void a_returned_manager_is_handed_to_the_next_compile() {
        StandardJavaFileManager first = take(StandardCharsets.UTF_8, Set.of());
        StandardJavaFileManager second = take(StandardCharsets.UTF_8, Set.of());

        assertThat(second).isSameAs(first);
    }

    /**
     * The charset a manager is built with is what decodes sources and cannot be changed afterwards,
     * so a request for a different one has to be a different manager rather than the cached one.
     */
    @Test
    void a_different_charset_gets_a_different_manager() {
        StandardJavaFileManager utf8 = take(StandardCharsets.UTF_8, Set.of());
        StandardJavaFileManager latin1 = take(StandardCharsets.ISO_8859_1, Set.of());

        assertThat(latin1).isNotSameAs(utf8);
        assertThat(take(StandardCharsets.UTF_8, Set.of())).isSameAs(utf8);
        assertThat(take(StandardCharsets.ISO_8859_1, Set.of())).isSameAs(latin1);
    }

    /** Not thread-safe, so a compile running while another holds a manager gets its own. */
    @Test
    void a_manager_is_held_by_one_compile_at_a_time() {
        try (var first = checkout(List.of());
                var second = checkout(List.of())) {
            assertThat(second.manager()).isNotSameAs(first.manager());
        }
        assertThat(pool.idleCount()).isEqualTo(2);
    }

    /**
     * However many compiles ran at once, no more than the cap stay open once they are done, and the
     * ones past it are closed so their jars are released.
     */
    @Test
    void the_pool_keeps_at_most_its_cap_and_closes_the_rest() {
        ReusedJavacFileManager small = new ReusedJavacFileManager(2);
        List<StandardJavaFileManager> closed = new ArrayList<>();
        JavaCompiler counting = closeRecording(closed);
        List<ReusedJavacFileManager.Lease> leases = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            leases.add(small.checkout(counting, StandardCharsets.UTF_8, d -> {}, Set.of(), List.of(), ""));
        }
        List<StandardJavaFileManager> managers =
                leases.stream().map(ReusedJavacFileManager.Lease::manager).toList();
        assertThat(managers).doesNotHaveDuplicates();

        for (var lease : leases) lease.close();

        assertThat(small.idleCount()).isEqualTo(2);
        assertThat(closed)
                .as("the four returned first are closed; the last two stay open")
                .containsExactlyElementsOf(managers.subList(0, 4));
    }

    /**
     * A compile gets the idle manager that already holds most of its classpath open, not merely the
     * one returned last, so concurrent modules with different classpaths keep their own warm.
     */
    @Test
    void a_compile_gets_the_manager_with_most_of_its_classpath(@TempDir Path dir) throws Exception {
        Path a1 = Files.createDirectories(dir.resolve("a1"));
        Path a2 = Files.createDirectories(dir.resolve("a2"));
        Path a3 = Files.createDirectories(dir.resolve("a3"));
        Path b1 = Files.createDirectories(dir.resolve("b1"));
        Path b2 = Files.createDirectories(dir.resolve("b2"));
        Path other = Files.createDirectories(dir.resolve("other"));
        StandardJavaFileManager forA;
        StandardJavaFileManager forB;
        var a = checkout(List.of(a1, a2, a3));
        var b = checkout(List.of(b1, b2));
        forA = a.manager();
        forB = b.manager();
        b.close();
        a.close();

        assertThat(take(StandardCharsets.UTF_8, Set.of(), List.of(b1, b2, other), ""))
                .as("b's manager, though a's was returned last")
                .isSameAs(forB);
        assertThat(take(StandardCharsets.UTF_8, Set.of(), List.of(a1, a2), "")).isSameAs(forA);
    }

    /**
     * A module path set by one compile must not leak into the next: a compile that declares no
     * {@code --module-path} gets a fresh manager, one that declares it keeps the held one.
     */
    @Test
    void a_module_path_the_next_compile_never_asked_for_replaces_the_manager(@TempDir Path dir) throws Exception {
        StandardJavaFileManager modular = take(StandardCharsets.UTF_8, Set.of());
        modular.setLocationFromPaths(StandardLocation.MODULE_PATH, List.of(Files.createDirectories(dir.resolve("mp"))));

        assertThat(take(StandardCharsets.UTF_8, Set.of(StandardLocation.MODULE_PATH)))
                .isSameAs(modular);
        assertThat(take(StandardCharsets.UTF_8, Set.of())).isNotSameAs(modular);
    }

    /**
     * A source path set by one compile (a mixed-language module's stubs) must not resolve types for
     * the next module, which would otherwise compile a stub body into its own output.
     */
    @Test
    void a_source_path_the_next_compile_never_asked_for_replaces_the_manager(@TempDir Path dir) throws Exception {
        StandardJavaFileManager stubbed = take(StandardCharsets.UTF_8, Set.of());
        stubbed.setLocationFromPaths(
                StandardLocation.SOURCE_PATH, List.of(Files.createDirectories(dir.resolve("stubs"))));

        assertThat(take(StandardCharsets.UTF_8, Set.of(StandardLocation.SOURCE_PATH)))
                .isSameAs(stubbed);
        assertThat(take(StandardCharsets.UTF_8, Set.of())).isNotSameAs(stubbed);
    }

    /**
     * A fresh manager already reports the system modules as set (the running JDK), so a declared
     * {@code --system} is remembered from the compile that declared it rather than probed, and a
     * manager that never saw one is kept.
     */
    @Test
    void a_system_image_an_earlier_compile_declared_replaces_the_manager() {
        StandardJavaFileManager plain = take(StandardCharsets.UTF_8, Set.of());
        assertThat(take(StandardCharsets.UTF_8, Set.of())).isSameAs(plain);

        StandardJavaFileManager systemed = take(StandardCharsets.UTF_8, Set.of(StandardLocation.SYSTEM_MODULES));
        assertThat(systemed).isSameAs(plain);
        assertThat(take(StandardCharsets.UTF_8, Set.of(StandardLocation.SYSTEM_MODULES)))
                .isSameAs(systemed);
        assertThat(take(StandardCharsets.UTF_8, Set.of())).isNotSameAs(systemed);
    }

    /** Every javac option that sets a path location on the file manager is declared, in both spellings. */
    @Test
    void every_path_option_declares_its_location() {
        assertThat(ProvenanceJavac.declaredLocations(new String[] {
                    "-sourcepath",
                    "a",
                    "--module-source-path",
                    "b",
                    "-processorpath",
                    "c",
                    "--processor-module-path",
                    "d",
                    "-p",
                    "e",
                    "--upgrade-module-path",
                    "f",
                    "--patch-module",
                    "m=g",
                    "--system",
                    "h",
                    "-h",
                    "i",
                    "-d",
                    "out",
                    "-s",
                    "gen"
                }))
                .containsExactlyInAnyOrder(
                        StandardLocation.SOURCE_PATH,
                        StandardLocation.MODULE_SOURCE_PATH,
                        StandardLocation.ANNOTATION_PROCESSOR_PATH,
                        StandardLocation.ANNOTATION_PROCESSOR_MODULE_PATH,
                        StandardLocation.MODULE_PATH,
                        StandardLocation.UPGRADE_MODULE_PATH,
                        StandardLocation.PATCH_MODULE_PATH,
                        StandardLocation.SYSTEM_MODULES,
                        StandardLocation.NATIVE_HEADER_OUTPUT);
        assertThat(ProvenanceJavac.declaredLocations(
                        new String[] {"--source-path", "a", "--processor-path", "c", "--module-path", "e"}))
                .containsExactlyInAnyOrder(
                        StandardLocation.SOURCE_PATH,
                        StandardLocation.ANNOTATION_PROCESSOR_PATH,
                        StandardLocation.MODULE_PATH);
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

        StandardJavaFileManager fm = take(StandardCharsets.UTF_8, Set.of());
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

        StandardJavaFileManager fm = take(StandardCharsets.UTF_8, Set.of());
        assertThat(compileAgainst(fm, dir, out, dep, "v")).isTrue();

        deleteTree(dep);
        Files.createDirectories(dep);

        assertThat(compileAgainst(fm, dir, out, dep, "v")).isFalse();
    }

    /**
     * javac caches a classpath entry it found missing until the manager closes. A sibling's output
     * directory that a compile named before it existed (a guard suite compiled ahead of the fixtures
     * it lists) must resolve in the next compile that names it once it is there.
     */
    @Test
    void a_classpath_directory_created_since_a_compile_found_it_missing_is_seen(@TempDir Path dir) throws Exception {
        Path dep = dir.resolve("dep");
        Path out = Files.createDirectories(dir.resolve("out"));
        String[] options = {"-classpath", dep.toString()};

        StandardJavaFileManager before =
                take(StandardCharsets.UTF_8, Set.of(), ProvenanceJavac.pathEntriesNamed(options), "");
        assertThat(compileAgainst(before, dir, out, dep, "v")).isFalse();

        Files.createDirectories(dep);
        buildDep(dir, dep, "public int v() { return 1; }");
        StandardJavaFileManager after =
                take(StandardCharsets.UTF_8, Set.of(), ProvenanceJavac.pathEntriesNamed(options), "");

        assertThat(after).isNotSameAs(before);
        assertThat(compileAgainst(after, dir, out, dep, "v")).isTrue();
        assertThat(take(StandardCharsets.UTF_8, Set.of(), ProvenanceJavac.pathEntriesNamed(options), ""))
                .as("a class written into a directory that exists keeps the manager")
                .isSameAs(after);
    }

    /**
     * javac opens a multi-release jar with the view of the release of the compile that first opened
     * it and keeps that view: a compile at another release gets a manager of its own.
     */
    @Test
    void a_compile_at_another_release_gets_a_fresh_manager() {
        String at11 = ProvenanceJavac.releaseOf(new String[] {"--release", "11"});
        StandardJavaFileManager first = take(StandardCharsets.UTF_8, Set.of(), List.of(), at11);
        assertThat(take(StandardCharsets.UTF_8, Set.of(), List.of(), ProvenanceJavac.releaseOf(new String[] {
                    "--release=11"
                })))
                .as("the same release in its other spelling keeps the manager")
                .isSameAs(first);
        assertThat(take(StandardCharsets.UTF_8, Set.of(), List.of(), ProvenanceJavac.releaseOf(new String[] {
                    "--release", "21"
                })))
                .isNotSameAs(first);
    }

    @Test
    void release_options_are_read_in_every_spelling() {
        assertThat(ProvenanceJavac.releaseOf(new String[] {"-d", "out", "--release", "17", "-g"}))
                .isEqualTo("--release=17 ");
        assertThat(ProvenanceJavac.releaseOf(new String[] {"-source", "11", "-target=11"}))
                .isEqualTo("-source=11 -target=11 ");
        assertThat(ProvenanceJavac.releaseOf(new String[] {"-d", "out"})).isEmpty();
    }

    /** Checks a manager out for one compile that names {@code entries}; the caller returns it. */
    private ReusedJavacFileManager.Lease checkout(List<Path> entries) {
        return pool.checkout(JAVAC, StandardCharsets.UTF_8, d -> {}, Set.of(), entries, "");
    }

    /** The manager a compile would get, returned to the pool at once as a finished compile does. */
    private StandardJavaFileManager take(Charset encoding, Set<StandardLocation> declared) {
        return take(encoding, declared, List.of(), "");
    }

    private StandardJavaFileManager take(
            Charset encoding, Set<StandardLocation> declared, List<Path> entries, String release) {
        try (var lease = pool.checkout(JAVAC, encoding, d -> {}, declared, entries, release)) {
            return lease.manager();
        }
    }

    /** The system javac, with every manager it builds recording itself in {@code closed} when closed. */
    private static JavaCompiler closeRecording(List<StandardJavaFileManager> closed) {
        return (JavaCompiler) Proxy.newProxyInstance(
                JavaCompiler.class.getClassLoader(), new Class<?>[] {JavaCompiler.class}, (proxy, method, args) -> {
                    Object result = invoke(method, JAVAC, args);
                    if (!method.getName().equals("getStandardFileManager")) return result;
                    StandardJavaFileManager real = (StandardJavaFileManager) result;
                    StandardJavaFileManager[] self = new StandardJavaFileManager[1];
                    self[0] = (StandardJavaFileManager) Proxy.newProxyInstance(
                            StandardJavaFileManager.class.getClassLoader(),
                            new Class<?>[] {StandardJavaFileManager.class},
                            (p, m, a) -> {
                                if (m.getName().equals("equals")) return p == a[0];
                                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                                if (m.getName().equals("close")) closed.add(self[0]);
                                return invoke(m, real, a);
                            });
                    return self[0];
                });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
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
