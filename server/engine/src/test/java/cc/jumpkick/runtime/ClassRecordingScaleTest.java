// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.test.ClassCones;
import cc.jumpkick.test.ClassReads;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite of jk-engine's shape is recorded, shadowed and replayed inside a small heap: a thousand
 * test classes with long names over four thousand workspace classes, four test JVMs, ten classes
 * that each read the same thirty thousand files and the rest a few each, loading classes as they go.
 * The class records stay bounded (no row past a few kilobytes, the suite's line holding no class's
 * content), the second run over the same tree would replay every class, and the heap the recording
 * took stays within the bound memory admission keeps for it. Holding every class's reads and every
 * row whole killed a 256 MiB engine after jk-cli's suite.
 */
@Tag("integration")
class ClassRecordingScaleTest {

    static final int TESTS = 1_000;
    static final int MAINS = 3_000;
    static final int FILES = 30_000;
    static final int SCANNERS = 10;
    static final int JVMS = 4;
    static final String PKG = "cc/jumpkick/runtime/scale/with/a/rather/long/package/name/for/realism";

    /** The test runner's window event, as the recording names it. */
    @Name(ClassReads.WINDOW_EVENT)
    @StackTrace(false)
    static final class Window extends Event {
        @Nullable
        String testClass;
    }

    /** A file read as the JDK's own event names it. */
    @Name("jdk.FileRead")
    @StackTrace(false)
    static final class Read extends Event {
        @Nullable
        String path;
    }

    @Test
    @Timeout(600)
    void a_suite_of_jk_engines_shape_is_recorded_shadowed_and_replayed_in_a_small_heap(@TempDir Path tmp)
            throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path module = Files.createDirectories(ws.resolve("m"));
        Path tests = Files.createDirectories(module.resolve("target/test-classes"));
        Path mains = Files.createDirectories(module.resolve("target/classes"));
        for (int i = 0; i < MAINS; i++) {
            classFile(
                    mains,
                    PKG + "/Main" + i,
                    List.of(PKG + "/Main" + ((i * 7 + 1) % MAINS), PKG + "/Main" + ((i + 13) % MAINS)));
        }
        for (int i = 0; i < TESTS; i++) {
            classFile(
                    tests,
                    PKG + "/ScaleTest" + i,
                    List.of(PKG + "/Main" + (i % MAINS), PKG + "/Main" + ((i * 3) % MAINS)));
        }
        Path data = Files.createDirectories(ws.resolve("data"));
        for (int i = 0; i < FILES; i++) Files.writeString(data.resolve("f" + i + ".txt"), "x");
        Path recordings = Files.createDirectories(tmp.resolve("rec"));
        assertThat(child(tmp, "-Xmx256m", "make", recordings, ws)).contains("exit=0", "made");

        String first = child(tmp, "-Xmx" + HEAP_MB + "m", "record", recordings, ws);
        assertThat(first).contains("exit=0", "classes=" + TESTS, "wouldRun=" + TESTS, "unsound=0");
        String second = child(tmp, "-Xmx" + HEAP_MB + "m", "record", recordings, ws);
        assertThat(second)
                .as("nothing changed: every class would replay")
                .contains("exit=0", "wouldRun=0", "unsound=0");

