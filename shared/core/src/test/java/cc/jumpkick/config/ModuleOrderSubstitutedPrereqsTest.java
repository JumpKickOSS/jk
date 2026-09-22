// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A published POM edge onto a workspace member is served by the member, so the member's output is
 * on the consumer's classpath. The build order has to carry that edge too, or the two land in one
 * wave and the consumer can compile before the member exists.
 */
class ModuleOrderSubstitutedPrereqsTest {

    @TempDir
    Path tmp;

    @Test
    void a_member_behind_a_published_edge_is_built_first() throws Exception {
        // app names only the published `com.foo:middle`; middle's locked row names the member.
        Path app = module("app", """
                [dependencies]
                middle = "com.foo:middle:1.0"
                """);
        Path lib = module("lib", """
                [dependencies]
                """);
        Files.writeString(tmp.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "test"
                resolution-algorithm = "pubgrub-v1"

                [[artifact]]
                name     = "com.foo:middle:jar:"
                version  = "1.0"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:00"
                scopes   = ["main"]
                deps = [
                  "cc.jumpkick:lib:jar:@1.0",
                ]
                """);

        // Declaration order puts app first, so only the substituted edge can move lib ahead.
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(app, JkBuildParser.parse(app.resolve("jk.toml")));
        modules.put(lib, JkBuildParser.parse(lib.resolve("jk.toml")));

        assertThat(ModuleOrder.orderModules(modules))
                .as("without the workspace root there is no lock to read, so declaration order stands")
                .containsExactly(app, lib);
        assertThat(ModuleOrder.orderModules(tmp, modules))
                .as("middle's locked edge onto the member makes lib a prereq of app")
                .containsExactly(lib, app);
    }

    @Test
    void a_lock_that_names_no_member_leaves_the_order_alone() throws Exception {
        Path app = module("app", """
                [dependencies]
                middle = "com.foo:middle:1.0"
                """);
        Path lib = module("lib", """
                [dependencies]
                """);
        Files.writeString(tmp.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "test"
                resolution-algorithm = "pubgrub-v1"

                [[artifact]]
                name     = "com.foo:middle:jar:"
                version  = "1.0"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:00"
                scopes   = ["main"]
                deps = [
                  "com.foo:leaf:jar:@1.0",
                ]
                """);

        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(app, JkBuildParser.parse(app.resolve("jk.toml")));
        modules.put(lib, JkBuildParser.parse(lib.resolve("jk.toml")));

        assertThat(ModuleOrder.orderModules(tmp, modules)).containsExactly(app, lib);
        assertThat(PomSubstitution.membersBehindPublishedEdges(
                        tmp, modules.get(app), List.of(Scope.values()), Set.of("cc.jumpkick:lib")))
                .isEmpty();
    }

    private Path module(String name, String extra) throws Exception {
        Path dir = tmp.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "%s"
                version = "1.0"
                java = 25
                %s
                """.formatted(name, extra));
        return dir;
    }
}
