// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A first-party pack at a pre-release version is pinned by version alone, and the loader still
 * finds and unpacks it from the store: the digest is read from the jar the store holds at that
 * version, not from the lock.
 */
class GuardPacksVersionPinTest {

    private static final String FRAGMENT = """
            [guards.no-system-out]
            kind       = "forbid"
            signatures = ["java.lang.System#out"]
            owner      = "com.acme.Log"
            instead    = "Log.info"
            why        = "stdout is not a log"
            """;

    private static GuardPacks.Coordinate coordinate(String text) {
        return Objects.requireNonNull(GuardPacks.Coordinate.parse(text));
    }

    @Test
    void a_first_party_pack_pins_by_version_alone_only_while_its_version_is_a_pre_release() {
        assertThat(coordinate("cc.jumpkick.guards:spring:0.13.3").pinsByVersionOnly())
                .isTrue();
        assertThat(coordinate("cc.jumpkick.guards:spring:1.0.0-rc1").pinsByVersionOnly())
                .isTrue();
        assertThat(coordinate("cc.jumpkick.guards:spring:1.2.0").pinsByVersionOnly())
                .as("a stable release is immutable and keeps its digest")
                .isFalse();
        assertThat(coordinate("com.acme:house-rules:0.9.0").pinsByVersionOnly())
                .as("a third-party pack keeps its digest at every version")
                .isFalse();
    }

    @Test
    void a_version_pinned_pack_is_unpacked_from_the_store_jar_and_stamped_with_its_digest(@TempDir Path tmp)
            throws IOException {
        GuardPacks.Coordinate c = coordinate("cc.jumpkick.guards:house:0.9.0");
        Path root = project(tmp, c);
        Path store = tmp.resolve("store");
        Path jar = shelve(store, c);

        List<String> problems = GuardPacks.ensure(root, store);

        assertThat(problems).isEmpty();
        Path dir = GuardPacks.unpackedDir(root, c);
        assertThat(GuardPacks.fragment(dir)).hasContent(FRAGMENT);
        assertThat(GuardPacks.unpackedAs(dir, Hashing.sha256Hex(jar))).isTrue();
        assertThat(GuardPacks.ensure(root, store))
                .as("an unchanged store jar is a stat, not a re-read")
                .isEmpty();
    }

    @Test
    void a_version_pinned_pack_absent_from_the_store_is_a_problem_unless_an_earlier_unpack_stands(@TempDir Path tmp)
            throws IOException {
        GuardPacks.Coordinate c = coordinate("cc.jumpkick.guards:house:0.9.0");
        Path root = project(tmp, c);
        Path store = Files.createDirectories(tmp.resolve("store"));

        assertThat(GuardPacks.ensure(root, store))
                .singleElement()
                .asString()
                .contains(c.gav(), "pinned by version", "run `jk lock`");

        Path dir = GuardPacks.unpackedDir(root, c);
        Files.createDirectories(dir);
        Files.writeString(GuardPacks.fragment(dir), FRAGMENT);
        assertThat(GuardPacks.ensure(root, store)).isEmpty();
    }

    /** A project extending {@code c}, whose lock pins it by version alone. */
    private static Path project(Path tmp, GuardPacks.Coordinate c) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name  = "demo"
                version = "1.0.0"
                """);
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), "[guards]\nextends = [\"" + c.gav() + "\"]\n");
        Files.writeString(
                root.resolve("jk-lock.toml"),
                "version = 1\ngenerated-by = \"jk test\"\nresolution-algorithm = \"pubgrub-v1\"\n\n[[plugin]]\ncoordinate = \""
                        + c.ga() + "\"\nversion    = \"" + c.version() + "\"\n");
        return root;
    }

    /** The pack jar on the store's first-party shelf, where {@code jk install} stages it. */
    private static Path shelve(Path store, GuardPacks.Coordinate c) throws IOException {
        Path dir = Files.createDirectories(store.resolve("repos/jk-local")
                .resolve(c.group().replace('.', '/'))
                .resolve(c.artifact())
                .resolve(c.version()));
        Path jar = dir.resolve(c.artifact() + "-" + c.version() + ".jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out)) {
            jos.putNextEntry(new JarEntry(GuardPacks.FRAGMENT));
            jos.write(FRAGMENT.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jar;
    }
}
