// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.Scope;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parse memo is stale when any catalog file it resolved short names through changes, not only
 * when {@code jk.toml} does: the project's {@code jk-libs.toml} and the global layer alike.
 */
class JkBuildParserCatalogMemoTest {

    private static final String MANIFEST = """
            group = "com.example"
            name = "app"
            version = "1.0.0"

            [dependencies]
            http = "1.0.0"
            """;

    @BeforeEach
    void startClean() {
        JkBuildParser.dropMemos();
    }

    @AfterEach
    void restoreHome() {
        System.clearProperty("jk.env.JK_HOME");
    }

    @Test
    void rewriting_jk_libs_toml_between_two_parses_yields_the_new_coordinate(@TempDir Path dir) throws IOException {
        Path toml = settled(dir.resolve("jk.toml"), MANIFEST);
        settled(dir.resolve("jk-libs.toml"), libs("com.acme:http-one"));
        assertThat(coordinate(toml)).isEqualTo("com.acme:http-one");

        settled(dir.resolve("jk-libs.toml"), libs("com.acme:http-two"));
        assertThat(coordinate(toml))
                .as("the second parse of an untouched jk.toml sees the rewritten catalog")
                .isEqualTo("com.acme:http-two");
    }

    @Test
    void a_jk_libs_toml_added_or_removed_between_two_parses_is_seen(@TempDir Path dir) throws IOException {
        Path toml = settled(dir.resolve("jk.toml"), MANIFEST);
        settled(dir.resolve("jk-libs.toml"), libs("com.acme:http-one"));
        assertThat(coordinate(toml)).isEqualTo("com.acme:http-one");

        Files.delete(dir.resolve("jk-libs.toml"));
        assertThatThrownBy(() -> coordinate(toml))
                .as("without the project layer the short name is unknown")
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name `http`");

        settled(dir.resolve("jk-libs.toml"), libs("com.acme:http-three"));
        assertThat(coordinate(toml)).isEqualTo("com.acme:http-three");
    }

    @Test
    void rewriting_the_global_layer_between_two_parses_yields_the_new_coordinate(@TempDir Path dir) throws IOException {
        System.setProperty("jk.env.JK_HOME", dir.resolve("home").toString());
        Path global = JkDirs.libraryRegistry();
        Files.createDirectories(global.getParent());
        Path toml = settled(dir.resolve("project").resolve("jk.toml"), MANIFEST);

        settled(global, libs("com.acme:http-global-one"));
        assertThat(coordinate(toml)).isEqualTo("com.acme:http-global-one");

        settled(global, libs("com.acme:http-global-two"));
        assertThat(coordinate(toml))
                .as("the second parse sees the rewritten global layer")
                .isEqualTo("com.acme:http-global-two");
    }

    private static String coordinate(Path toml) throws IOException {
        return JkBuildParser.parseLocal(toml)
                .dependencies()
                .of(Scope.MAIN)
                .getFirst()
                .module();
    }

    private static String libs(String coordinate) {
        return "[libraries]\nhttp = \"" + coordinate + "\"\n";
    }

    /** Writes {@code body} with an mtime an hour old, so every stamp is outside the settle window. */
    private static Path settled(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)));
        return file;
    }
}
