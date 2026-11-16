// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pack's rules are the bytes the lock pins for the version the rules file names, first-party or
 * not: a row with no digest is relocked, and a version the lock does not pin is never served from
 * what an earlier build unpacked for another version.
 */
class GuardPacksPinTest {

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
    void a_first_party_row_with_no_digest_is_refused_even_with_the_jar_in_the_store(@TempDir Path tmp)
            throws IOException {
        GuardPacks.Coordinate c = coordinate("cc.jumpkick.guards:library:0.51.1");
        Path root = project(tmp, c, c.version(), null);
        Path store = tmp.resolve("store");
        shelve(store, c);

        assertThat(GuardPacks.ensure(root, store))
                .singleElement()
                .asString()
                .contains(c.gav(), "without a checksum", "run jk lock");
        assertThat(GuardPacks.fragment(GuardPacks.unpackedDir(root, c))).doesNotExist();
    }

    @Test
    void a_first_party_row_with_its_digest_is_unpacked_and_stamped(@TempDir Path tmp) throws IOException {
        GuardPacks.Coordinate c = coordinate("cc.jumpkick.guards:library:0.51.1");
        Path store = tmp.resolve("store");
        Path jar = shelve(store, c);
        Path root = project(tmp, c, c.version(), Hashing.sha256Hex(jar));

        assertThat(GuardPacks.ensure(root, store)).isEmpty();
        Path dir = GuardPacks.unpackedDir(root, c);
        assertThat(GuardPacks.fragment(dir)).hasContent(FRAGMENT);
        assertThat(GuardPacks.unpackedAs(dir, Hashing.sha256Hex(jar))).isTrue();
    }

    @Test
    void a_version_the_lock_does_not_pin_is_not_served_from_another_versions_unpack(@TempDir Path tmp)
            throws IOException {
        GuardPacks.Coordinate pinned = coordinate("cc.jumpkick.guards:library:0.51.1");
        Path store = tmp.resolve("store");
        Path jar = shelve(store, pinned);
        Path root = project(tmp, pinned, pinned.version(), Hashing.sha256Hex(jar));
        assertThat(GuardPacks.ensure(root, store)).isEmpty();

        // The rules file now names another version; the lock and target/ still hold 0.51.1's.
        GuardPacks.Coordinate edited = coordinate("cc.jumpkick.guards:library:0.50.2");
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), "[guards]\nextends = [\"" + edited.gav() + "\"]\n");

        assertThat(GuardPacks.ensure(root, store))
                .singleElement()
                .asString()
                .contains(edited.gav(), "not in jk-lock.toml", "run jk lock");
    }

    @Test
    void a_load_without_the_store_refuses_another_versions_unpack_too(@TempDir Path tmp) throws IOException {
        GuardPacks.Coordinate pinned = coordinate("cc.jumpkick.guards:library:0.51.1");
        Path store = tmp.resolve("store");
        Path jar = shelve(store, pinned);
        Path root = project(tmp, pinned, pinned.version(), Hashing.sha256Hex(jar));
        assertThat(GuardRules.load(root, GuardsConfig.ABSENT, store).hasErrors())
                .isFalse();
        assertThat(GuardRules.load(root, GuardsConfig.ABSENT).hasErrors())
                .as("what the build unpacked is the pinned bytes")
                .isFalse();

        Files.writeString(
                root.resolve(GuardsPresence.RULES_FILE),
                "[guards]\nextends = [\"cc.jumpkick.guards:library:0.50.2\"]\n");

        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.problems()).extracting(LoadError::message).anySatisfy(m -> assertThat(m)
                .contains("cc.jumpkick.guards:library:0.50.2", "run jk build"));
        assertThat(load.rules().ids()).doesNotContain("no-system-out");
    }

    @Test
    void an_unresolvable_first_party_pack_names_the_version_this_jk_ships() {
        assertThat(GuardPacks.unresolvable(coordinate("cc.jumpkick.guards:library:0.50.2"), "0.51.1"))
                .contains(
                        "cc.jumpkick.guards:library:0.50.2",
                        "older ones are removed",
                        "This jk is 0.51.1",
                        "pin cc.jumpkick.guards:library:0.51.1",
                        "jk update");
        assertThat(GuardPacks.unresolvable(coordinate("com.acme:house:1.0.0"), "0.51.1"))
                .contains("com.acme:house:1.0.0", "add the repository")
                .doesNotContain("0.51.1");
    }

    /** A project extending {@code c}, whose lock pins {@code version} with {@code sha256} (none when null). */
    private static Path project(Path tmp, GuardPacks.Coordinate c, String version, @Nullable String sha256)
            throws IOException {
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
                        + c.ga() + "\"\nversion    = \"" + version + "\"\n"
                        + (sha256 == null
                                ? ""
                                : "source     = \"jumpkick+https://jumpkick.build/repo/\"\nchecksum   = \"sha256:"
                                        + sha256 + "\"\n"));
        return root;
    }

    /** The pack jar where a download from the JumpKick repository lands in the store. */
    private static Path shelve(Path store, GuardPacks.Coordinate c) throws IOException {
        Path dir = Files.createDirectories(store.resolve("repos/jumpkick")
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
