// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The streaming reader takes from a recording what the JDK's own parser takes: every field of every
 * wanted event, in file order, across chunks, whatever the string's encoding (inline Latin-1, UTF-8,
 * the chunk's string pool, empty, null), and the JDK's own file events by path.
 */
class RecordingReaderTest {

    @Name("jk.test.Sample")
    @StackTrace(false)
    static final class Sample extends Event {
        @Nullable
        String text;

        int number;
        long big;
        boolean flag;
        double ratio;
        float part;
        short small;
        byte tiny;
        char letter;

        @Nullable
        Class<?> type;

        @Nullable
        Thread thread;
    }

    private static final List<String> FIELDS =
            List.of("text", "number", "big", "flag", "ratio", "part", "small", "tiny", "letter", "missing");

    @Test
    void reads_what_the_jdk_parser_reads(@TempDir Path tmp) throws Exception {
        Path data = Files.writeString(tmp.resolve("data.txt"), "x");
        Path jfr = tmp.resolve("r.jfr");
        String repeated = "a string long enough for the pool " + tmp;
        try (Recording r = new Recording()) {
            r.enable("jk.test.Sample").withThreshold(Duration.ZERO);
            r.enable("jdk.FileRead").withThreshold(Duration.ZERO).withStackTrace();
            r.enable("jdk.FileWrite").withThreshold(Duration.ZERO);
            r.start();
            String[] texts = {"short", "", null, "ünïcödé ✓ 日本", repeated, repeated, "x".repeat(300)};
            for (int i = 0; i < 300; i++) {
                if (i % 100 == 50) {
                    // another recording starting and stopping rotates the chunk
                    try (Recording rotate = new Recording()) {
                        rotate.start();
                    }
                }
                Sample s = new Sample();
                s.text = texts[i % texts.length];
                s.number = i * 7 - 1000;
                s.big = Long.MIN_VALUE + i;
                s.flag = i % 3 == 0;
                s.ratio = i / 7.0;
                s.part = i / 3.0f;
                s.small = (short) -i;
                s.tiny = (byte) i;
                s.letter = (char) ('a' + i % 26);
                s.type = String.class;
                s.thread = Thread.currentThread();
                s.commit();
                Files.readString(data);
                Files.writeString(tmp.resolve("out-" + (i % 5)), "y");
            }
            r.stop();
            r.dump(jfr);
        }

        List<String> expected = new ArrayList<>();
        try (RecordingFile in = new RecordingFile(jfr)) {
            while (in.hasMoreEvents()) {
                RecordedEvent e = in.readEvent();
                String name = e.getEventType().getName();
                if (name.equals("jk.test.Sample")) {
                    expected.add(name + " " + e.getString("text") + " " + e.getInt("number") + " "
                            + e.getLong("big") + " " + e.getBoolean("flag") + " " + e.getDouble("ratio") + " "
                            + e.getFloat("part") + " " + e.getShort("small") + " " + e.getByte("tiny") + " "
                            + (long) e.getChar("letter") + " null");
                } else if (name.equals("jdk.FileRead") || name.equals("jdk.FileWrite")) {
                    expected.add(name + " " + e.getString("path"));
                }
            }
        }
        List<String> actual = new ArrayList<>();
        RecordingReader.read(
                jfr,
                Map.of("jk.test.Sample", FIELDS, "jdk.FileRead", List.of("path"), "jdk.FileWrite", List.of("path")),
                (name, at, end, v) -> {
                    if (name.equals("jk.test.Sample")) {
                        actual.add(name + " " + String.join(" ", stringsOf(v)));
                    } else {
                        actual.add(name + " " + v[0]);
                    }
                });
        assertThat(expected).hasSizeGreaterThanOrEqualTo(900);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void an_event_s_start_and_end_are_the_jdk_parser_s(@TempDir Path tmp) throws Exception {
        Path jfr = tmp.resolve("t.jfr");
        try (Recording r = new Recording()) {
            r.enable("jk.test.Sample").withThreshold(Duration.ZERO);
            r.start();
            for (int i = 0; i < 20; i++) {
                Sample s = new Sample();
                s.begin();
                s.text = "w" + i;
                Thread.sleep(2);
                s.commit();
            }
            r.stop();
            r.dump(jfr);
        }
        List<long[]> expected = new ArrayList<>();
        try (RecordingFile in = new RecordingFile(jfr)) {
            while (in.hasMoreEvents()) {
                RecordedEvent e = in.readEvent();
                if (!e.getEventType().getName().equals("jk.test.Sample")) continue;
                expected.add(new long[] {nanos(e.getStartTime()), nanos(e.getEndTime())});
            }
        }
        List<long[]> actual = new ArrayList<>();
        RecordingReader.read(
                jfr,
                Map.of("jk.test.Sample", List.of("text")),
                (n, start, end, v) -> actual.add(new long[] {start, end}));
        assertThat(actual).hasSameSizeAs(expected).hasSize(20);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(actual.get(i)[0]).isCloseTo(expected.get(i)[0], offset(1_000L));
            assertThat(actual.get(i)[1]).isCloseTo(expected.get(i)[1], offset(1_000L));
            assertThat(actual.get(i)[1] - actual.get(i)[0]).isGreaterThanOrEqualTo(1_000_000L);
        }
    }

    private static long nanos(Instant t) {
        return t.getEpochSecond() * 1_000_000_000L + t.getNano();
    }

    @Test
    void a_cut_recording_is_refused_not_misread(@TempDir Path tmp) throws Exception {
        Path jfr = tmp.resolve("r.jfr");
        try (Recording r = new Recording()) {
            r.enable("jk.test.Sample").withThreshold(Duration.ZERO);
            r.start();
            for (int i = 0; i < 1000; i++) {
                Sample s = new Sample();
                s.text = "t" + i;
                s.commit();
            }
            r.stop();
            r.dump(jfr);
        }
        byte[] whole = Files.readAllBytes(jfr);
        Path cut = Files.write(tmp.resolve("cut.jfr"), Arrays.copyOf(whole, whole.length / 2));
        assertThatThrownBy(
                        () -> RecordingReader.read(cut, Map.of("jk.test.Sample", List.of("text")), (n, a, e, v) -> {}))
                .isInstanceOf(IOException.class);
        Path junk =
                Files.writeString(tmp.resolve("junk.jfr"), "not a recording at all, but long enough to hold a header?");
        assertThatThrownBy(() -> RecordingReader.read(junk, Map.of(), (n, a, e, v) -> {}))
                .isInstanceOf(IOException.class);
    }

    private static List<String> stringsOf(@Nullable String[] values) {
        List<String> out = new ArrayList<>();
        for (String v : values) out.add(Objects.toString(v));
        return out;
    }
}
