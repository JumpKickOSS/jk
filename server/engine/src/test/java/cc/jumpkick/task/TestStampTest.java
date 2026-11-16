// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The test-skip freshness key: it must stay stable when nothing changed, and bust when the module's
 * own main code, a test source, a dependency's content or the toolchain identity changed; the
 * lock reaches it only through the jars a module resolves.
 */
class TestStampTest {

    private static Path write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    @Test
    void stable_when_nothing_changes(@TempDir Path dir) throws IOException {
        Path testSrc = write(dir.resolve("FooTest.java"), "class FooTest {}");
        Path mainClasses = Files.createDirectories(dir.resolve("classes/main"));
        write(mainClasses.resolve("Foo.class"), "AAAA");
        Path sibJar = write(dir.resolve("dep/target/dep.jar"), "DEPBYTES");

        String k1 = TestStamp.computeKey(
                List.of(testSrc), mainClasses, List.of(), List.of(sibJar), List.of("jk:1.0", "runner:abc"));
        String k2 = TestStamp.computeKey(
                List.of(testSrc), mainClasses, List.of(), List.of(sibJar), List.of("jk:1.0", "runner:abc"));
        assertThat(k1).isNotNull().isEqualTo(k2);
    }

    @Test
    void own_main_change_busts_the_key(@TempDir Path dir) throws IOException {
        Path testSrc = write(dir.resolve("FooTest.java"), "class FooTest {}");
        Path mainClasses = Files.createDirectories(dir.resolve("classes/main"));
        Path mainClass = write(mainClasses.resolve("Foo.class"), "AAAA");

        String before = TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(), List.of(), List.of());
        Files.writeString(mainClass, "BBBB"); // main code changed; test source untouched
        String after = TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(), List.of(), List.of());

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void projected_main_fingerprint_matches_on_disk_tree(@TempDir Path dir) throws IOException {
        Path testSrc = write(dir.resolve("FooTest.java"), "class FooTest {}");
        Path mainClasses = Files.createDirectories(dir.resolve("classes/main"));
        write(mainClasses.resolve("Foo.class"), "AAAA");

        String live = TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(), List.of(), List.of());
        String projected = ClasspathFingerprint.entry(mainClasses);
        String viaOverride =
                TestStamp.computeKey(List.of(testSrc), mainClasses, projected, List.of(), List.of(), List.of());
        assertThat(viaOverride).isEqualTo(live);

        // Wiped classes + missing: token must NOT match the live green key.
        Path wiped = dir.resolve("classes/wiped");
        String missing = ClasspathFingerprint.entry(wiped);
        assertThat(missing).startsWith("missing:");
        String afterCleanWrong = TestStamp.computeKey(List.of(testSrc), wiped, List.of(), List.of(), List.of());
        assertThat(afterCleanWrong).isNotEqualTo(live);
        // Same wipe with the projected fingerprint recovers the green key.
        String afterCleanProjected =
                TestStamp.computeKey(List.of(testSrc), wiped, projected, List.of(), List.of(), List.of());
        assertThat(afterCleanProjected).isEqualTo(live);
    }

    @Test
    void dependency_content_change_busts_but_identical_rebuild_does_not(@TempDir Path dir) throws IOException {
        Path testSrc = write(dir.resolve("FooTest.java"), "class FooTest {}");
        Path mainClasses = Files.createDirectories(dir.resolve("classes/main"));
        Path sibJar = write(dir.resolve("dep/target/dep.jar"), "DEP-V1");

        String base = TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(), List.of(sibJar), List.of());

        // jk re-jars every build: identical bytes, new file mtime → must NOT bust.
        Files.writeString(sibJar, "DEP-V1");
        Files.setLastModifiedTime(sibJar, FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        assertThat(TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(), List.of(sibJar), List.of()))
                .as("byte-identical sibling rebuild keeps the key")
                .isEqualTo(base);

        // A real content change in the dependency → must bust (retest the dependent).
        Files.writeString(sibJar, "DEP-V2");
        assertThat(TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(), List.of(sibJar), List.of()))
                .as("dependency content change busts the dependent's key")
                .isNotEqualTo(base);
    }

    @Test
    void test_source_and_extras_changes_bust_the_key(@TempDir Path dir) throws IOException {
        Path testSrc = write(dir.resolve("FooTest.java"), "class FooTest {}");
        Path mainClasses = Files.createDirectories(dir.resolve("classes/main"));

        String base = TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(), List.of(), List.of("jk:1.0"));

        Files.writeString(testSrc, "class FooTest { void t() {} }");
        assertThat(TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(), List.of(), List.of("jk:1.0")))
                .isNotEqualTo(base);

        Files.writeString(testSrc, "class FooTest {}"); // restore
        assertThat(TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(), List.of(), List.of("jk:2.0")))
                .as("a toolchain/runner identity change retests")
                .isNotEqualTo(base);
    }

    /**
     * The workspace's one {@code jk-lock.toml} is no stamp input: a member's tests are keyed on the
     * jars they run with, so a dependency another member bumped leaves them replayed (JK-VW9QQ),
     * and a bump that reaches this member's runtime classpath re-runs them through those jars.
     */
    @Test
    void the_lock_keys_a_suite_only_through_the_jars_it_resolves(@TempDir Path dir) throws IOException {
        Path testSrc = write(dir.resolve("FooTest.java"), "class FooTest {}");
        Path mainClasses = Files.createDirectories(dir.resolve("classes/main"));
        Path lock = write(
                dir.resolve("jk-lock.toml"),
                "[[library]]\ncoordinate = \"org.postgresql:postgresql\"\nversion = \"42.7.14\"\n");
        Path driver = write(dir.resolve("repo/postgresql-42.7.14.jar"), "DRIVER-42.7.14");
        Path junit = write(dir.resolve("repo/junit.jar"), "JUNIT");

        String core = TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(), List.of(junit), List.of());
        String service =
                TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(), List.of(junit, driver), List.of());

        // service bumps the driver: the lock moves, and so does the jar service runs with.
        Files.writeString(lock, "[[library]]\ncoordinate = \"org.postgresql:postgresql\"\nversion = \"42.7.11\"\n");
        Path older = write(dir.resolve("repo/postgresql-42.7.11.jar"), "DRIVER-42.7.11");
        assertThat(TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(), List.of(junit), List.of()))
                .as("a member whose classpath does not hold the driver stays replayed")
                .isEqualTo(core);
        assertThat(TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(), List.of(junit, older), List.of()))
                .as("the member that runs with the driver re-runs")
                .isNotEqualTo(service);
    }

    /**
     * The test javac configuration reaches the stamp through the test compile's action key: an
     * edit to {@code [javac.test]} that recompiles the tests also re-runs them.
     */
    @Test
    void the_test_compile_key_is_a_stamp_input(@TempDir Path dir) throws IOException {
        Path testSrc = write(dir.resolve("FooTest.java"), "class FooTest {}");
        Path mainClasses = Files.createDirectories(dir.resolve("classes/main"));
        List<String> extras = List.of("jk:1.0");

        String bare = TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(), List.of(), extras);
        String underOneCompile = TestStamp.computeKey(
                List.of(testSrc),
                mainClasses,
                List.of(),
                List.of(),
                TestStamp.withCompileTest(extras, javac("compile-key-one")));
        String underAnotherCompile = TestStamp.computeKey(
                List.of(testSrc),
                mainClasses,
                List.of(),
                List.of(),
                TestStamp.withCompileTest(extras, javac("compile-key-two")));

        assertThat(underOneCompile).isNotEqualTo(bare).isNotEqualTo(underAnotherCompile);
        assertThat(TestStamp.withCompileTest(extras, TestStamp.CompileTestKeys.NONE))
                .as("a module with no test compile has no key and stamps as before")
                .isEqualTo(extras);
    }

    /**
     * The Kotlin and Groovy test compiles are stamp inputs on the same footing as javac's: a
     * changed kotlinc or groovyc key re-runs the tests, and a key under one language is not a key
     * under another.
     */
    @Test
    void a_changed_kotlin_or_groovy_test_compile_key_changes_the_stamp(@TempDir Path dir) throws IOException {
        Path testSrc = write(dir.resolve("FooTest.kt"), "class FooTest");
        Path mainClasses = Files.createDirectories(dir.resolve("classes/main"));
        List<String> extras = List.of("jk:1.0");

        String kotlinOne = key(testSrc, mainClasses, new TestStamp.CompileTestKeys(null, "kt-one", null));
        String kotlinTwo = key(testSrc, mainClasses, new TestStamp.CompileTestKeys(null, "kt-two", null));
        String groovyOne = key(testSrc, mainClasses, new TestStamp.CompileTestKeys(null, null, "kt-one"));
        String javacOne = key(testSrc, mainClasses, javac("kt-one"));
        String mixed = key(testSrc, mainClasses, new TestStamp.CompileTestKeys("j", "kt-one", null));

        assertThat(kotlinOne)
                .as("a changed Kotlin test-compile key changes the stamp")
                .isNotEqualTo(kotlinTwo);
        assertThat(kotlinOne)
                .as("the same key text under another language is another stamp")
                .isNotEqualTo(groovyOne)
                .isNotEqualTo(javacOne);
        assertThat(mixed).as("a mixed module folds every language's key").isNotEqualTo(kotlinOne);
        assertThat(TestStamp.withCompileTest(extras, new TestStamp.CompileTestKeys(null, "kt", "gv")))
                .containsExactlyInAnyOrder("jk:1.0", "compile-test-kotlin:kt", "compile-test-groovy:gv");
    }

    private static TestStamp.CompileTestKeys javac(String key) {
        return new TestStamp.CompileTestKeys(key, null, null);
    }

    private static String key(Path testSrc, Path mainClasses, TestStamp.CompileTestKeys keys) throws IOException {
        return Objects.requireNonNull(TestStamp.computeKey(
                List.of(testSrc),
                mainClasses,
                List.of(),
                List.of(),
                TestStamp.withCompileTest(List.of("jk:1.0"), keys)));
    }

    @Test
    void resource_fixture_change_busts_but_identical_rewrite_does_not(@TempDir Path dir) throws IOException {
        Path testSrc = write(dir.resolve("FooTest.java"), "class FooTest {}");
        Path mainClasses = Files.createDirectories(dir.resolve("classes/main"));
        Path resDir = dir.resolve("test/resources");
        Path fixture = write(resDir.resolve("fixture.json"), "{\"v\":1}");

        String base = TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(resDir), List.of(), List.of());

        // Byte-identical rewrite (new mtime) must NOT bust.
        Files.writeString(fixture, "{\"v\":1}");
        Files.setLastModifiedTime(fixture, FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        assertThat(TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(resDir), List.of(), List.of()))
                .isEqualTo(base);

        // A fixture-only edit must retestfalse green).
        Files.writeString(fixture, "{\"v\":2}");
        assertThat(TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(resDir), List.of(), List.of()))
                .as("resource-only edit busts the key")
                .isNotEqualTo(base);

        // A new fixture file must retest too.
        write(resDir.resolve("extra.txt"), "x");
        Files.writeString(fixture, "{\"v\":1}"); // restore original content
        assertThat(TestStamp.computeKey(List.of(testSrc), mainClasses, List.of(resDir), List.of(), List.of()))
                .as("added resource file busts the key")
                .isNotEqualTo(base);

        // Missing resource dir behaves like empty (no I/O failure, key stable).
        assertThat(TestStamp.computeKey(
                        List.of(testSrc),
                        mainClasses,
                        List.of(dir.resolve("integration/resources")),
                        List.of(),
                        List.of()))
                .isNotNull();
    }
}
