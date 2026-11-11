// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fat jar embeds the lockfile's SBOM, so the SBOM is part of its key: a lock change that leaves
 * the module's bundled jars alone must not restore an {@code -all.jar} carrying the old SBOM.
 */
class AssemblySbomKeyTest {

    private static final JkBuild APP =
            JkBuild.builder(new Project("com.example", "app", "1.0.0", 25)).build();

    @Test
    void a_different_sbom_is_a_different_assembly_key(@TempDir Path module) {
        String before = key(module, "{\"components\":[\"a:1\"]}".getBytes(StandardCharsets.UTF_8));
        String after = key(module, "{\"components\":[\"a:1\",\"b:2\"]}".getBytes(StandardCharsets.UTF_8));
        String none = key(module, null);

        assertThat(before).isNotEqualTo(after).isNotEqualTo(none);
        assertThat(key(module, "{\"components\":[\"a:1\"]}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(before);
    }

    private static String key(Path module, byte @Nullable [] sbom) {
        return PackagingKeys.assembly(
                        module.resolve("target/app-all.jar"), module, APP, Map.of(), "classes", "contrib", "deps", sbom)
                .key();
    }
}
