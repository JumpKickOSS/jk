// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.EnvLookup;
import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.forge.ForgeAuth;
import cc.jumpkick.forge.TokenStore;
import cc.jumpkick.repo.MavenSettings;
import cc.jumpkick.repo.RepoCredentialResolver;
import cc.jumpkick.repo.RepoCredentialStore;
import cc.jumpkick.run.TestFailureInfo;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A known secret value must never appear in free-form engine output (wire/journal) or in
 * stamp/cache key material that lands under {@code target/}.
 *
 * <p>Both sides of the redactor's boundary are asserted here, deliberately in one file: a value a
 * {@code .env} declares is masked, a repository credential jk itself resolved is masked, and a value
 * that is merely present in the environment is left alone. The first two are told to the redactor;
 * the third would have to be guessed at, which is the mode this design refuses.
 */
class EnvSecretRedactionTest {

    private static final String SECRET = "jk-1274-must-not-leak-s3cret-token";

    /** A repository token of the shape CI exports: nothing in the tree declares it. */
    private static final String REPO_TOKEN = "ci-only-nexus-bearer-token";

    @BeforeEach
    @AfterEach
    void forgetResolvedCredentials() {
        ResolvedSecrets.clear();
    }

    /**
     * The real resolver, with every collaborator but the environment pointed at a scratch dir. The
     * shell binds the name to {@code host}, as CI does beside the token, so the credential is sent.
     */
    private static RepoCredentialResolver resolver(Path scratch, String tokenValue, String host) {
        Function<String, @Nullable String> env = name -> "JK_REPO_NEXUS_TOKEN".equals(name) ? tokenValue : null;
        Function<String, @Nullable String> noEnv = k -> null;
        return new RepoCredentialResolver(
                env,
                MavenSettings.empty(),
                new RepoCredentialStore(scratch.resolve("creds")),
                new ForgeAuth(new TokenStore(scratch.resolve("tokens")), noEnv, argv -> Optional.empty()),
                (endpoint, field, token) -> Optional.empty(),
                name -> "JK_REPO_NEXUS_HOST".equals(name) ? host : null,
                List::of);
    }

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

        String redacted = requireNonNull(EventRedaction.redactEnv(tmp.toString(), "signing failed: " + SECRET));
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

        var red = requireNonNull(EventRedaction.redactFailure(tmp.toString(), f));

        assertThat(red.message()).doesNotContain(SECRET).contains(SecretRedactor.MASK);
        assertThat(red.stack()).doesNotContain(SECRET).contains(SecretRedactor.MASK);
        assertThat(red.className()).isEqualTo("FooTest");
        assertThat(red.exceptionClass()).isEqualTo(f.exceptionClass());

