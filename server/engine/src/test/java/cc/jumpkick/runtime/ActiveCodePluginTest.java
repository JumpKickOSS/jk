// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module runs one plugin worker. A step-only plugin table beside a framework table is refused by
 * name — its steps would never run, and the first sign would be a compile error naming nothing.
 */
class ActiveCodePluginTest {

    @Test
    void a_generator_table_beside_a_framework_table_is_refused_by_name(@TempDir Path dir) {
        JkBuild build = JkBuildParser.parse("""
                name = "svc"
                group = "com.example"
                version = "1.0.0"
                java = 25

                [spring-boot]
                version = "4.1.1"

                [openapi]
                generator = "spring"
                """);

        assertThatThrownBy(() -> PluginBuild.activeCodePlugin(build, dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[openapi] cannot run beside [spring-boot]")
                .hasMessageContaining("its own module");
    }

    @Test
    void a_lone_generator_table_is_the_modules_worker(@TempDir Path dir) {
        JkBuild build = JkBuildParser.parse("""
                name = "svc"
                group = "com.example"
                version = "1.0.0"
                java = 25

                [openapi]
                generator = "spring"

                [platform-dependencies]
                spring-boot-dependencies = "4.1.1"
                """);

        assertThat(PluginBuild.activeCodePlugin(build, dir)).map(a -> a.manifest().id()).contains("openapi");
    }
}
