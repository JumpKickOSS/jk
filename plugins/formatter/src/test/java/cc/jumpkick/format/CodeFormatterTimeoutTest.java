// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run with one file that never comes back.
 *
 * <p>The stand-in blocks instead of exploding a real formatter's line-break search, because what is
 * under test is the run: a file nothing can stop must not take the other files, the stream, or the
 * worker's exit with it. Every case here is also a hang if the bound is missing, so the class
 * carries its own deadline.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CodeFormatterTimeoutTest {

    @TempDir
    Path dir;

    /** Held for the whole run: the stand-in waits on it exactly the way a wedged formatter would. */
    private final CountDownLatch wedged = new CountDownLatch(1);

    @Test
    void a_file_that_outlasts_the_timeout_is_reported_by_path_and_the_run_finishes() throws Exception {
        var spec = spec("Fast1.java", "Wedged.java", "Fast2.java");
        Emissions out = new Emissions();

        CodeFormatter.Tally tally = CodeFormatter.formatAll(spec, out.writer(), null, work());

        assertThat(tally.errors()).isEqualTo(1);
        assertThat(tally.clean()).isEqualTo(2);
        // Spec order holds even though the middle file settled last.
        assertThat(out.statuses()).containsExactly("clean", "error", "clean");
        assertThat(out.lineFor("Wedged.java")).contains("\"status\":\"error\"").contains("timed out after");
    }

    @Test
    void the_files_behind_a_wedged_one_still_report_their_own_verdicts() throws Exception {
        var spec = spec("Wedged.java", "Fast1.java", "Fast2.java", "Fast3.java");
        Emissions out = new Emissions();

        CodeFormatter.Tally tally = CodeFormatter.formatAll(spec, out.writer(), null, work());

        assertThat(tally.clean()).isEqualTo(3);
        assertThat(out.statuses()).containsExactly("error", "clean", "clean", "clean");
    }

    @Test
    void a_file_in_flight_past_the_warn_threshold_is_named_before_its_verdict() throws Exception {
        var spec = spec("Wedged.java");
        Emissions out = new Emissions();

        CodeFormatter.formatAll(spec, out.writer(), null, work());

        List<String> slow = out.linesWithStatus("slow");
        assertThat(slow).isNotEmpty();
        assertThat(slow.getFirst()).contains("Wedged.java").contains("still formatting after");
        // The chatter came first; the verdict is what closed the file out.
        assertThat(out.statuses()).endsWith("error");
    }

    @Test
    void a_file_that_no_thread_will_ever_start_is_settled_rather_than_waited_on() throws Exception {
        // One slot buys one replacement, so two wedged files leave nothing to run the third on.
        var spec = spec("Wedged1.java", "Wedged2.java", "Queued.java");
        FormatStampCache memo = new FormatStampCache(dir.resolve("stamps"), CONFIG_KEY);
        Emissions out = new Emissions();

        CodeFormatter.Tally tally = CodeFormatter.formatAll(spec, out.writer(), memo, work());

        assertThat(tally.errors()).isEqualTo(3);
        assertThat(out.lineFor("Queued.java")).contains("was left wedged by a file that timed out");
        // It was never attempted, so there is nothing to remember about it: a memo here would make
        // the next run refuse a file that has never been tried.
        Path queued = spec.files.get(2).file().toPath();
        assertThat(memo.timedOutAt(memo.keyFor(Files.readAllBytes(queued)))).isZero();
    }

    /** Nesting deep enough to account for a stall — the shape the memo and the message both key on. */
    private static final String NESTED = """
            class Wedged {
                Object o = a(b(c(d(e(f(g(h(i(j(k -> k))))))))));
            }
            """;

    @Test
    void a_timeout_message_carries_the_post_mortem_when_the_source_explains_it() throws Exception {
        var spec = spec("Wedged.java");
        Files.writeString(dir.resolve("Wedged.java"), NESTED, StandardCharsets.UTF_8);
        Emissions out = new Emissions();

        CodeFormatter.formatAll(spec, out.writer(), null, work());

        assertThat(out.lineFor("Wedged.java")).contains("deepest expression nesting here is");
    }

    /**
     * A file that finishes just after the run gave up on it is still reported as timed out. Whether
     * a task beats a poll by a few milliseconds is not something a user could reason about, and by
     * then the run has already replaced its slot and named the file.
     */
    @Test
    void a_file_that_comes_back_late_is_still_the_file_that_timed_out() throws Exception {
        var spec = spec("Late.java");
        Emissions out = new Emissions();

        CodeFormatter.Tally tally = CodeFormatter.formatAll(spec, out.writer(), null, (ref, index, dog) -> {
            try (var window = dog.watch(index, ref.file())) {
                holdFor(spec.fileTimeoutMs + 150);
                return new CodeFormatter.FileResult(ref.file(), "changed", null);
            }
        });

        assertThat(tally.changed()).isZero();
        assertThat(tally.errors()).isEqualTo(1);
        assertThat(out.lineFor("Late.java")).contains("timed out after");
    }

    /**
     * The run remembers what it gave up on, keyed on the bytes it gave up on — so the next run
     * reports the file without spending the limit again. {@code FormatTimeoutMemoTest} covers the
     * reading side.
     */
    @Test
    void a_timed_out_file_whose_shape_explains_it_is_remembered_for_the_next_run() throws Exception {
        var spec = spec("Wedged.java");
        Files.writeString(dir.resolve("Wedged.java"), NESTED, StandardCharsets.UTF_8);
        FormatStampCache memo = new FormatStampCache(dir.resolve("stamps"), CONFIG_KEY);
        Emissions out = new Emissions();

        CodeFormatter.formatAll(spec, out.writer(), memo, work());

        Path file = spec.files.getFirst().file().toPath();
        assertThat(memo.timedOutAt(memo.keyFor(Files.readAllBytes(file)))).isEqualTo(spec.fileTimeoutMs);
    }

    /**
     * An ordinary file that blew the limit is far more likely a host that stalled for a moment than a
     * source nothing can format. Remembering that would refuse a good file on every later run until
     * somebody edited it — much worse than paying the limit again.
     */
    @Test
    void a_timed_out_file_of_ordinary_shape_is_not_remembered() throws Exception {
        var spec = spec("Wedged.java");
        FormatStampCache memo = new FormatStampCache(dir.resolve("stamps"), CONFIG_KEY);
        Emissions out = new Emissions();

        CodeFormatter.Tally tally = CodeFormatter.formatAll(spec, out.writer(), memo, work());

        assertThat(tally.errors()).isEqualTo(1);
        Path file = spec.files.getFirst().file().toPath();
        assertThat(memo.timedOutAt(memo.keyFor(Files.readAllBytes(file)))).isZero();
        assertThat(memo.timeoutCount()).isZero();
        // And it says so, with the one knob that answers both remaining explanations: a very large
        // file, or a very slow host. A bare "timed out" answers neither.
        assertThat(out.lineFor("Wedged.java"))
                .contains("nothing about this file's shape explains that")
                .contains("jk.format.file-timeout-ms");
    }

    @Test
    void a_zero_timeout_leaves_the_run_unbounded() throws Exception {
        var spec = spec("Fast1.java", "Fast2.java");
        spec.fileWarnMs = 0;
        spec.fileTimeoutMs = 0;
        Emissions out = new Emissions();

        CodeFormatter.Tally tally = CodeFormatter.formatAll(spec, out.writer(), null, work());

        assertThat(tally.clean()).isEqualTo(2);
        assertThat(out.linesWithStatus("slow")).isEmpty();
    }

    /** Lets the wedged stand-ins unwind once the assertions are done with them. */
    @AfterEach
    void release() {
        wedged.countDown();
    }

    /**
     * The stand-in for one file's formatting: instant for every file but {@code Wedged*.java}, which
     * waits on a latch nothing in the run releases <em>and</em> ignores its interrupt — the property
     * that matters, since a line-break search deep in native-speed code never checks for one.
     */
    private CodeFormatter.FileWork work() {
        return (ref, index, dog) -> {
            try (var window = dog.watch(index, ref.file())) {
                boolean wedges = ref.file().getName().startsWith("Wedged");
                while (wedges && wedged.getCount() > 0) {
                    try {
                        wedged.await();
                    } catch (InterruptedException e) {
                        /* not this stand-in's cue, exactly as it is not a break search's */
                    }
                }
                return new CodeFormatter.FileResult(ref.file(), "clean", null);
            }
        };
    }

    /** Hold this thread for {@code ms}, ignoring interrupts, the way a break search would. */
    private static void holdFor(long ms) {
        long until = Clock.SYSTEM.nanos() + ms * 1_000_000L;
        long left;
        while ((left = until - Clock.SYSTEM.nanos()) > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(left);
            } catch (InterruptedException e) {
                /* not this stand-in's cue */
            }
        }
    }

    private static final String CONFIG_KEY = "2222222222222222222222222222222222222222222222222222222222222222";

    /** A one-slot run with short thresholds, over files that exist so the post-mortem can read them. */
    private CodeFormatter.Spec spec(String... names) throws Exception {
        var spec = new CodeFormatter.Spec();
        spec.threads = 1;
        spec.fileWarnMs = 50;
        spec.fileTimeoutMs = 300;
        for (String name : names) {
            Path p = dir.resolve(name);
            Files.writeString(p, "class " + name.replace(".java", "") + " {}\n", StandardCharsets.UTF_8);
            spec.files.add(new CodeFormatter.FileRef(CodeFormatter.Kind.JAVA, p.toFile()));
        }
        return spec;
    }

    /** Captures the {@code ##JKFMT:} stream a run writes, in emission order. */
    private static final class Emissions {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final ProtocolWriter writer =
                new ProtocolWriter(new PrintStream(bytes, true, StandardCharsets.UTF_8), "##JKFMT:");

        ProtocolWriter writer() {
            return writer;
        }

        List<String> lines() {
            String text = bytes.toString(StandardCharsets.UTF_8);
            return text.isBlank() ? List.of() : List.of(text.strip().split("\n"));
        }

        /** Per-file verdicts in emission order; the in-flight {@code slow} chatter is not one. */
        List<String> statuses() {
            List<String> out = new ArrayList<>();
            for (String line : lines()) {
                String status = status(line);
                if (!"slow".equals(status)) out.add(status);
            }
            return out;
        }

        List<String> linesWithStatus(String status) {
            List<String> out = new ArrayList<>();
            for (String line : lines()) {
                if (status.equals(status(line))) out.add(line);
            }
            return out;
        }

        /** The last line about {@code name} — a slow file's verdict follows its chatter. */
        String lineFor(String name) {
            String found = "";
            for (String line : lines()) {
                if (line.contains(File.separator + name + "\"") || line.contains(name + "\"")) found = line;
            }
            return found;
        }

        private static String status(String line) {
            int at = line.indexOf("\"status\":\"");
            if (at < 0) return "";
            int from = at + "\"status\":\"".length();
            return line.substring(from, line.indexOf('"', from));
        }
    }
}
