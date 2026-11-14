// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.PreflightMemoFixtures.writeProject;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dirty memo decides whether a module is scheduled at all. A value the build resolves from the
 * caller's environment and hands to a step whose key carries it (a plugin table's {@code env:NAME},
 * a declared {@code [env]} name) must make the memo miss when it changes, or the module is never
 * scheduled and the artifact made under the old value stays on disk.
 */
class DirtyMemoEnvTest {

    /** The android plugin's own schema example: the keystore path comes from the shell. */
    private static final String SIGNING_TABLE = """

            [android]
            namespace   = "t.app"
            compile-sdk = 34
            min-sdk     = 24

            [android.signing.release]
            store-file = "env:RELEASE_KEYSTORE"
            key-alias  = "upload"
            """;

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    private static Session withEnv(String name, String value) {
        return Session.defaults().withVariant(null, Map.of(name, value));
    }

    private static boolean hits(Session session, Path tmp, BuildGraph.Result graph, boolean skipTests)
            throws Exception {
        return SessionContext.where(
                session, () -> PreflightMemo.tryLoadDirty(tmp, graph, skipTests).isPresent());
    }

    private static void store(Session session, Path tmp, BuildGraph.Result graph, boolean skipTests) throws Exception {
        SessionContext.where(session, () -> {
            PreflightMemo.storeDirty(
                    tmp,
                    graph,
                    skipTests,
                    Set.of(),
                    PreflightMemo.snapshotFingerprints(graph, skipTests).fingerprints());
            return null;
        });
    }

    private static BuildGraph.Result withTable(Path tmp, String table) throws Exception {
        writeProject(tmp);
        Files.writeString(tmp.resolve("jk.toml"), Files.readString(tmp.resolve("jk.toml")) + table);
        return BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
    }

    @Test
    void a_changed_env_value_a_plugin_signing_table_names_misses_the_memo(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = withTable(tmp, SIGNING_TABLE);
        store(withEnv("RELEASE_KEYSTORE", "/keys/debug.jks"), tmp, graph, false);
        assertThat(hits(withEnv("RELEASE_KEYSTORE", "/keys/debug.jks"), tmp, graph, false))
                .isTrue();

        assertThat(hits(withEnv("RELEASE_KEYSTORE", "/keys/release.jks"), tmp, graph, false))
                .as("the packager key carries the resolved store-file; the memo must miss with it")
                .isFalse();
    }

    @Test
    void a_changed_dot_env_value_a_plugin_signing_table_names_misses_the_memo(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = withTable(tmp, SIGNING_TABLE);
        Files.writeString(tmp.resolve(".env"), "RELEASE_KEYSTORE=/keys/debug.jks\n");
        store(Session.defaults(), tmp, graph, true);
        assertThat(hits(Session.defaults(), tmp, graph, true)).isTrue();

        Files.writeString(tmp.resolve(".env"), "RELEASE_KEYSTORE=/keys/release-keystore.jks\n");
        assertThat(hits(Session.defaults(), tmp, graph, true))
                .as("a .env value the signing table names reaches the packager; the memo must miss with it")
                .isFalse();
    }

    @Test
    void a_plugin_value_with_no_env_reference_keys_no_environment(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = withTable(tmp, SIGNING_TABLE.replace("env:RELEASE_KEYSTORE", "/keys/fixed.jks"));
        store(withEnv("RELEASE_KEYSTORE", "/keys/debug.jks"), tmp, graph, true);
        assertThat(hits(withEnv("RELEASE_KEYSTORE", "/keys/release.jks"), tmp, graph, true))
                .as("a literal store-file reads no variable, so the variable's value cannot move the row")
                .isTrue();
    }

    @Test
    void a_changed_declared_env_value_misses_the_memo_with_tests_skipped(@TempDir Path tmp) throws Exception {
        BuildGraph.Result graph = withTable(tmp, """

                [env]
                optional = ["GEN_MODE"]
                """);
        store(withEnv("GEN_MODE", "A"), tmp, graph, true);
        assertThat(hits(withEnv("GEN_MODE", "A"), tmp, graph, true)).isTrue();

        assertThat(hits(withEnv("GEN_MODE", "B"), tmp, graph, true))
                .as("[env] reaches processors and plugin steps of a -S build; the memo must miss with it")
                .isFalse();
    }
}
