// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildLogicGroovyHostTest {

    @Test
    void a_scripts_key_is_its_bytes_and_moves_with_them() throws Exception {
        byte[] a = "println 'a'\n".getBytes(StandardCharsets.UTF_8);
        byte[] b = "println 'b'\n".getBytes(StandardCharsets.UTF_8);
        assertThat(BuildLogicGroovyHost.scriptKey(a)).isEqualTo(BuildLogicGroovyHost.scriptKey(a.clone()));
        assertThat(BuildLogicGroovyHost.scriptKey(a)).isNotEqualTo(BuildLogicGroovyHost.scriptKey(b));
    }

    /** The key holds no path, so two checkouts of one script share its compiled jar. */
    @Test
    void the_same_script_in_two_checkouts_shares_one_compiled_jar(@TempDir Path dir) throws Exception {
        Path one = Files.writeString(
                Files.createDirectories(dir.resolve("one/.jk/scripts")).resolve("before-compile.groovy"),
                "println 1\n");
        Path two = Files.writeString(
                Files.createDirectories(dir.resolve("two/.jk/scripts")).resolve("before-compile.groovy"),
                "println 1\n");
        assertThat(BuildLogicGroovyHost.compiledJar(one)).isEqualTo(BuildLogicGroovyHost.compiledJar(two));
    }

    /** The runner's child classpath is Groovy and Ant alone: its source names nothing of jk's. */
    @Test
    void the_runner_names_nothing_the_child_cannot_resolve() throws Exception {
        String source = GroovyHostJar.source();
        assertThat(source).doesNotContain("cc.jumpkick").doesNotContain("Log.");
        assertThat(source).contains("catch (Throwable noAnt)");
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

    @Test
    void a_wiped_tool_cache_is_verified_and_fetched_again(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("groovy-source.jar"), "groovy bytes");
        var pin = new BuildLogicGroovyHost.PinnedJar("g", "groovy", "1", Hashing.sha256Hex(source));
        Path cache = dir.resolve("tools/build-logic-groovy");
        AtomicInteger fetches = new AtomicInteger();
        BuildLogicGroovyHost.Fetch fetch = jar -> {
            fetches.incrementAndGet();
            return source;
        };

        Path[] first = BuildLogicGroovyHost.ensureJars(cache, List.of(pin), fetch);
        assertThat(first[0]).hasContent("groovy bytes");
        BuildLogicGroovyHost.ensureJars(cache, List.of(pin), fetch);
        assertThat(fetches.get()).as("verified once, then remembered").isEqualTo(1);

        Files.delete(first[0]);
        Files.delete(cache);
        Path[] again = BuildLogicGroovyHost.ensureJars(cache, List.of(pin), fetch);

        assertThat(again[0]).hasContent("groovy bytes");
        assertThat(fetches.get()).isEqualTo(2);
    }
}