        long peak = Long.parseLong(second.replaceAll("(?s).*peak=(\\d+).*", "$1"));
        long rowMax = Long.parseLong(second.replaceAll("(?s).*rowMax=(\\d+).*", "$1"));
        long header = Long.parseLong(second.replaceAll("(?s).*header=(\\d+).*", "$1"));
        long size = Long.parseLong(second.replaceAll("(?s).*size=(\\d+).*", "$1"));
        long live = Long.parseLong(second.replaceAll("(?s).*live=(\\d+).*", "$1"));
        System.out.println(
                "scale: peak heap " + (peak >> 20) + " MiB, live after " + (live >> 20) + " MiB, record file "
                        + (size >> 10) + " KiB, longest row " + rowMax + " chars, suite line " + header + " chars");
        assertThat(rowMax).as("a row is bounded, whatever its class read").isLessThan(8_192);
        assertThat(header).as("the suite's line holds no class's content").isLessThan(8_192);
        assertThat(size).as("the record file is bounded by the class count").isLessThan(TESTS * 8_192L);
    }

    /** The child's heap: the reader's peak, the recording's bound, the index, and room for the JVM itself. */
    static final long HEAP_MB = 64;

    private static void classFile(Path root, String internalName, List<String> refs) throws IOException {
        byte[] bytes = ClassFile.of().build(ClassDesc.ofInternalName(internalName), cb -> {
            int n = 0;
            for (String r : refs) {
                cb.withField("f" + n++, ClassDesc.ofInternalName(r), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC);
            }
        });
        Path f = root.resolve(internalName + ".class");
        Files.createDirectories(f.getParent());
        Files.write(f, bytes);
    }

    /** Run {@link Child} in a fresh JVM; its exit and output, without the name of a heap error. */
    private static String child(Path tmp, String heap, String mode, Path recordings, Path ws) throws Exception {
        Path log = Files.createTempFile(tmp, mode, ".log");
        Process p = new ProcessBuilder(List.of(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        heap,
                        "-XX:+ExitOnOutOfMemoryError",
                        "-cp",
                        System.getProperty("java.class.path"),
                        Child.class.getName(),
                        mode,
                        recordings.toString(),
                        ws.toString()))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertThat(p.waitFor(500, TimeUnit.SECONDS)).as(mode + " in time").isTrue();
        } finally {
            p.destroyForcibly();
        }
        return ("exit=" + p.exitValue() + " " + Files.readString(log))
                .replace("OutOfMemoryError", "out-of-memory error");
    }

    /** {@code make}: write the recordings; {@code record}: attribute, shadow and record them. */
    static final class Child {
        public static void main(String[] args) throws IOException {
            Path recordings = Path.of(args[1]);
            Path ws = Path.of(args[2]);
            if (args[0].equals("make")) {
                make(recordings, ws);
                System.out.println("made");
                return;
            }
            record(recordings, ws);
        }

        private static void make(Path recordings, Path ws) throws IOException {
            Path module = ws.resolve("m");
            String tests = module.resolve("target/test-classes").toString();
            String mains = module.resolve("target/classes").toString();
            String data = ws.resolve("data").toString();
            for (int jvm = 0; jvm < JVMS; jvm++) {
                try (Recording r = new Recording()) {
                    r.enable("jdk.FileRead").withThreshold(Duration.ZERO);
                    r.enable(ClassReads.WINDOW_EVENT).withThreshold(Duration.ZERO);
                    r.start();
                    for (int i = jvm; i < TESTS; i += JVMS) {
                        Window w = new Window();
                        w.testClass = (PKG + "/ScaleTest" + i).replace('/', '.');
                        w.begin();
                        read(tests + "/" + PKG + "/ScaleTest" + i + ".class");
                        for (int k = 0; k < 6; k++)
                            read(mains + "/" + PKG + "/Main" + ((i * 6 + k) % MAINS) + ".class");
                        if (i < SCANNERS) {
                            for (int f = 0; f < FILES; f++) read(data + "/f" + f + ".txt");
                        } else {
                            for (int f = 0; f < 3; f++) read(data + "/f" + ((i * 3 + f) % FILES) + ".txt");
                        }
                        w.commit();
                    }
                    r.stop();
                    r.dump(recordings.resolve("jvm-" + jvm + ".jfr"));
                }
            }
        }

        private static void read(String path) {
            Read read = new Read();
            read.path = path;
            read.commit();
        }

        private static void record(Path recordings, Path ws) throws IOException {
            Path module = ws.resolve("m");
            Path tests = module.resolve("target/test-classes");
            Path mains = module.resolve("target/classes");
            ClassReads.Attributed seen = ClassReads.attribute(
                    recordings, module, new ClassReads.Layout(List.of(tests, mains), List.of(), Set.of(), ws, null));
            Map<String, TestClassLedger.Entry> recorded = new TreeMap<>();
            for (int i = 0; i < TESTS; i++) {
                recorded.put(
                        "|" + (PKG + "/ScaleTest" + i).replace('/', '.'),
                        new TestClassLedger.Entry(5, 0, 0, 1_000, 20));
            }
            Map<String, TestClassRecords.Outcome> ran = TestClassRecords.outcomes(recorded);
            Path file = TestClassRecords.file(module);
            ClassShadow shadow = new ClassShadow(
                    ClassCones.index(List.of(tests, mains), ClassRecording.INDEX_CLASSES),
                    "suite",
                    ws,
                    ClassShadow.pooledOf(TestClassRecords.header(file)),
                    ClassShadow.observed(seen.pooled(), ws),
                    "reads",
                    "reads",
                    "stamp");
            TestClassRecords.Lookups lookups = TestClassRecords.Lookups.none();
            String header = TestClassRecords.header("g:m", seen, lookups, ws, null);
            TestClassRecords.merge(module, header, ran, (o, prev) -> {
                ClassReads.Observation obs = seen.classes().get(o.testClass());
                return TestClassRecords.row(o, obs, lookups, ws, shadow.of(o, obs, prev));
            });
            long[] rowMax = {0};
            TestClassRecords.forEachRow(file, (key, row) -> rowMax[0] = Math.max(rowMax[0], row.length()));
            String head = TestClassRecords.header(file);
            Runtime rt = Runtime.getRuntime();
            System.gc();
            long live = rt.totalMemory() - rt.freeMemory();
            long peak = 0;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null)
                    peak += pool.getPeakUsage().getUsed();
            }
            System.out.println("complete=" + seen.complete() + " classes="
                    + seen.classes().size() + " wouldRun="
                    + shadow.wouldRun() + " unsound=" + shadow.unsound().size() + " rowMax=" + rowMax[0] + " header="
                    + (head == null ? 0 : head.length()) + " size=" + Files.size(file) + " peak=" + peak + " live="
                    + live + " more="
                    + Jsonl.bool(TestClassRecords.rows(file).values().iterator().next(), "readsMore", false));
        }
    }
}
