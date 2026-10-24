// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An {@code unpack = true} tool is its archive extracted once into the store, named by the archive's digest. */
class ToolArchivesTest {

    @Test
    void an_archive_unpacks_once_and_is_its_digest(@TempDir Path tmp) throws Exception {
        Path scratch = tmp.resolve("scratch");
        Path archive = zip(tmp.resolve("ide.zip"), "lib/app.jar", "product-info.json");

        Path dir = ToolArchives.unpack(archive, List.of(), scratch);

        assertThat(dir).startsWith(scratch.resolve("unpacked"));
        assertThat(dir.resolve("lib/app.jar")).hasContent("lib/app.jar");
        assertThat(dir.resolve("product-info.json")).exists();
        assertThat(ToolArchives.isUnpacked(dir)).isTrue();
        assertThat(ToolArchives.identity(dir)).isEqualTo("unpacked:" + dir.getFileName());

        Files.delete(dir.resolve("product-info.json"));
        assertThat(ToolArchives.unpack(archive, List.of(), scratch))
                .as("a whole unpack is served as it stands, not extracted again")
                .isEqualTo(dir);
        assertThat(dir.resolve("product-info.json")).doesNotExist();
    }

    @Test
    void a_torn_unpack_is_replaced_and_another_archive_is_another_directory(@TempDir Path tmp) throws Exception {
        Path scratch = tmp.resolve("scratch");
        Path archive = zip(tmp.resolve("ide.zip"), "lib/app.jar");
        Path dir = ToolArchives.unpack(archive, List.of(), scratch);
        Files.delete(dir.resolve(ToolArchives.COMPLETE));
        Files.delete(dir.resolve("lib/app.jar"));

        assertThat(ToolArchives.unpack(archive, List.of(), scratch)).isEqualTo(dir);
        assertThat(dir.resolve("lib/app.jar")).exists();

        Path other = ToolArchives.unpack(zip(tmp.resolve("other.zip"), "lib/other.jar"), List.of(), scratch);
        assertThat(other).isNotEqualTo(dir);
        assertThat(other.resolve("lib/other.jar")).exists();
    }

    @Test
    void an_include_extracts_only_what_its_globs_match_and_is_its_own_directory(@TempDir Path tmp) throws Exception {
        Path scratch = tmp.resolve("scratch");
        Path archive = zip(
                tmp.resolve("ide.zip"),
                "lib/app.jar",
                "product-info.json",
                "plugins/java/lib/java.jar",
                "plugins/Kotlin/lib/kotlin.jar");

        Path part = ToolArchives.unpack(archive, List.of("lib/**", "product-info.json", "plugins/java/**"), scratch);
        Path whole = ToolArchives.unpack(archive, List.of(), scratch);

        assertThat(part.resolve("lib/app.jar")).exists();
        assertThat(part.resolve("product-info.json")).exists();
        assertThat(part.resolve("plugins/java/lib/java.jar")).exists();
        assertThat(part.resolve("plugins/Kotlin")).doesNotExist();
        assertThat(whole).isNotEqualTo(part);
        assertThat(whole.resolve("plugins/Kotlin/lib/kotlin.jar")).exists();
        assertThat(ToolArchives.unpack(archive, List.of("lib/**", "product-info.json", "plugins/java/**"), scratch))
                .isEqualTo(part);
    }

    @Test
    void a_glob_matches_within_one_segment_and_a_double_star_across_them(@TempDir Path tmp) throws Exception {
        for (String rel : new String[] {"lib/b.jar", "lib/a.jar", "lib/modules/m.jar", "lib/notes.txt"}) {
            Path file = tmp.resolve(rel);
            Files.createDirectories(file.getParent());
            Files.writeString(file, rel);
        }
        assertThat(ProvidedClasspath.matching(tmp, "lib/*.jar"))
                .containsExactly(tmp.resolve("lib/a.jar"), tmp.resolve("lib/b.jar"));
        assertThat(ProvidedClasspath.matching(tmp, "lib/**.jar"))
                .containsExactly(tmp.resolve("lib/a.jar"), tmp.resolve("lib/b.jar"), tmp.resolve("lib/modules/m.jar"));
        assertThat(ProvidedClasspath.matching(tmp, "plugins/*/lib/*.jar")).isEmpty();
    }

    private static Path zip(Path file, String... entries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.write(entry.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }
}
