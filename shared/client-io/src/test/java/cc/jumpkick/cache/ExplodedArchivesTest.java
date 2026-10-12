// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplodedArchivesTest {

    @Test
    void an_archive_explodes_once_into_its_shard(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        Path aar = Files.write(tmp.resolve("lib.aar"), zip("classes.jar", "jar", "res/values/strings.xml", "<r/>"));

        Path exploded = ExplodedArchives.explodeFile(cas, aar);

        assertThat(exploded.resolve("classes.jar")).hasContent("jar");
        assertThat(exploded.resolve("res/values/strings.xml")).hasContent("<r/>");
        assertThat(ExplodedArchives.explodeFile(cas, aar)).isEqualTo(exploded);
        assertThat(siblings(exploded)).as("no staging beside the published dir").containsExactly(exploded);
    }

    @Test
    void a_failed_explode_leaves_no_staging_behind(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        Path aar = Files.write(tmp.resolve("evil.aar"), zip("classes.jar", "jar", "../escaped.txt", "slip"));

        assertThatThrownBy(() -> ExplodedArchives.explodeFile(cas, aar))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("zip-slip");

        Path shards = cas.root().resolve("exploded");
        try (Stream<Path> all = Files.walk(shards)) {
            assertThat(all.filter(p -> !p.equals(shards)))
                    .as("neither the staging dir nor a published dir remains")
                    .allSatisfy(p -> assertThat(p).isDirectory())
                    .noneMatch(p -> p.getFileName().toString().startsWith(".exploding-"))
                    .noneMatch(p -> p.getFileName().toString().length() == 64);
        }
    }

    private static Iterable<Path> siblings(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir.getParent())) {
            return s.toList();
        }
    }

    private static byte[] zip(String... nameThenBody) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < nameThenBody.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameThenBody[i]));
                zip.write(nameThenBody[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
