// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.script.ScriptHeader;
import cc.jumpkick.script.ScriptHeaderParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code jk tool run} script's classes are reused only for the same script, the same {@code
 * //SOURCES} content and the same {@code --with} dependencies: a change to any of them is a new key.
 */
class ScriptCacheKeyTest {

    private static String key(Path script, List<Dependency> with) throws Exception {
        byte[] bytes = Files.readAllBytes(script);
        ScriptHeader header = ScriptHeaderParser.parse(new String(bytes, StandardCharsets.UTF_8));
        return ScriptCacheKey.of(script, bytes, header, with);
    }

    @Test
    void an_unchanged_script_keeps_its_key(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("Hello.java"), "class Hello {}");
        assertThat(key(script, List.of())).isEqualTo(key(script, List.of()));
    }

    @Test
    void an_edited_sources_file_is_a_new_key(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("Main.java"), "//SOURCES Util.java\nclass Main {}");
        Files.writeString(dir.resolve("Util.java"), "class Util {}");
        String before = key(script, List.of());

        Files.writeString(dir.resolve("Util.java"), "class Util { static int x; }");

        assertThat(key(script, List.of())).isNotEqualTo(before);
    }

    @Test
    void another_with_dependency_is_a_new_key(@TempDir Path dir) throws Exception {
        Path script = Files.writeString(dir.resolve("Main.java"), "class Main {}");
        Dependency gson = ScriptHeaderParser.parseDependency("com.google.code.gson:gson:2.11.0");
        Dependency newer = ScriptHeaderParser.parseDependency("com.google.code.gson:gson:2.12.0");

        assertThat(key(script, List.of(gson)))
                .isNotEqualTo(key(script, List.of()))
                .isNotEqualTo(key(script, List.of(newer)));
    }

    @Test
    void a_run_stamps_its_entry_as_used(@TempDir Path dir) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("key/classes"));
        Path used = dir.resolve("key").resolve(ScriptCacheKey.USED);
        ScriptCacheKey.stampUse(classes);
        Files.setLastModifiedTime(used, FileTime.from(Instant.now().minus(Duration.ofDays(40))));

        ScriptCacheKey.stampUse(classes);

        assertThat(Files.getLastModifiedTime(used).toInstant())
                .isAfter(Instant.now().minus(Duration.ofDays(1)));
    }
}
