// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.test.ClassReads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each class's record names it as a container path, says what its window held and what it looked
 * up, spells workspace paths relative to the workspace, keeps the platform evidence apart, and
 * survives a later run that did not run it.
 */
class TestClassRecordsTest {

    @Test
    void a_class_record_says_what_the_class_did_and_a_later_run_keeps_the_classes_it_did_not_run(@TempDir Path tmp)
            throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path module = Files.createDirectories(ws.resolve("m"));
        ClassReads.Observation reads = observation();
        reads.reads().add(ws.resolve("data/orders.json"));
        reads.osPaths().add(Path.of("/proc/self/status"));
        reads.loaded().add("demo.Orders");
        reads.nativeCode().add("/opt/lib/libfast.so");
        ClassReads.Attributed seen = new ClassReads.Attributed(
                Map.of("demo.OrderTest", reads, "demo.PlainTest", observation()), observation(), true, 1, 4);
        TestClassRecords.Lookups lookups = new TestClassRecords.Lookups(
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
        String order = lines.get(1);
        assertThat(Jsonl.strArray(order, "container")).containsExactly("demo", "OrderTest");
        assertThat(Jsonl.str(order, "outcome")).isEqualTo("passed");
        assertThat(Jsonl.intValue(order, "tests", 0))
                .as("its nested class folded in")
                .isEqualTo(3);
        assertThat(Jsonl.strArray(order, "reads")).containsExactly("data/orders.json");
        assertThat(Jsonl.strArray(order, "osPaths")).containsExactly("/proc/self/status");
        assertThat(Jsonl.strArray(order, "nativeCode")).containsExactly("/opt/lib/libfast.so");
        assertThat(Jsonl.strArray(order, "env")).containsExactly("LANG");
        assertThat(order).contains("\"props\":[\"user.home\"]").contains("\"loaded\":[\"demo.Orders\"]");
        assertThat(Jsonl.str(lines.get(2), "outcome")).isEqualTo("failed");

        TestClassRecords.write(
                module,
                "g:m",
                Map.of("|demo.PlainTest", new TestClassLedger.Entry(1, 0, 0, 2_000, 5)),
                new ClassReads.Attributed(Map.of("demo.PlainTest", observation()), observation(), true, 1, 0),
                new TestClassRecords.Lookups(Map.of(), Map.of()),
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
    void a_java_class_is_a_container_path_and_back() {
        assertThat(TestClassRecords.container("a.b.C$D")).containsExactly("a.b", "C", "D");
        assertThat(TestClassRecords.className(List.of("a.b", "C", "D"))).isEqualTo("a.b.C$D");
        assertThat(TestClassRecords.container("Top")).containsExactly("", "Top");
        assertThat(TestClassRecords.className(List.of("", "Top"))).isEqualTo("Top");
    }

    private static ClassReads.Observation observation() {
        return new ClassReads.Observation(
                new TreeSet<>(),
                new TreeSet<>(),
                new TreeSet<>(),
                new TreeSet<>(),
                new TreeSet<>(),
                new TreeSet<>(),
                new TreeSet<>());
    }
}
