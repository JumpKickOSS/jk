// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.forge.ForgeAuth;
import cc.jumpkick.forge.ForgeIdentity;
import cc.jumpkick.forge.TokenStore;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.task.RunNotices;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code ${cmd:...}} credential in the user's own config runs its helper, and the refresher a
 * refusing repository calls runs it again; the same reference in a project manifest is an error.
 */
class RepoCredentialHelperTest {

    private static final URI GAR = URI.create("https://us-maven.pkg.dev/acme/maven/");

    @AfterEach
    void forget() {
        RunNotices.clear();
    }

    /** A helper that prints {@code token-<n>}, counting its runs in a file beside it. */
    private static Path countingHelper(Path dir) throws IOException {
        Path script = dir.resolve("helper.sh");
        Files.writeString(script, """
                #!/bin/sh
                n=$(cat "%1$s/count" 2>/dev/null || echo 0)
                n=$((n + 1))
                echo "$n" > "%1$s/count"
                echo "token-$n"
                """.formatted(dir));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    private static RepoCredentialResolver resolver(Path dir, List<RepositorySpec> user) {
        return new RepoCredentialResolver(
                Map.<String, String>of()::get,
                MavenSettings.empty(),
                new RepoCredentialStore(dir.resolve("store")),
                new ForgeAuth(new TokenStore(dir.resolve("tokens")), k -> null, argv -> Optional.empty()),
                (ForgeIdentity) (endpoint, field, token) -> Optional.empty(),
                Map.<String, String>of()::get,
                () -> user);
    }

    @Test
    void a_user_config_helper_credential_resolves_and_its_refresher_runs_the_helper_again(@TempDir Path dir)
            throws IOException {
        Path helper = countingHelper(dir);
        var declared = new RepoCredential.Bearer("${cmd:" + helper + "}");
        var r = resolver(dir, List.of(new RepositorySpec("gar", GAR, declared)));

        RepoCredential first = r.resolve("gar", GAR, Optional.of(declared));
        assertThat(first).isEqualTo(new RepoCredential.Bearer("token-1"));
        assertThat(r.resolve("gar", GAR, Optional.of(declared))).as("cached").isEqualTo(first);

        var refresh = Objects.requireNonNull(r.refresher("gar", GAR, Optional.of(declared), first));
        assertThat(refresh.get()).isEqualTo(new RepoCredential.Bearer("token-2"));
        assertThat(Files.readString(dir.resolve("count")).strip()).isEqualTo("2");
    }

    @Test
    void a_credential_no_helper_printed_has_no_refresher(@TempDir Path dir) {
        var r = resolver(dir, List.of());
        var literal = new RepoCredential.Bearer("a-literal-token");

        assertThat(r.refresher("gar", GAR, Optional.of(literal), literal)).isNull();
    }

    @Test
    void a_project_helper_reference_is_an_error_not_a_run(@TempDir Path dir) throws IOException {
        Path helper = countingHelper(dir);
        var declared = new RepoCredential.Bearer("${cmd:" + helper + "}");
        var r = resolver(dir, List.of());

        assertThatThrownBy(() -> r.resolve("gar", GAR, Optional.of(declared)))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("${cmd:} works only in ~/.jk/config.toml");
        assertThat(dir.resolve("count")).doesNotExist();
    }
}
