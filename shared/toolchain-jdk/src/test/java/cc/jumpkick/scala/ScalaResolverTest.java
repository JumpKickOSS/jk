// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scala;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScalaResolverTest {

    @Test
    void default_version_is_latest_stable_scala_3() {
        assertThat(ScalaResolver.DEFAULT_VERSION).isEqualTo("3.8.4");
        assertThat(ScalaResolver.DEFAULT_VERSION).startsWith("3.");
    }

    @Test
    void worker_modules_are_compiler_and_bridge_not_the_runtime_library() {
        assertThat(ScalaResolver.workerModules())
                .containsExactly(ScalaResolver.COMPILER_MODULE, ScalaResolver.BRIDGE_MODULE);
        assertThat(ScalaResolver.workerModules()).doesNotContain("org.scala-lang:scala3-library_3");
        assertThat(ScalaResolver.workerModules()).doesNotContain("org.scala-lang:scala-library");
    }
}
