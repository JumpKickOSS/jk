// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.RetiredKeys;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.tomlj.Toml;

/**
 * Every POM fixture this module imports renders a {@code jk.toml} with no retired key: the
 * importer's one writer, {@link JkBuildRenderer}, is held to the list the parser refuses.
 */
class ImportRetiredKeysTest {

    @TempDir
    Path temp;

    @TestFactory
    Stream<DynamicTest> every_pom_fixture_imports_without_a_retired_key() throws IOException, URISyntaxException {
        Path root = Path.of(Objects.requireNonNull(ImportRetiredKeysTest.class.getResource("/cc/jumpkick/mvn"))
                .toURI());
        List<Path> poms;
        try (Stream<Path> walk = Files.walk(root)) {
            poms = walk.filter(p -> p.getFileName().toString().endsWith("-pom.xml"))
                    .sorted()
                    .toList();
        }
        // Fake-green floor: the fixture tree held this many when the sweep was written.
        assertThat(poms).hasSizeGreaterThanOrEqualTo(10);
        return poms.stream()
                .map(pom -> DynamicTest.dynamicTest(root.relativize(pom).toString(), () -> {
                    Path dir = Files.createTempDirectory(temp, "import");
                    String rendered = JkBuildRenderer.render(
                            TestImporters.importXml(dir, Files.readString(pom)).jkBuild());
                    assertThat(RetiredKeys.found(Toml.parse(rendered)))
                            .as("%s imports as%n%s", pom.getFileName(), rendered)
                            .isEmpty();
                }));
    }
}
