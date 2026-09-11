// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class JdkProgressLabelTest {

    @Test
    void compact_name_is_product_and_major() {
        assertThat(JdkProgressLabel.compactName(entry("Eclipse", "Temurin", 25)))
                .isEqualTo("Temurin 25");
        assertThat(JdkProgressLabel.compactName(entry("Oracle", "GraalVM", 25))).isEqualTo("GraalVM 25");
    }

    @Test
    void downloading_at_half_matches_the_build_row_shape() {
        String line = JdkProgressLabel.downloading("Temurin 25", 50, 100);
        assertThat(line).isEqualTo("downloading Temurin 25 ▰▰▰▰▰▱▱▱▱▱ 50%");
        JdkProgressLabel.Parsed p = requireNonNull(JdkProgressLabel.tryParse(line));
        assertThat(p.verb()).isEqualTo("downloading");
        assertThat(p.name()).isEqualTo("Temurin 25");
        assertThat(p.bar()).hasSize(JdkProgressLabel.BAR_WIDTH);
        assertThat(p.percent()).isEqualTo(50);
        assertThat(p.hasBar()).isTrue();
    }

    @Test
    void installing_is_full_bar() {
        String line = JdkProgressLabel.installing("Temurin 25");
        assertThat(line).isEqualTo("installing Temurin 25 ▰▰▰▰▰▰▰▰▰▰ 100%");
        JdkProgressLabel.Parsed p = requireNonNull(JdkProgressLabel.tryParse(line));
        assertThat(p.verb()).isEqualTo("installing");
        assertThat(p.percent()).isEqualTo(100);
    }

    @Test
    void unknown_total_omits_the_bar() {
        String line = JdkProgressLabel.downloading("Temurin 25", 0, 0);
        assertThat(line).isEqualTo("downloading Temurin 25");
        JdkProgressLabel.Parsed p = requireNonNull(JdkProgressLabel.tryParse(line));
        assertThat(p.hasBar()).isFalse();
        assertThat(p.percent()).isEqualTo(-1);
    }

    @Test
    void percent_floors_so_100_means_complete() {
        // 199/200 = 99.5 must display 99 — "100%" only when the last byte arrived.
        assertThat(JdkProgressLabel.percent(199, 200)).isEqualTo(99);
        assertThat(JdkProgressLabel.percent(200, 200)).isEqualTo(100);
        assertThat(JdkProgressLabel.percent(1, 200)).isEqualTo(0);
        assertThat(JdkProgressLabel.percent(-5, 200)).isEqualTo(0);
    }

    @Test
    void tryParse_rejects_unrelated_labels() {
        assertThat(JdkProgressLabel.tryParse("resolve JDK")).isNull();
        assertThat(JdkProgressLabel.tryParse("fetched org.foo:bar:jar:")).isNull();
    }

    private static JdkCatalog.Entry entry(String vendor, String product, int major) {
        return new JdkCatalog.Entry(
                vendor,
                product,
                product.toLowerCase() + "-" + major,
                major,
                major + ".0.0",
                true,
                false,
                List.of(),
                "linux",
                "x64",
                "tar.gz",
                URI.create("https://example.invalid/" + product + ".tar.gz"),
                "aa",
                1L,
                product.toLowerCase() + "-" + major,
                "");
    }
}
