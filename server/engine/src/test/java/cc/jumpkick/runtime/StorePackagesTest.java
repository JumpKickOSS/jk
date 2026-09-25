// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A package is answered from the store's Maven layout without reading every jar in the store. */
class StorePackagesTest {

    @Test
    void a_jar_under_the_packages_group_is_named_and_the_answer_is_cached(@TempDir Path store) throws Exception {
        Path version = store.resolve("repos/central/org/acme/acme-util/1.0");
        Files.createDirectories(version);
        jar(version.resolve("acme-util-1.0.jar"), "org/acme/util/Strings.class");
        jar(version.resolve("acme-util-1.0-sources.jar"), "org/acme/util/Strings.java");
        Path index = store.resolve("index");

        assertThat(StorePackages.find(store, "org.acme.util", index)).containsExactly("org.acme:acme-util");
        assertThat(index.resolve("store/org.acme.util.txt")).content().isEqualTo("org.acme:acme-util\n");
        Files.delete(version.resolve("acme-util-1.0.jar"));
        assertThat(StorePackages.find(store, "org.acme.util", index))
                .as("the cache answers after the jar is gone")
                .containsExactly("org.acme:acme-util");
    }

    @Test
    void the_group_and_artifact_are_the_directories_above_the_version(@TempDir Path tmp) {
        Path repo = tmp.resolve("central");
        Path jar = repo.resolve("org/springframework/spring-web/7.0.9/spring-web-7.0.9.jar");
        assertThat(StorePackages.coordinate(repo, jar)).isEqualTo("org.springframework:spring-web");
    }

    private static void jar(Path file, String entry) throws IOException {
        try (OutputStream os = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(os)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(new byte[] {(byte) 0xCA, (byte) 0xFE});
            zip.closeEntry();
        }
    }
}
