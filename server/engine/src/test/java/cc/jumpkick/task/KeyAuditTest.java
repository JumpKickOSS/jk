// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.KeyAuditMode;
import cc.jumpkick.config.Request;
import cc.jumpkick.run.JkThreads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The key audit's own rules: what it holds a step to, and how it names what moved. */
class KeyAuditTest {

    @TempDir
    Path tmp;

    @Test
    void a_key_taken_again_from_a_file_the_build_wrote_is_reported_with_the_token_that_moved() throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        Path report = module.resolve("report.txt");
        KeyAudit audit = new KeyAudit(KeyAuditMode.STRICT);
        List<KeyAudit.Moved> moved = Request.detached()
                .with(KeyAudit.SLOT, audit)
                .call(() -> {
                    KeyAudit.KeySource key = () -> {
                        KeyAudit.tap("src/A.java:" + "a".repeat(64));
                        KeyAudit.tap(Files.exists(report) ? "report.txt:" + "b".repeat(64) : "report.txt missing");
                        return Files.exists(report) ? "k2" : "k1";
                    };
                    KeyAudit.used(module, "run-tests", key.key(), key);
                    // The step's own build writes a file its key reads.
                    Files.writeString(report, "ran");
                    return audit.verify();
                });
        assertThat(moved).singleElement().satisfies(m -> {
            assertThat(m.step()).isEqualTo("run-tests");
            assertThat(m.module()).isEqualTo(module.toAbsolutePath().normalize());
            assertThat(m.tokens()).extracting(KeyAudit.Token::name).containsExactly("report.txt", "report.txt missing");
            assertThat(m.describe())
                    .startsWith("the key of run-tests moved after its own build: k1 -> k2; ")
                    .contains("report.txt appeared (bbbbbbbbbbbb)")
                    .contains("report.txt missing went away")
                    .endsWith("; the next build runs it again");
        });
    }

    @Test
    void a_key_that_holds_is_no_finding_and_a_later_registration_replaces_an_earlier() throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        KeyAudit audit = new KeyAudit(KeyAuditMode.WARN);
        AtomicReference<String> current = new AtomicReference<>("before");
        List<KeyAudit.Moved> moved = Request.detached()
                .with(KeyAudit.SLOT, audit)
                .call(() -> {
                    KeyAudit.used(module, "node-build", "before", current::get);
                    // Stored again under the key the next build takes: the audit holds the step to that one.
                    current.set("after");
                    KeyAudit.used(module, "node-build", "after", current::get);
                    return audit.verify();
                });
        assertThat(moved).isEmpty();
        assertThat(audit.size()).isEqualTo(1);
    }

    @Test
    void a_re_key_that_throws_is_reported_rather_than_read_as_a_pass() throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        KeyAudit audit = new KeyAudit(KeyAuditMode.STRICT);
        List<KeyAudit.Moved> moved = Request.detached()
                .with(KeyAudit.SLOT, audit)
                .call(() -> {
                    KeyAudit.keyed(module, "plugin-aot", "k", Map.of(), () -> {
                        throw new IllegalStateException("context gone");
                    });
                    return audit.verify();
                });
        assertThat(moved)
                .singleElement()
                .satisfies(m -> assertThat(m.describe()).contains("the re-key").contains("failed: context gone"));
    }

    @Test
    void off_a_request_that_audits_nothing_is_registered_or_tapped() throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        assertThat(KeyAudit.on()).isFalse();
        KeyAudit.used(module, "compile-main", "k", () -> {
            throw new AssertionError("an unaudited build derives no second key");
        });
        KeyAudit.tap("ignored");
    }

    @Test
    void hashed_lines_become_named_tokens() {
        assertThat(KeyAudit.lines(
                        "",
                        List.of(
                                "src:a/B.java:" + "c".repeat(64),
                                "guards=" + "d".repeat(64),
                                "skip-node=false",
                                "mirrored:excluded-unless-rewritten")))
                .containsEntry("src:a/B.java", "c".repeat(64))
                .containsEntry("guards", "d".repeat(64))
                .containsEntry("skip-node", "false")
                .containsEntry("mirrored:excluded-unless-rewritten", "");
    }

    @Test
    void close_drops_every_registration() throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        KeyAudit audit = new KeyAudit(KeyAuditMode.WARN);
        Request.detached().with(KeyAudit.SLOT, audit).run(() -> KeyAudit.used(module, "ksp", "k", () -> "k"));
        assertThat(audit.size()).isEqualTo(1);
        audit.close();
        assertThat(audit.size()).isZero();
    }

    @Test
    void a_tap_carried_onto_a_pool_lane_from_a_thread_that_audits_nothing_is_a_no_op() throws Exception {
        // The propagator carries the tap's absence as a binding to nothing: every javac key on a
        // pool lane taps, audited or not.
        JkThreads.cpu()
                .submit(() -> {
                    KeyAudit.tap("cp:" + "e".repeat(64));
                    KeyAudit.tapLines("task:compile-main@1\njk:0.51");
                    return null;
                })
                .get();
    }
}
