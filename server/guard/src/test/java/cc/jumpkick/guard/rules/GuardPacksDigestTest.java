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

/** A digest-pinned pack is the bytes the lock names, not whatever sits at that version in the store. */
class GuardPacksDigestTest {

    private static final String FRAGMENT =
            "[guards.no-x]\nkind = \"text\"\nfiles = [\"**/*.java\"]\npatterns = [\"x\"]\n";

    @Test
    void a_store_jar_whose_bytes_disagree_with_the_pin_is_refused_with_the_fix_and_never_unpacked(@TempDir Path tmp)
            throws IOException {
        GuardPacks.Coordinate c = coordinate("com.acme:house:1.2.0");
        Path store = tmp.resolve("store");
        Path shelved = shelve(store, c, FRAGMENT + "# build two\n");
        String pinned = Hashing.sha256Hex(pack(tmp.resolve("pinned.jar"), FRAGMENT));
        Path root = project(tmp, c, pinned);

        List<String> problems = GuardPacks.ensure(root, store);

        assertThat(problems)
                .singleElement()
                .asString()
                .contains(
                        c.gav(),
                        pinned.substring(0, 12),
                        Hashing.sha256Hex(shelved).substring(0, 12),
                        "run `jk lock`");
        assertThat(GuardPacks.fragment(GuardPacks.unpackedDir(root, c))).doesNotExist();
    }

    @Test
    void a_store_jar_with_the_pinned_bytes_is_unpacked_and_stamped_with_the_pin(@TempDir Path tmp) throws IOException {
        GuardPacks.Coordinate c = coordinate("com.acme:house:1.2.0");
        Path store = tmp.resolve("store");
        Path shelved = shelve(store, c, FRAGMENT);
        Path root = project(tmp, c, Hashing.sha256Hex(shelved));

        assertThat(GuardPacks.ensure(root, store)).isEmpty();
        Path dir = GuardPacks.unpackedDir(root, c);
        assertThat(GuardPacks.fragment(dir)).hasContent(FRAGMENT);
        assertThat(GuardPacks.unpackedAs(dir, Hashing.sha256Hex(shelved))).isTrue();
    }

    private static GuardPacks.Coordinate coordinate(String gav) {
        return Objects.requireNonNull(GuardPacks.Coordinate.parse(gav), gav);
    }

    /** A project extending {@code c}, whose lock pins it to {@code sha256}. */
    private static Path project(Path tmp, GuardPacks.Coordinate c, String sha256) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(root.resolve("jk.toml"), "group = \"com.example\"\nname = \"demo\"\nversion = \"1.0.0\"\n");
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), "[guards]\nextends = [\"" + c.gav() + "\"]\n");
        Files.writeString(
                root.resolve("jk-lock.toml"),
                "version = 1\ngenerated-by = \"jk test\"\nresolution-algorithm = \"pubgrub-v1\"\n\n[[plugin]]\ncoordinate = \""
                        + c.ga() + "\"\nversion    = \"" + c.version() + "\"\nchecksum   = \"sha256:" + sha256
                        + "\"\n");
        return root;
    }

    /** The pack jar on a known shelf of the store, in repository layout. */
    private static Path shelve(Path store, GuardPacks.Coordinate c, String fragment) throws IOException {
        Path dir = Files.createDirectories(store.resolve("repos/jk-local")
                .resolve(c.group().replace('.', '/'))
                .resolve(c.artifact())
                .resolve(c.version()));
        return pack(dir.resolve(c.artifact() + "-" + c.version() + ".jar"), fragment);
    }

    private static Path pack(Path jar, String fragment) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream zip = new JarOutputStream(out)) {
            zip.putNextEntry(new JarEntry(GuardPacks.FRAGMENT));
            zip.write(fragment.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }
}
