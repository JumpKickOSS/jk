// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1293: enumerating engine identities.
 *
 * <p>The identity key is a hash, and since the store became part of it (JK-1289) a machine can hold
 * several resident engines at once — one per distinct store. {@code current()} names only the one this
 * invocation would talk to, so without enumeration the only way to clear the rest was {@code pkill}.
 */
class EnginePathsEnumerationTest {

    private static Path engineDir(Path state) throws Exception {
        return Files.createDirectories(state.resolve("engine"));
    }

    @Test
    void every_endpoint_pointer_is_an_identity(@TempDir Path tmp) throws Exception {
        Path dir = engineDir(tmp);
        Files.writeString(dir.resolve("aaaa1111.endpoint"), "aaaa1111.gen1.sock\n");
        Files.writeString(dir.resolve("bbbb2222.endpoint"), "bbbb2222.gen3.sock\n");

        var found = EnginePaths.identitiesIn(tmp);

        assertThat(found).hasSize(2);
        assertThat(found).extracting(EnginePaths.Paths::key).containsExactlyInAnyOrder("aaaa1111", "bbbb2222");
    }

    @Test
    void an_identity_resolves_the_same_paths_as_a_hashed_one(@TempDir Path tmp) throws Exception {
        // Enumeration recovers a key from a filename rather than by hashing, so its paths have to agree
        // with what resolve() produces or `stop --all` would probe files nothing writes.
        Path dir = engineDir(tmp);
        EnginePaths.Paths hashed = EnginePaths.resolve(tmp, tmp.resolve("store"));
        Files.writeString(dir.resolve(hashed.key() + ".endpoint"), hashed.key() + ".gen1.sock\n");

        var found = EnginePaths.identitiesIn(tmp);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).key()).isEqualTo(hashed.key());
        assertThat(found.get(0).socket()).isEqualTo(hashed.socket());
        assertThat(found.get(0).pid()).isEqualTo(hashed.pid());
        assertThat(found.get(0).log()).isEqualTo(hashed.log());
    }

    @Test
    void unrelated_files_in_the_engine_dir_are_not_identities(@TempDir Path tmp) throws Exception {
        Path dir = engineDir(tmp);
        Files.writeString(dir.resolve("aaaa1111.endpoint"), "aaaa1111.gen1.sock\n");
        // Everything else an engine leaves behind must not be mistaken for one.
        for (String noise : new String[] {
            "aaaa1111.pid", "aaaa1111.log", "aaaa1111.gen1.sock", "aaaa1111.lock", "aaaa1111.token", "notes.txt"
        }) {
            Files.writeString(dir.resolve(noise), "x");
        }

        assertThat(EnginePaths.identitiesIn(tmp)).hasSize(1);
    }

    @Test
    void a_state_dir_with_no_engine_dir_yields_nothing(@TempDir Path tmp) {
        assertThat(EnginePaths.identitiesIn(tmp.resolve("never-used"))).isEmpty();
    }

    @Test
    void an_empty_engine_dir_yields_nothing(@TempDir Path tmp) throws Exception {
        engineDir(tmp);

        assertThat(EnginePaths.identitiesIn(tmp)).isEmpty();
    }

    @Test
    void the_newest_identity_comes_first(@TempDir Path tmp) throws Exception {
        // Most-recently-used first, so a listing reads sensibly and `stop --all` addresses the engine a
        // user most likely means before the stale ones.
        Path dir = engineDir(tmp);
        Path older = dir.resolve("old00000.endpoint");
        Path newer = dir.resolve("new00000.endpoint");
        Files.writeString(older, "x");
        Files.writeString(newer, "x");
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L));
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L));

        assertThat(EnginePaths.identitiesIn(tmp)).extracting(EnginePaths.Paths::key).containsExactly("new00000", "old00000");
    }
}