        // A failure with nothing to mask comes back as the same instance (no copy churn).
        var clean = new TestFailureInfo("g:a", "junit-jupiter", "FooTest", "bar()", "E", "m", "s");
        assertThat(EventRedaction.redactFailure(tmp.toString(), clean)).isSameAs(clean);
    }

    /**
     * Redaction follows the <em>declaration</em>, not the precedence winner. {@code .env} names the
     * secret; whichever layer supplies its effective value, that value is what reaches the wire and
     * that value is what gets masked. The reverse rule left the CI shape — {@code .env} default
     * plus an exported override — as the one case nothing masked.
     */
    @Test
    void a_dotenv_named_value_is_masked_even_when_the_shell_supplies_it(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve(".env"), "MODE=from-file\n");
        var env = EnvLookup.forModule(tmp, name -> "MODE".equals(name) ? "from-shell" : null);
        var r = SecretRedactor.from(env);
        assertThat(r.redact("mode=from-shell")).isEqualTo("mode=" + SecretRedactor.MASK);
        // The losing file value never resolves, so nothing prints it and nothing masks it.
        assertThat(r.redact("mode=from-file")).isEqualTo("mode=from-file");
    }

    /** A name no {@code .env} mentions stays untouched — the redactor cannot enumerate the shell. */
    @Test
    void an_undeclared_environment_value_is_left_alone(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve(".env"), "MODE=from-file\n");
        var env = EnvLookup.forModule(tmp, name -> "HOME".equals(name) ? "/home/developer" : null);
        assertThat(SecretRedactor.from(env).redact("home=/home/developer")).isEqualTo("home=/home/developer");
    }

    /**
     * The CI shape: no {@code .env} anywhere in the tree, the credential arriving only as
     * {@code JK_REPO_NEXUS_TOKEN}. The declaration-based redactor cannot see it — and it is masked
     * anyway, because the resolver that produced it said so. The journal row is the assertion that
     * matters: a token masked on the wire and written raw to disk has not been redacted.
     */
    @Test
    void a_resolved_repository_credential_is_masked_on_the_wire_and_in_the_journal(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        assertThat(Files.exists(project.resolve(".env")))
                .as("nothing in this tree declares the credential")
                .isFalse();

        RepoCredential resolved = SessionContext.where(
                Session.defaults().withWorkingDir(project), () -> resolver(tmp, REPO_TOKEN, "nexus.example.com")
                        .resolve("nexus", URI.create("https://nexus.example.com/repo/"), Optional.empty()));
        assertThat(resolved).isEqualTo(new RepoCredential.Bearer(REPO_TOKEN));

        String worker = "worker failed: PUT https://nexus.example.com/repo/ -> 401 " + "(sent Authorization: Bearer "
                + REPO_TOKEN + ")";
        String onTheWire = requireNonNull(EventRedaction.redactEnv(project.toString(), worker));
        assertThat(onTheWire).doesNotContain(REPO_TOKEN).contains(SecretRedactor.MASK);

        Path journalRoot = tmp.resolve("journal");
        BuildJournal journal = new BuildJournal(journalRoot);
        BuildRecord record = new BuildRecord(
                null,
                1L,
                BuildRecord.SCHEMA,
                "publish",
                project.toString(),
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
                List.of(new BuildRecord.Diag("error", project.toString(), "publish", "upload", onTheWire, "", "")),
                "cli",
                null,
                null,
                null,
                false,
                null,
                0L);
        assertThat(journal.append(record, new BuildJournal.Snapshot(null, null, onTheWire + "\n")))
                .isNotNull();

        try (var walk = Files.walk(journalRoot)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(p)).as("journal file %s", p).doesNotContain(REPO_TOKEN);
            }
        }
    }

    /**
     * The engine is resident and serves many projects at once, so the collection is scoped to the
     * workspace that resolved it. Two sessions running side by side: A's token is masked in A's
     * output and invisible to B's redactor.
     */
    @Test
    void one_session_credential_never_reaches_another_sessions_redactor(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        String tokenA = "session-a-nexus-token";
        String tokenB = "session-b-nexus-token";

        AtomicReference<@Nullable String> aSawItsOwn = new AtomicReference<>();
        AtomicReference<@Nullable String> bSawSessionAs = new AtomicReference<>();
        Runnable inA = () -> {
            resolver(a, tokenA, "a.example").resolve("nexus", URI.create("https://a.example/repo/"), Optional.empty());
            aSawItsOwn.set(EventRedaction.redactEnv(a.toString(), "401 for " + tokenA));
        };
        Runnable inB = () -> {
            resolver(b, tokenB, "b.example").resolve("nexus", URI.create("https://b.example/repo/"), Optional.empty());
            bSawSessionAs.set(EventRedaction.redactEnv(b.toString(), "401 for " + tokenA));
        };
        Thread ta = new Thread(() -> SessionContext.runWhere(Session.defaults().withWorkingDir(a), inA), "session-a");
        Thread tb = new Thread(() -> SessionContext.runWhere(Session.defaults().withWorkingDir(b), inB), "session-b");
        ta.start();
        tb.start();
        ta.join();
        tb.join();

        assertThat(aSawItsOwn.get()).isEqualTo("401 for " + SecretRedactor.MASK);
        assertThat(bSawSessionAs.get())
                .as("B has no business knowing A's credential, so B cannot mask it either")
                .isEqualTo("401 for " + tokenA);
        assertThat(EventRedaction.redactEnv(b.toString(), "401 for " + tokenB))
                .isEqualTo("401 for " + SecretRedactor.MASK);
    }
}
