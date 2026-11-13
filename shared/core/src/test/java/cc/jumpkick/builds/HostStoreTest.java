// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One host file for every section the estimate learns: each writer keeps the others' lines. */
class HostStoreTest {

    @TempDir
    Path tmp;

    private Path file() {
        return tmp.resolve("builds").resolve(HostStore.FILE);
    }

    @Test
    void a_section_is_replaced_and_the_others_kept() throws Exception {
        HostStore.update(file(), HostStore.BIAS, lines -> List.of("CALIBRATION compile 0.1 1"));
        HostStore.update(file(), HostStore.FETCH, lines -> List.of("samples 10 20"));
        HostStore.update(file(), HostStore.BIAS, lines -> {
            List<String> out = new ArrayList<>(lines);
            out.add("HOST suite 0.2 1");
            return out;
        });

        assertThat(HostStore.section(file(), HostStore.BIAS))
                .containsExactly("CALIBRATION compile 0.1 1", "HOST suite 0.2 1");
        assertThat(HostStore.section(file(), HostStore.FETCH)).containsExactly("samples 10 20");
        assertThat(HostStore.section(file(), HostStore.LOCK)).isEmpty();

        HostStore.update(file(), HostStore.FETCH, lines -> List.of());
        assertThat(HostStore.sections(file())).containsOnlyKeys(HostStore.BIAS);
    }

    @Test
    void a_missing_store_reads_as_empty_and_comments_are_not_lines() throws Exception {
        assertThat(HostStore.section(file(), HostStore.BIAS)).isEmpty();
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "# header\n[bias]\n# note\nHOST compile 0.3 2\n\n[lock]\noverhead-ms 400\n");
        assertThat(HostStore.section(file(), HostStore.BIAS)).containsExactly("HOST compile 0.3 2");
        assertThat(HostStore.section(file(), HostStore.LOCK)).containsExactly("overhead-ms 400");
    }

    @Test
    void concurrent_writers_of_different_sections_keep_each_others_lines() throws Exception {
        int rounds = 50;
        Thread a = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                int n = i;
                HostStore.update(file(), HostStore.BIAS, lines -> List.of("HOST compile " + n + " 1"));
            }
        });
        Thread b = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                int n = i;
                HostStore.update(file(), HostStore.FETCH, lines -> List.of("samples " + (n + 1)));
            }
        });
        a.start();
        b.start();
        a.join();
        b.join();
        assertThat(HostStore.section(file(), HostStore.BIAS)).containsExactly("HOST compile " + (rounds - 1) + " 1");
        assertThat(HostStore.section(file(), HostStore.FETCH)).containsExactly("samples " + rounds);
    }
}
