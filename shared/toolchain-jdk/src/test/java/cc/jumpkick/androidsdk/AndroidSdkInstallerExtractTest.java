// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.androidsdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A component zip that cannot be extracted leaves nothing beside the component it failed to become. */
class AndroidSdkInstallerExtractTest {

    @Test
    void a_component_zip_extracts_and_leaves_no_staging(@TempDir Path tmp) throws Exception {
        Path zip = Files.write(tmp.resolve("platform.zip"), zip("android-36/android.jar", "jar"));
        Path platforms = tmp.resolve("sdk/platforms");
        Path dir = platforms.resolve("android-36");

        AndroidSdkInstaller.extractZip(zip, dir);

        assertThat(dir.resolve("android.jar")).hasContent("jar");
        assertThat(staging(platforms)).isEmpty();
    }

    @Test
    void a_zip_slip_entry_is_refused_and_the_staging_removed(@TempDir Path tmp) throws Exception {
        Path zip =
                Files.write(tmp.resolve("evil.zip"), zip("android-36/android.jar", "jar", "../../escaped.txt", "slip"));
        Path platforms = tmp.resolve("sdk/platforms");
        Path dir = platforms.resolve("android-36");

        assertThatThrownBy(() -> AndroidSdkInstaller.extractZip(zip, dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes");

        assertThat(dir).doesNotExist();
        assertThat(staging(platforms)).isEmpty();
        assertThat(tmp.resolve("escaped.txt")).doesNotExist();
    }

    @Test
    void a_truncated_zip_fails_and_the_staging_removed(@TempDir Path tmp) throws Exception {
        // Incompressible bytes keep the entry's data large, so a cut at the midpoint lands inside it
        // rather than in the central directory a streaming reader never reaches.
        byte[] noise = new byte[64 * 1024];
        new Random(7).nextBytes(noise);
        byte[] whole = zip("android-36/android.jar", new String(noise, StandardCharsets.ISO_8859_1));
        Path zip = Files.write(tmp.resolve("cut.zip"), Arrays.copyOf(whole, whole.length / 2));
        Path platforms = tmp.resolve("sdk/platforms");
        Path dir = platforms.resolve("android-36");

        assertThatThrownBy(() -> AndroidSdkInstaller.extractZip(zip, dir)).isInstanceOf(IOException.class);

        assertThat(dir).doesNotExist();
        assertThat(staging(platforms)).isEmpty();
    }

    private static Iterable<Path> staging(Path parent) throws IOException {
        if (!Files.isDirectory(parent)) return List.of();
        try (Stream<Path> s = Files.list(parent)) {
            return s.filter(p -> p.getFileName().toString().startsWith(".extract-"))
                    .toList();
        }
    }

    private static byte[] zip(String... nameThenBody) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < nameThenBody.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameThenBody[i]));
                zip.write(nameThenBody[i + 1].getBytes(StandardCharsets.ISO_8859_1));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
