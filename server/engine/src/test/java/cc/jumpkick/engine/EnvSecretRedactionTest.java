// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.EnvLookup;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.run.TestFailureInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A known {@code.env} value must never appear in free-form engine output (wire/journal)
 * or in stamp/cache key material that lands under {@code target/}.
 */
class EnvSecretRedactionTest {

    private static final String SECRET = "jk-1274-must-not-leak-s3cret-token";

    @Test
    void redactEnv_masks_file_sourced_values(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve(".env"), "TOKEN=" + SECRET + "\n");
        String raw = "failed to auth with token " + SECRET + " against mirror";
        assertThat(EventRedaction.redactEnv(tmp.toString(), raw))
                .isEqualTo("failed to auth with token " + SecretRedactor.MASK + " against mirror");
        assertThat(EventRedaction.redactEnv(tmp.toString(), raw)).doesNotContain(SECRET);
    }

    @Test
    void journal_diagnostics_never_persist_a_raw_env_secret(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve(".env"), "TOKEN=" + SECRET + "\n");
        Path journalRoot = tmp.resolve("journal");
        BuildJournal journal = new BuildJournal(journalRoot);

        String redacted = EventRedaction.redactEnv(tmp.toString(), "signing failed: " + SECRET);
        BuildRecord record = new BuildRecord(
                null,
                1L,
                BuildRecord.SCHEMA,
                "build",
                tmp.toString(),
                "g:a",
                null /* projectId */,
                1_700_000_000_000L,
                1_700_000_000_100L,
                100L,
                false,
                false,
                1,
                "9.9",
                null,
                List.of(),
                List.of(),
                List.of(new BuildRecord.Diag("error", tmp.toString(), "package", "sign", redacted, "", "")),
                "cli",
                null,
                null,
                false,
                null,
                0L);

        String id = journal.append(record, new BuildJournal.Snapshot(null, null, redacted + "\n"));
        assertThat(id).isNotNull();

        // Walk everything the journal wrote — the secret must not appear on disk.
        try (var walk = Files.walk(journalRoot)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String body = Files.readString(p);
                assertThat(body).as("journal file %s", p).doesNotContain(SECRET);
                String name = p.getFileName().toString();
                // Only diagnostic-bearing files must include the mask; identity/run-number do not.
                if (name.equals("record.json") || name.equals(BuildJournal.DIAGNOSTICS_TXT)) {
                    assertThat(body).as("journal file %s", p).contains(SecretRedactor.MASK);
                }
            }
        }
    }

    @Test
    void redactFailure_masks_message_and_stack(@TempDir Path tmp) throws Exception {
        // printStackTrace text repeats the raw message on its first line, so masking message
        // alone still leaks the secret through the stack field.
        Files.writeString(tmp.resolve(".env"), "TOKEN=" + SECRET + "\n");
        var f = new TestFailureInfo(
                "g:a",
                "junit-jupiter",
                "FooTest",
                "bar()",
                "org.opentest4j.AssertionFailedError",
                "expected " + SECRET,
                "org.opentest4j.AssertionFailedError: expected " + SECRET + "\n\tat FooTest.bar(FooTest.java:9)");

        var red = EventRedaction.redactFailure(tmp.toString(), f);

        assertThat(red.message()).doesNotContain(SECRET).contains(SecretRedactor.MASK);
        assertThat(red.stack()).doesNotContain(SECRET).contains(SecretRedactor.MASK);
        assertThat(red.className()).isEqualTo("FooTest");
        assertThat(red.exceptionClass()).isEqualTo(f.exceptionClass());

        // A failure with nothing to mask comes back as the same instance (no copy churn).
        var clean = new TestFailureInfo("g:a", "junit-jupiter", "FooTest", "bar()", "E", "m", "s");
        assertThat(EventRedaction.redactFailure(tmp.toString(), clean)).isSameAs(clean);
    }

    @Test
    void real_environment_values_are_not_masked_even_when_named_in_dotenv(@TempDir Path tmp) throws Exception {
        // Source-based masking: a real env var that shadows.env is not a secret.
        Files.writeString(tmp.resolve(".env"), "MODE=from-file\n");
        // Simulate via SecretRedactor directly (same rule as BuildEnv when the shell wins).
        var env = EnvLookup.forModule(tmp, name -> "MODE".equals(name) ? "from-shell" : null);
        var r = SecretRedactor.from(env);
        assertThat(r.redact("mode=from-shell")).isEqualTo("mode=from-shell");
        assertThat(r.redact("mode=from-file")).isEqualTo("mode=from-file");
    }
}
