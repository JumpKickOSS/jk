// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JavaRelease;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The request's {@code release} is the {@code --release} javac gets: a level below jk's floor of 17
 * is a request the toolchain JDK cross-compiles, the way a module whose dependencies need an API a
 * later JDK removed asks for; only a level no javac has a release for is refused.
 */
class CompileRequestTest {

    @Test
    void a_release_below_the_floor_is_a_request_javac_cross_compiles() {
        CompileRequest request = CompileRequest.builder()
                .sources(List.of(Path.of("A.java")))
                .release(11)
                .build();
        assertThat(request.release()).isEqualTo(11);
        assertThat(CompileRequest.builder().release(JavaRelease.OLDEST).build().release())
                .isEqualTo(JavaRelease.OLDEST);
    }

    @Test
    void a_release_no_javac_compiles_for_is_refused() {
        assertThatThrownBy(() -> CompileRequest.builder().release(7).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("release must be >= " + JavaRelease.OLDEST);
    }
}
