// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildLogicGroovyHostTest {

    @Test
    void wrap_points_the_child_at_the_user_script_with_escaped_paths(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("after-resources.groovy");
        Files.writeString(script, "outDir.resolve('x.txt').toFile().text = 'ok'\n");
        Path project = dir.resolve("proj");
        Path out = dir.resolve("o");
        String wrapped = BuildLogicGroovyHost.wrap(script, project, out);
        assertTrue(wrapped.contains("setVariable('projectDir'"));
        assertTrue(wrapped.contains("setVariable('outDir'"));
        assertTrue(wrapped.contains("setVariable('properties'"));
        assertTrue(wrapped.contains("GroovyShell"));
        assertTrue(wrapped.contains(BuildLogicGroovyHost.groovyString(
                script.toAbsolutePath().normalize().toString())));
        assertTrue(wrapped.contains(BuildLogicGroovyHost.groovyString(
                project.toAbsolutePath().normalize().toString())));
    }

    @Test
    void groovyString_escapes_quotes_and_backslashes() {
        assertTrue(BuildLogicGroovyHost.groovyString("a'b\\c").contains("\\'"));
        assertTrue(BuildLogicGroovyHost.groovyString("a'b\\c").contains("\\\\"));
    }

    // The child classpath jars are code the user's script runs under, so bytes are trusted only
    // when they hash to a pin, and publication is a verify-then-rename. The offline behaviour of
    // the fetch itself is Http's guard, pinned by HttpTest.offline_short_circuits_with_offline_exception.

    @Test
    void a_truncated_jar_on_disk_is_not_believed(@TempDir Path tmp) throws Exception {
        // The old test was `size > 0`: a download truncated by a killed engine was valid forever
        // and every later build failed with a bare ClassNotFoundException from the child JVM.
        var jar = BuildLogicGroovyHost.PINNED_JARS.getFirst();
        Path out = tmp.resolve(jar.fileName());
        Files.writeString(out, "truncated");
        assertThat(BuildLogicGroovyHost.published(out, jar.sha256())).isFalse();
    }

    @Test
    void published_accepts_exactly_the_pinned_bytes(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("a.jar");
        Files.writeString(out, "bytes");
        String pin = Hashing.sha256Hex(out);
        assertThat(BuildLogicGroovyHost.published(out, pin)).isTrue();
        assertThat(BuildLogicGroovyHost.published(out, pin.toUpperCase(Locale.ROOT)))
                .isTrue();
        assertThat(BuildLogicGroovyHost.published(tmp.resolve("absent.jar"), pin))
                .isFalse();
    }

    @Test
    void a_mismatched_source_is_never_published_and_the_error_names_jar_and_dir(@TempDir Path tmp) throws Exception {
        Path source = tmp.resolve("download");
        Files.writeString(source, "not-the-pinned-bytes");
        Path cache = Files.createDirectories(tmp.resolve("tool-cache"));
        Path out = cache.resolve("groovy-5.0.4.jar");
        assertThatThrownBy(() -> BuildLogicGroovyHost.publish(source, out, "ab".repeat(32)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("groovy-5.0.4.jar")
                .hasMessageContaining(cache.toString());
        assertThat(out).doesNotExist();
        try (var listing = Files.list(cache)) {
            assertThat(listing).as("no temp litter").isEmpty();
        }
    }

    @Test
    void publish_verifies_then_replaces_a_poisoned_file_atomically(@TempDir Path tmp) throws Exception {
        Path source = tmp.resolve("download");
        Files.writeString(source, "good-bytes");
        String pin = Hashing.sha256Hex(source);
        Path cache = Files.createDirectories(tmp.resolve("tool-cache"));
        Path out = cache.resolve("ivy.jar");
        Files.writeString(out, "poisoned");
        BuildLogicGroovyHost.publish(source, out, pin);
        assertThat(Files.readString(out)).isEqualTo("good-bytes");
        assertThat(BuildLogicGroovyHost.published(out, pin)).isTrue();
    }
}
