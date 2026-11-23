// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.test.ClassReads;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each class's record names it as a container path, says what its window held and what it looked
 * up, spells workspace paths relative to the workspace, keeps the platform evidence apart, stays
 * bounded whatever the class read, and survives a later run that did not run it.
 */
class TestClassRecordsTest {

    @Test
    void a_class_record_says_what_the_class_did_and_a_later_run_keeps_the_classes_it_did_not_run(@TempDir Path tmp)
            throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path module = Files.createDirectories(ws.resolve("m"));
        ClassReads.Observation reads = ClassReads.Observation.empty();
        reads.addRead(ws.resolve("data/orders.json"));
        reads.addOsPath(Path.of("/proc/self/status"));
        reads.addOsPath(Path.of("/etc/localtime"));
        reads.addLoaded("demo.Orders");
        reads.addNative("/opt/lib/libfast.so");
        ClassReads.Attributed seen = new ClassReads.Attributed(
                Map.of("demo.OrderTest", reads, "demo.PlainTest", ClassReads.Observation.empty()),
                ClassReads.Observation.empty(),
                true,
                1,
                4);
        TestClassRecords.Lookups lookups = TestClassRecords.Lookups.of(
                Map.of("demo.OrderTest", Set.of("LANG")), Map.of("demo.OrderTest", Set.of("user.home")));

        TestClassRecords.write(
                module,
                "g:m",
                Map.of(
                        "|demo.OrderTest", new TestClassLedger.Entry(2, 0, 0, 1_000, 40),
                        "|demo.OrderTest$Nested", new TestClassLedger.Entry(1, 0, 1, 1_000, 10),
                        "|demo.PlainTest", new TestClassLedger.Entry(1, 1, 0, 1_000, 5)),
                seen,
                lookups,
                ws,
                "toolchain:jdk:abc");

        Path file = TestClassRecords.file(module);
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(3);
        assertThat(Jsonl.str(lines.getFirst(), "kind")).isEqualTo("suite");
        assertThat(Jsonl.str(lines.getFirst(), "toolchain")).isEqualTo("toolchain:jdk:abc");
        assertThat(Jsonl.bool(lines.getFirst(), "platformBound", false)).isTrue();
        assertThat(lines.getFirst())
                .as("the suite's line holds no class's content")
                .doesNotContain("orders.json");
        String order = lines.get(1);
        assertThat(Jsonl.strArray(order, "container")).containsExactly("demo", "OrderTest");
        assertThat(Jsonl.str(order, "outcome")).isEqualTo("passed");
        assertThat(Jsonl.intValue(order, "tests", 0))
                .as("its nested class folded in")
                .isEqualTo(3);
        assertThat(Jsonl.strArray(order, "reads")).containsExactly("data/orders.json");
        assertThat(Jsonl.strArray(order, "osPaths")).contains("/etc/localtime");
        assertThat(Jsonl.strArray(order, "nativeCode")).containsExactly("/opt/lib/libfast.so");
        assertThat(Jsonl.strArray(order, "env")).containsExactly("LANG");
        assertThat(order).contains("\"props\":[\"user.home\"]").contains("\"loaded\":1");
        assertThat(Jsonl.str(lines.get(2), "outcome")).isEqualTo("failed");

        TestClassRecords.write(
                module,
                "g:m",
                Map.of("|demo.PlainTest", new TestClassLedger.Entry(1, 0, 0, 2_000, 5)),
                new ClassReads.Attributed(
                        Map.of("demo.PlainTest", ClassReads.Observation.empty()),
                        ClassReads.Observation.empty(),
                        true,
                        1,
                        0),
                TestClassRecords.Lookups.none(),
                ws,
                null);
        List<String> again = Files.readAllLines(file);
        assertThat(again).hasSize(3);
        assertThat(again.get(1))
                .as("a class this run did not run keeps its row")
                .isEqualTo(order);
        assertThat(Jsonl.str(again.get(2), "outcome")).isEqualTo("passed");
    }

    @Test
    void a_class_that_read_fifty_thousand_files_keeps_a_bounded_row(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path module = Files.createDirectories(ws.resolve("m"));
        ClassReads.Observation scanner = ClassReads.Observation.empty();
        for (int i = 0; i < 50_000; i++) scanner.addRead(ws.resolve("src/f" + i + ".java"));
        for (int i = 0; i < 100; i++) scanner.addOsPath(Path.of("/proc/" + i + "/stat"));
        assertThat(scanner.reads()).hasSize(ClassReads.READS_KEPT);
        assertThat(scanner.readsMore()).isTrue();
        TestClassRecords.write(
                module,
                "g:m",
                Map.of("|demo.ScannerTest", new TestClassLedger.Entry(1, 0, 0, 1_000, 5)),
                new ClassReads.Attributed(Map.of("demo.ScannerTest", scanner), scanner, true, 1, 50_100),
                TestClassRecords.Lookups.none(),
                ws,
                null);
        List<String> lines = Files.readAllLines(TestClassRecords.file(module));
        assertThat(lines.get(1)).contains("\"readsMore\":true");
        assertThat(lines).allSatisfy(l -> assertThat(l.length()).isLessThan(4_096));
    }

    @Test
    void a_row_past_the_bound_is_skipped_and_the_rest_read() throws Exception {
        String huge = "x".repeat(TestClassRecords.MAX_ROW_CHARS + 10);
        StringReader in = new StringReader("first\n" + huge + "\nlast\n");
        assertThat(TestClassRecords.bounded(in)).isEqualTo("first");
        assertThat(TestClassRecords.bounded(in)).isEmpty();
        assertThat(TestClassRecords.bounded(in)).isEqualTo("last");
        assertThat(TestClassRecords.bounded(in)).isNull();
    }

    @Test
    void a_java_class_is_a_container_path_and_back() {
        assertThat(TestClassRecords.container("a.b.C$D")).containsExactly("a.b", "C", "D");
        assertThat(TestClassRecords.className(List.of("a.b", "C", "D"))).isEqualTo("a.b.C$D");
        assertThat(TestClassRecords.container("Top")).containsExactly("", "Top");
        assertThat(TestClassRecords.className(List.of("", "Top"))).isEqualTo("Top");
    }

    @Test
    void a_siblings_class_tree_beside_its_jar_loads_classes_by_name(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path sibling = Files.createDirectories(ws.resolve("sdk/target"));
        Path jar = Files.write(sibling.resolve("sdk-1.0.jar"), new byte[] {1});
        Path tree = Files.createDirectories(sibling.resolve("classes"));
        Path library =
                Files.write(Files.createDirectories(tmp.resolve("store")).resolve("lib-1.0.jar"), new byte[] {2});
        Path tests = ws.resolve("m/target/test-classes");
        Path main = ws.resolve("m/target/classes");

        TestClassRecording.Roots roots = TestClassRecording.Roots.of(ws, tests, main, List.of(jar, library));

        assertThat(roots.classTrees()).containsExactly(tests, main, tree);
        assertThat(roots.cone())
                .as("the jar holds the same classes for the cone")
                .containsExactly(tests, main, jar);
        assertThat(roots.external()).containsExactly(library);
    }
}
