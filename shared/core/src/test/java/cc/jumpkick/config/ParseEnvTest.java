// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1269: {@code ${VAR}} must resolve through a supplied environment, not the ambient process.
 *
 * <p>The build's authoritative parse runs inside a long-lived engine, so reading
 * {@code System.getenv} there meant {@code FOO=x jk build} had no effect on {@code [repositories]}
 * interpolation while variant selection — handed the caller's {@code clientEnv} — did see it. And
 * because the parse is memoized on (path, size, mtime), whichever caller parsed first pinned its
 * interpolation for everyone after it.
 */
class ParseEnvTest {

    /** The interpolated basic-auth username of the single declared repository. */
    private static String basicUser(cc.jumpkick.model.JkBuild build) {
        var cred = build.repositories().get(0).credential().orElseThrow();
        return ((cc.jumpkick.credential.RepoCredential.Basic) cred).username();
    }

    private static Path manifest(Path dir, String url) throws Exception {
        Path file = dir.resolve("jk.toml");
        Files.writeString(
                file,
                """
                [project]
                group   = "com.example"
                name    = "m"
                version = "1.0.0"

                [repositories]
                internal = { url = "%s", username = "${REPO_USER}", password = "${REPO_PASS}" }
                """
                        .formatted(url));
        return file;
    }

    @Test
    void interpolation_reads_the_supplied_environment(@TempDir Path tmp) throws Exception {
        Path file = manifest(tmp, "https://repo.example/maven");
        Map<String, String> env = Map.of("REPO_USER", "alice", "REPO_PASS", "s3cret");

        var build = JkBuildParser.parse(file, env::get);

        assertThat(build.repositories()).hasSize(1);
        assertThat(basicUser(build)).isEqualTo("alice");
    }

    @Test
    void a_changed_environment_re_parses_instead_of_serving_the_first_callers_values(@TempDir Path tmp)
            throws Exception {
        Path file = manifest(tmp, "https://repo.example/maven");

        var first = JkBuildParser.parse(file, Map.of("REPO_USER", "alice", "REPO_PASS", "p1")::get);
        assertThat(basicUser(first)).isEqualTo("alice");

        // Same bytes, same mtime — only the environment differs. The memoized parse must not win.
        var second = JkBuildParser.parse(file, Map.of("REPO_USER", "bob", "REPO_PASS", "p2")::get);
        assertThat(basicUser(second)).isEqualTo("bob");
    }

    @Test
    void a_manifest_without_interpolation_still_hits_the_cache(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("jk.toml");
        Files.writeString(
                file,
                """
                [project]
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                """);
        // A parse that consulted nothing must not be invalidated by an unrelated env change —
        // otherwise every manifest becomes env-sensitive and the cache stops earning its keep.
        AtomicReference<Integer> lookups = new AtomicReference<>(0);
        java.util.function.UnaryOperator<String> counting = name -> {
            lookups.updateAndGet(n -> n + 1);
            return null;
        };
        JkBuildParser.parse(file, counting);
        int afterFirst = lookups.get();
        JkBuildParser.parse(file, counting);
        assertThat(lookups.get()).as("second parse should be served from cache").isEqualTo(afterFirst);
    }

    @Test
    void an_unset_variable_is_an_error_not_an_empty_string(@TempDir Path tmp) throws Exception {
        Path file = manifest(tmp, "https://repo.example/maven");
        // Silent emptiness is how a build "succeeds" against the wrong registry.
        assertThatThrownBy(() -> JkBuildParser.parse(file, name -> null))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("REPO_USER");
    }

    @Test
    void unset_becoming_set_invalidates_the_cached_parse(@TempDir Path tmp) throws Exception {
        Path file = manifest(tmp, "https://repo.example/maven");
        assertThatThrownBy(() -> JkBuildParser.parse(file, name -> null))
                .isInstanceOf(JkBuildParseException.class);

        // The failed parse must not have poisoned the cache, and the now-set value must be seen.
        var build = JkBuildParser.parse(file, Map.of("REPO_USER", "carol", "REPO_PASS", "p")::get);
        assertThat(basicUser(build)).isEqualTo("carol");
    }
}
