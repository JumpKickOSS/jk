// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** jk counts only the bytes it owns: a file another tree also links is shared until jk holds its last link. */
class DiskUsageOwnershipTest {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_file_linked_from_outside_the_walk_is_shared_not_owned(@TempDir Path tmp) throws Exception {
        Path jk = Files.createDirectories(tmp.resolve("jk"));
        Path m2 = Files.createDirectories(tmp.resolve("m2"));
        Path dep = Files.write(m2.resolve("dep.jar"), new byte[4096]);
        Files.createLink(jk.resolve("dep.jar"), dep);
        Files.write(jk.resolve("own.bin"), new byte[1000]);

        DiskUsage.Stats stats = DiskUsage.of(jk);

        assertThat(stats.files()).isEqualTo(2);
        assertThat(stats.bytes()).as("only the file jk holds alone").isEqualTo(1000);
        assertThat(stats.sharedBytes()).as("the jar ~/.m2 also links").isEqualTo(4096);

        Files.delete(dep);
        assertThat(DiskUsage.of(jk).bytes())
                .as("the last link is jk's: now it owns it")
                .isEqualTo(5096);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void links_that_all_lie_inside_the_walk_count_once_as_owned(@TempDir Path tmp) throws Exception {
        Path cas = Files.createDirectories(tmp.resolve("sha256"));
        Path repos = Files.createDirectories(tmp.resolve("repos"));
        Path blob = Files.write(cas.resolve("blob"), new byte[2048]);
        Files.createLink(repos.resolve("a.jar"), blob);

        DiskUsage.Stats[] parts = DiskUsage.exclusive(cas, repos);

        assertThat(parts[0].bytes()).isEqualTo(2048);
        assertThat(parts[1].bytes()).as("attributed to the first root").isZero();
        assertThat(parts[0].sharedBytes() + parts[1].sharedBytes()).isZero();
        assertThat(DiskUsage.linkCount(blob)).isEqualTo(2);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void the_store_leaves_a_blob_another_tree_links_until_it_holds_the_last_link(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("store"));
        byte[] data = new byte[4096];
        data[0] = 'P';
        data[1] = 'K';
        Path blob = cas.put(data);
        long old = 1_800_000_000_000L - 60L * 24 * 3600 * 1000;
        Files.setLastModifiedTime(blob, FileTime.fromMillis(old));
        Path elsewhere = Files.createDirectories(tmp.resolve("private-home")).resolve("copy.jar");
        Files.createLink(elsewhere, blob);

        StoreCasGc.Report kept =
                StoreCasGc.run(cas, Set.of(), Map.of(), StoreCasGc.Policy.defaults(1.0), 1_800_000_000_000L, false);
        assertThat(blob)
                .as("past its window, but deleting the store's link would free nothing")
                .exists();
        assertThat(kept.deleted()).isZero();

        Files.delete(elsewhere);
        StoreCasGc.run(cas, Set.of(), Map.of(), StoreCasGc.Policy.defaults(1.0), 1_800_000_000_000L, false);
        assertThat(blob).as("the store holds the last link: its window applies").doesNotExist();
    }

    @Test
    void the_windows_file_information_struct_decodes_its_volume_index_and_link_count() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(WindowsFileIdentity.INFO_BYTES, 4);
            info.set(ValueLayout.JAVA_INT_UNALIGNED, WindowsFileIdentity.OFF_VOLUME, 0xDEADBEEF);
            info.set(ValueLayout.JAVA_INT_UNALIGNED, WindowsFileIdentity.OFF_LINKS, 3);
            info.set(ValueLayout.JAVA_INT_UNALIGNED, WindowsFileIdentity.OFF_INDEX_HIGH, 0x1);
            info.set(ValueLayout.JAVA_INT_UNALIGNED, WindowsFileIdentity.OFF_INDEX_LOW, 0x80000002);

            WindowsFileIdentity.Info decoded = WindowsFileIdentity.decode(info);

            assertThat(decoded.volume()).isEqualTo(0xDEADBEEFL);
            assertThat(decoded.index()).isEqualTo(0x1_8000_0002L);
            assertThat(decoded.links()).isEqualTo(3);
        }
    }
}
