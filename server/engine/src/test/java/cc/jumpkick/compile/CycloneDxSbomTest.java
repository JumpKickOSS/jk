// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CycloneDxSbomTest {

    @Test
    void write_is_stable_regardless_of_component_order() {
        var guava = new CycloneDxSbom.Component(
                "com.google.guava",
                "guava",
                "33.6.0-jre",
                "dc573e1fca4fd5454f4a5fd3d7da2df03002876a4175bafc14a95980dd7713b3");
        var annotations = new CycloneDxSbom.Component(
                "com.google.errorprone",
                "error_prone_annotations",
                "2.50.0",
                "4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a");
        byte[] solverOrder = CycloneDxSbom.write("io.example", "app", "0.1.0", List.of(guava, annotations));
        byte[] lockOrder = CycloneDxSbom.write("io.example", "app", "0.1.0", List.of(annotations, guava));
        assertThat(solverOrder).isEqualTo(lockOrder);

        String json = new String(lockOrder, StandardCharsets.UTF_8);
        int errorProne = json.indexOf("error_prone_annotations");
        int guavaName = json.indexOf("\"name\": \"guava\"");
        assertThat(errorProne)
                .as("components sorted by group then artifact")
                .isGreaterThan(0)
                .isLessThan(guavaName);
    }
}
