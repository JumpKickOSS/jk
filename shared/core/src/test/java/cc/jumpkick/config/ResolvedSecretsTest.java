// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A credential jk resolved itself is masked because jk <em>said</em> it was one — never because a
 * name looked like a secret. The filing is per workspace, so a resident engine cannot carry one
 * project's token into another project's redactor.
 */
class ResolvedSecretsTest {

    private static final String TOKEN = "jk-2481-resolved-nexus-token";

    @BeforeEach
    @AfterEach
    void forget() {
        ResolvedSecrets.clear();
    }

    @Test
    void a_recorded_value_is_masked_for_its_workspace_and_only_there(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        ResolvedSecrets.recordFor(a, TOKEN);

        assertThat(ResolvedSecrets.plus(a, SecretRedactor.none()).redact("Bearer " + TOKEN))
                .isEqualTo("Bearer " + SecretRedactor.MASK);
        assertThat(ResolvedSecrets.plus(b, SecretRedactor.none()).redact("Bearer " + TOKEN))
                .as("another project's build must never inherit this token")
                .isEqualTo("Bearer " + TOKEN);
    }

    /**
     * The two sets stay separate in the owner and merge only at the redactor: a {@code .env} value
     * and a resolved credential are both masked, and the {@code .env} contract is untouched — a
     * name the file never declared is still left alone.
     */
    @Test
    void the_env_redactor_is_widened_by_composition_not_by_guessing(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve(".env"), "DECLARED=declared-file-value\n");
        ResolvedSecrets.recordFor(tmp, TOKEN);

        SecretRedactor merged = ResolvedSecrets.plus(tmp, BuildEnv.secretsFor(tmp));

        assertThat(merged.redact("token=" + TOKEN)).isEqualTo("token=" + SecretRedactor.MASK);
        assertThat(merged.redact("declared=declared-file-value")).isEqualTo("declared=" + SecretRedactor.MASK);
        assertThat(merged.redact("home=/home/developer")).isEqualTo("home=/home/developer");
    }

    /** A module directory and its workspace root are one scope, so an event's dir and the request's agree. */
    @Test
    void a_module_directory_files_under_its_workspace_root(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Path module = Files.createDirectories(root.resolve("libs/core"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "root"
                version = "0.1.0"

                [workspace]
                modules = ["libs/core"]
                """);

        ResolvedSecrets.recordFor(root, TOKEN);

        assertThat(ResolvedSecrets.plus(module, SecretRedactor.none()).redact("Bearer " + TOKEN))
                .isEqualTo("Bearer " + SecretRedactor.MASK);
    }

    /** {@link #record} files against the session on this thread — that is what makes it request-scoped. */
    @Test
    void record_files_against_the_current_session(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        SessionContext.where(Session.defaults().withWorkingDir(a), () -> {
            ResolvedSecrets.record(TOKEN);
            return null;
        });

        assertThat(ResolvedSecrets.plus(a, SecretRedactor.none()).redact(TOKEN)).isEqualTo(SecretRedactor.MASK);
        assertThat(ResolvedSecrets.plus(b, SecretRedactor.none()).redact(TOKEN)).isEqualTo(TOKEN);
    }

    /** The same floor {@code SecretRedactor} applies: below it a "credential" masks ordinary text. */
    @Test
    void a_value_under_the_minimum_length_is_not_filed(@TempDir Path tmp) {
        ResolvedSecrets.recordFor(tmp, "abc");
        assertThat(ResolvedSecrets.plus(tmp, SecretRedactor.none()).isEmpty()).isTrue();
        ResolvedSecrets.recordFor(tmp, null);
        assertThat(ResolvedSecrets.plus(tmp, SecretRedactor.none()).isEmpty()).isTrue();
    }
}
