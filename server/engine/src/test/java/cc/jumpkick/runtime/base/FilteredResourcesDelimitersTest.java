// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A filtered root copies with the module's {@code [resources] delimiters}: with {@code @} alone, a
 * {@code ${name}} reaches the classes as written, for the application to resolve at run time.
 */
class FilteredResourcesDelimitersTest {

    @Test
    void the_copy_expands_only_the_delimiters_the_table_names(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                java    = 25

                [resources]
                filtered   = ["src/filter"]
                delimiters = ["@"]

                [resources.properties]
                port = "8080"
                """);
        Files.createDirectories(dir.resolve("src/filter"));
        Files.writeString(dir.resolve("src/filter/app.properties"), "runtime=${port}\nbuild=@port@\n");
        JkBuild build = JkBuildParser.parse(Files.readString(dir.resolve("jk.toml")));
        Path classes = Files.createDirectories(dir.resolve("target/classes"));

        Files.writeString(classes.resolve("app.properties"), "runtime=${port}\nbuild=8080\n");
        assertThat(FilteredResources.mainOutOfSync(dir, build, classes))
                .as("the copy leaves ${port} as written")
                .isFalse();

        Files.writeString(classes.resolve("app.properties"), "runtime=8080\nbuild=8080\n");
        assertThat(FilteredResources.mainOutOfSync(dir, build, classes)).isTrue();
    }
}
