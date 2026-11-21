// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module's outputs record reads the rows of the workspace lock it builds from, not the file: on
 * hotel-reservations a relock that gave service its own postgresql left contract, core, frontend and
 * rest "built from other inputs" with every step cached. A row the module resolves, its
 * own or a sibling's it inherits, still moves it.
 */
class LockSliceTest {

    private static final String HEADER = """
            version = 1
            generated-by = "jk %s"
            generated-by-build = "%s"
            resolution-algorithm = "pubgrub-v1"

            """;

    private static String row(String name, String version, String scopes, String members) {
        return """
                [[artifact]]
                name     = "%s:jar:"
                version  = "%s"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:%s"
                scopes   = [%s]
                %s
                """.formatted(name, version, "ab".repeat(32), scopes, members);
    }

    @AfterEach
    void forget() {
        LockfileReader.clearCache();
    }

    @Test
    void a_row_another_member_resolves_moves_nothing_here(@TempDir Path ws) throws Exception {
        JkBuild core = module(ws, "core", "slf4j = \"org.slf4j:slf4j-api:2.0.16\"");
        JkBuild service = module(ws, "service", "postgresql = \"org.postgresql:postgresql:42.7.14\"");
        Path lock = ws.resolve("jk-lock.toml");
        Files.writeString(
                lock,
                HEADER.formatted("0.51.1", "aaaaaaaaaaaa")
                        + row("org.slf4j:slf4j-api", "2.0.16", "\"main\"", "")
                        + row("org.postgresql:postgresql", "42.7.14", "\"main\"", ""));
        String coreBefore = LockSlice.outputsDigest(ws.resolve("core"), core, lock);
        String serviceBefore = LockSlice.outputsDigest(ws.resolve("service"), service, lock);

        // service locks its own driver; the jk that wrote the lock moved too.
        Files.writeString(
                lock,
                HEADER.formatted("0.51.2", "bbbbbbbbbbbb")
                        + row("org.slf4j:slf4j-api", "2.0.16", "\"main\"", "")
                        + row("org.postgresql:postgresql", "42.7.13", "\"main\"", "members = [\"service\"]")
                        + row("org.postgresql:postgresql", "42.7.14", "\"main\"", ""));
        LockfileReader.clearCache();
        assertThat(LockSlice.outputsDigest(ws.resolve("core"), core, lock))
                .as("core resolves no driver")
                .isEqualTo(coreBefore);
        assertThat(LockSlice.outputsDigest(ws.resolve("service"), service, lock))
                .as("service builds from the row it now reads")
                .isNotEqualTo(serviceBefore);
    }

    @Test
    void without_a_manifest_the_whole_file_keys_as_before(@TempDir Path ws) throws Exception {
        Path lock = Files.writeString(ws.resolve("jk-lock.toml"), HEADER.formatted("0.51.1", "aaaaaaaaaaaa"));
        assertThat(LockSlice.outputsDigest(ws, null, lock)).isEqualTo(FileHashMemo.contentHash(lock));
        assertThat(LockSlice.outputsDigest(ws, null, ws.resolve("absent.toml"))).isEqualTo("missing");
    }

    private static JkBuild module(Path ws, String name, String dependency) throws Exception {
        Path dir = Files.createDirectories(ws.resolve(name));
        Path manifest = Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "%s"
                version = "1.0"

                [dependencies]
                %s
                """.formatted(name, dependency));
        return JkBuildParser.parseLocal(manifest);
    }
}
