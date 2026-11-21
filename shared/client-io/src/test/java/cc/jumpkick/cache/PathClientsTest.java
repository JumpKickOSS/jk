// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathClientsTest {

    /**
     * On POSIX the new pair is renamed straight over the live one: a running client keeps its inode,
     * and parking the live name first would leave a moment with no {@code jk} on the PATH.
     */
    @Test
    void install_binaries_renames_over_the_live_pair_and_parks_nothing_on_posix(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Files.writeString(bin.resolve("jk"), "old-jk");
        Files.writeString(bin.resolve("jkx"), "old-jkx");
        Path src = Files.writeString(tmp.resolve("new-jk"), "new-jk");

        PathClients.installBinaries(src, bin, false);

        assertThat(bin.resolve("jk")).hasContent("new-jk");
        assertThat(bin.resolve("jkx")).hasContent("new-jk");
        try (var entries = Files.list(bin)) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .as("nothing parked, nothing staged")
                    .containsExactlyInAnyOrder("jk", "jkx");
        }
    }

    /** An install stages under names of its own, so it never deletes the client another install is staging. */
    @Test
    void install_binaries_leaves_another_installs_staged_client_alone(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path theirs = Files.writeString(bin.resolve(".jk-new"), "their-jk");
        Path src = Files.writeString(tmp.resolve("new-jk"), "new-jk");

        PathClients.installBinaries(src, bin, false);

        assertThat(theirs).hasContent("their-jk");
        assertThat(bin.resolve("jk")).hasContent("new-jk");
        try (var entries = Files.list(bin)) {
            assertThat(entries.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("jk", "jkx", ".jk-new");
        }
    }

    @Test
    void install_binaries_parks_the_live_pair_on_windows(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Files.writeString(bin.resolve("jk.exe"), "old-jk");
        Files.writeString(bin.resolve("jkx.exe"), "old-jkx");
        Path src = Files.writeString(tmp.resolve("new-jk"), "new-jk");

        PathClients.installBinaries(src, bin, true);

        assertThat(bin.resolve("jk.exe")).hasContent("new-jk");
        assertThat(bin.resolve("jkx.exe")).hasContent("new-jk");
        assertThat(bin.resolve("jk.exe.old")).hasContent("old-jk");
        assertThat(bin.resolve("jkx.exe.old")).hasContent("old-jkx");
    }

    @Test
    void the_clients_earlier_installs_parked_are_swept_and_nothing_else(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        for (String parked :
                List.of("jk.old", "jkx.old", "jk.exe.old", "jk.exe.old-123456789", "jkx.exe.old-42", "jk.bat.old")) {
            Files.writeString(bin.resolve(parked), "parked");
        }
        for (String kept : List.of("jk", "jkx", "jk.exe", "jk-jvm", "jk.older", "notes.old", "jk.old.txt")) {
            Files.writeString(bin.resolve(kept), "kept");
        }

        assertThat(PathClients.sweepParked(bin)).isEqualTo(6);

        try (var entries = Files.list(bin)) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder("jk", "jkx", "jk.exe", "jk-jvm", "jk.older", "notes.old", "jk.old.txt");
        }
        assertThat(PathClients.sweepParked(tmp.resolve("no-such-bin"))).isZero();
    }

    @Test
    void an_install_sweeps_the_last_parked_client_and_parks_the_current_one(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Files.writeString(bin.resolve("jk.exe"), "v2");
        Files.writeString(bin.resolve("jkx.exe"), "v2");
        Files.writeString(bin.resolve("jk.exe.old-99"), "v1");
        Path src = Files.writeString(tmp.resolve("v3"), "v3");

        PathClients.installBinaries(src, bin, true);

        assertThat(bin.resolve("jk.exe.old-99")).doesNotExist();
        assertThat(bin.resolve("jk.exe.old")).hasContent("v2");
        assertThat(bin.resolve("jk.exe")).hasContent("v3");
    }

    @Test
    void a_source_that_cannot_be_copied_leaves_the_live_client_on_path(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Files.writeString(bin.resolve("jk"), "old-jk");
        Files.writeString(bin.resolve("jkx"), "old-jkx");
        Path missing = tmp.resolve("missing-blob");

        assertThatThrownBy(() -> PathClients.installBinaries(missing, bin, false))
                .isInstanceOf(IOException.class);

        assertThat(bin.resolve("jk")).hasContent("old-jk");
        assertThat(bin.resolve("jkx")).hasContent("old-jkx");
        try (var entries = Files.list(bin)) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .as("nothing parked, nothing staged")
                    .containsExactlyInAnyOrder("jk", "jkx");
        }
    }

    /**
     * The source is a CAS blob its digest vouches for. A hard link would make bin/jk the same
     * inode, so rewriting the PATH client — or the chmod the install itself performs — would change
     * the verified bytes; the install takes its own copy.
     */
    @Test
    void install_binaries_copies_the_source_instead_of_linking_it(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path src = Files.writeString(tmp.resolve("blob"), "verified-bytes");

        PathClients.installBinaries(src, bin, false);

        assertThat(Files.isSameFile(bin.resolve("jk"), src)).isFalse();
        Files.writeString(bin.resolve("jk"), "rewritten client");
        assertThat(src).hasContent("verified-bytes");
    }

    @Test
    void parked_name_appends_old_after_the_full_filename() {
        assertThat(PathClients.parkedName(Path.of("jk")).getFileName().toString())
                .isEqualTo("jk.old");
        assertThat(PathClients.parkedName(Path.of("jk.exe")).getFileName().toString())
                .isEqualTo("jk.exe.old");
    }
}
