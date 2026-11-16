// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.rules.GuardPacks;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.host.Hashing;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardPackFetchTest {

    private static final GuardPacks.Coordinate PACK =
            Objects.requireNonNull(GuardPacks.Coordinate.parse("cc.jumpkick.guards:house:0.9.0"));
    private static final byte[] JAR = jar();
    private static final String SHA = Hashing.sha256Hex(JAR);

    @Test
    void a_pinned_pack_missing_from_the_store_and_target_is_fetched_and_then_loads(@TempDir Path tmp) throws Exception {
        Path root = project(tmp, true);
        Path store = Files.createDirectories(tmp.resolve("store"));
        List<String> asked = new ArrayList<>();

        List<String> fetched = GuardPackFetch.fetchMissing(root, store, (c, sha) -> {
            assertThat(sha).as("the fetch is pinned to the lock's digest").isEqualTo(SHA);
            asked.add(c.gav());
            shelve(store, c);
            return true;
        });

        assertThat(fetched).containsExactly(PACK.gav());
        assertThat(GuardPacks.ensure(root, store)).isEmpty();
        assertThat(GuardPackFetch.fetchMissing(root, store, (c, sha) -> {
                    asked.add(c.gav());
                    return true;
                }))
                .as("a pack already in the store is not fetched again")
                .isEmpty();
        assertThat(asked).containsExactly(PACK.gav());
    }

    @Test
    void a_pack_no_repository_has_is_still_the_loaders_error(@TempDir Path tmp) throws Exception {
        Path root = project(tmp, true);
        Path store = Files.createDirectories(tmp.resolve("store"));

        assertThat(GuardPackFetch.fetchMissing(root, store, (c, sha) -> false)).isEmpty();

        assertThat(GuardPacks.ensure(root, store)).singleElement().asString().contains(PACK.gav());
    }

    @Test
    void an_unpinned_pack_is_left_to_jk_lock(@TempDir Path tmp) throws Exception {
        Path root = project(tmp, false);
        Path store = Files.createDirectories(tmp.resolve("store"));

        assertThat(GuardPackFetch.fetchMissing(root, store, (c, sha) -> {
                    throw new AssertionError("fetched an unpinned pack");
                }))
                .isEmpty();
    }

    @Test
    void a_row_with_no_digest_is_not_fetched_and_is_the_loaders_error_to_relock(@TempDir Path tmp) throws Exception {
        Path root = project(tmp, true);
        Files.writeString(
                root.resolve("jk-lock.toml"),
                "version = 1\ngenerated-by = \"jk test\"\nresolution-algorithm = \"pubgrub-v1\"\n\n[[plugin]]\n"
                        + "coordinate = \"" + PACK.ga() + "\"\nversion    = \"" + PACK.version() + "\"\n");
        Path store = Files.createDirectories(tmp.resolve("store"));
        shelve(store, PACK);

        assertThat(GuardPackFetch.fetchMissing(root, store, (c, sha) -> {
                    throw new AssertionError("fetched a pack the lock pins no bytes for");
                }))
                .isEmpty();
        assertThat(GuardPacks.ensure(root, store))
                .singleElement()
                .asString()
                .contains(PACK.gav(), "without a checksum", "run jk lock");
    }

    private static Path project(Path tmp, boolean pinned) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(root.resolve("jk.toml"), "group = \"com.example\"\nname  = \"demo\"\nversion = \"1.0.0\"\n");
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), "[guards]\nextends = [\"" + PACK.gav() + "\"]\n");
        String lock = "version = 1\ngenerated-by = \"jk test\"\nresolution-algorithm = \"pubgrub-v1\"\n";
        if (pinned) {
            lock += "\n[[plugin]]\ncoordinate = \"" + PACK.ga() + "\"\nversion    = \"" + PACK.version()
                    + "\"\nsource     = \"jumpkick+https://jumpkick.build/repo/\"\nchecksum   = \"sha256:" + SHA
                    + "\"\n";
        }
        Files.writeString(root.resolve("jk-lock.toml"), lock);
        return root;
    }

    /** Where a download from the JumpKick repository lands in the store. */
    private static void shelve(Path store, GuardPacks.Coordinate c) throws IOException {
        Path dir = Files.createDirectories(store.resolve("repos/jumpkick")
                .resolve(c.group().replace('.', '/'))
                .resolve(c.artifact())
                .resolve(c.version()));
        Files.write(dir.resolve(c.artifact() + "-" + c.version() + ".jar"), JAR);
    }

    /** The pack's jar, built once so the lock can pin its digest. */
    private static byte[] jar() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(out)) {
            JarEntry entry = new JarEntry(GuardPacks.FRAGMENT);
            entry.setTime(0L);
            jos.putNextEntry(entry);
            jos.write(
                    "[guards.no-system-out]\nkind = \"forbid\"\nsignatures = [\"java.lang.System#out\"]\nwhy = \"x\"\n"
                            .getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
