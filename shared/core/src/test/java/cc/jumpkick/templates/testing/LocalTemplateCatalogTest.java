// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.templates.testing;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.util.TestHomes;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LocalTemplateCatalogTest {

    /**
     * The repository is built before the first test class runs; git run there is git in a repository
     * a test made only when it lies under a temp root of the suite's JVMs (under jk, the sandbox
     * slot's), else every class of the suite keys by its module.
     */
    @Test
    void the_catalog_lies_in_the_sandbox_slots_temp_root() throws Exception {
        Optional<Path> slot = LocalTemplateCatalog.sandboxSlot();
        Path root = slot.isPresent()
                ? slot.get().toRealPath().resolve(TestHomes.TMP)
                : Path.of(System.getProperty("java.io.tmpdir")).toRealPath();

        Path repo = Path.of(URI.create(LocalTemplateCatalog.url()));

        assertThat(repo.toAbsolutePath().normalize()).startsWith(root);
        assertThat(repo.resolve("HEAD")).isRegularFile();
    }
}
