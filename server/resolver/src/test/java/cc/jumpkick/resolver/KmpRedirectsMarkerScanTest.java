// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.repo.GradleModuleMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1767: the marker scan must be read-size independent — a marker straddling the first-chunk
 * boundary or pushed past it by a long license header must still be seen, otherwise a KMP root
 * silently degrades to the plain Maven view (duplicate/missing classes, no diagnostic).
 */
class KmpRedirectsMarkerScanTest {

    private static final String MARKER = GradleModuleMetadata.POM_MARKER;
    private static final int CHUNK = 8192;

    @Test
    void finds_marker_in_the_head(@TempDir Path tmp) throws Exception {
        Path pom = write(tmp, "<?xml version=\"1.0\"?>\n<!-- " + MARKER + " -->\n<project/>\n");
        assertThat(KmpRedirects.pomHasGradleMetadataMarker(pom)).isTrue();
    }

    @Test
    void finds_marker_straddling_the_first_chunk_boundary(@TempDir Path tmp) throws Exception {
        // Position the marker so it starts just before byte 8192 and ends after it.
        String prefix = "<?xml version=\"1.0\"?>\n<!-- ";
        StringBuilder sb = new StringBuilder(prefix);
        while (sb.length() < CHUNK - 10) sb.append('x');
        sb.append(' ').append(MARKER).append(" -->\n<project/>\n");
        Path pom = write(tmp, sb.toString());
        assertThat(KmpRedirects.pomHasGradleMetadataMarker(pom)).isTrue();
    }

    @Test
    void finds_marker_past_a_long_license_header(@TempDir Path tmp) throws Exception {
        // 20KB of license comment before the marker: the head is clearly still preamble, so the
        // scan must keep going instead of trusting the first chunk.
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>\n<!--\n");
        while (sb.length() < 20_000) sb.append("License line, all rights reserved.\n");
        sb.append("-->\n<!-- ").append(MARKER).append(" -->\n<project/>\n");
        Path pom = write(tmp, sb.toString());
        assertThat(KmpRedirects.pomHasGradleMetadataMarker(pom)).isTrue();
    }

    @Test
    void plain_maven_pom_has_no_marker(@TempDir Path tmp) throws Exception {
        Path pom = write(tmp, "<?xml version=\"1.0\"?>\n<project>\n  <artifactId>plain</artifactId>\n</project>\n");
        assertThat(KmpRedirects.pomHasGradleMetadataMarker(pom)).isFalse();
    }

    @Test
    void large_markerless_pom_stops_scanning_after_the_root_element(@TempDir Path tmp) throws Exception {
        // A multi-MB POM without the marker must answer false (and not by luck of a cap hit
        // exactly at EOF — the root element appears in the first chunk, so the scan stops early).
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>\n<project>\n");
        while (sb.length() < 3_000_000) sb.append("  <dependency>com.example:filler:1.0</dependency>\n");
        sb.append("</project>\n");
        Path pom = write(tmp, sb.toString());
        assertThat(KmpRedirects.pomHasGradleMetadataMarker(pom)).isFalse();
    }

    @Test
    void empty_file_has_no_marker(@TempDir Path tmp) throws Exception {
        Path pom = write(tmp, "");
        assertThat(KmpRedirects.pomHasGradleMetadataMarker(pom)).isFalse();
    }

    private static Path write(Path tmp, String content) throws Exception {
        Path pom = tmp.resolve("test.pom");
        Files.writeString(pom, content);
        return pom;
    }
}
