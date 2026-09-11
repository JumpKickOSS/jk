// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PathSource;
import cc.jumpkick.model.Workspace;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Shared jk.toml fixtures for the {@link JkBuildParser} test classes. */
final class JkBuildParserFixtures {

    static final String PROJECT = """
            group    = "com.example"
            name     = "widget"
            version  = "1.0.0"
            jdk      = 25
            java     = 25
            """;

    /** Synthetic catalog used so the tests don't drift with the bundled set. */
    static final LibraryCatalog TEST_CATALOG = LibraryCatalog.of(Map.of(
            "jackson-databind", new LibraryCatalog.Module("tools.jackson.core", "jackson-databind"),
            "picocli", new LibraryCatalog.Module("info.picocli", "picocli")));

    static String graal(@Nullable String value) {
        return PROJECT + """

                [native]
                graal = %s
                """.formatted(value);
    }

    /** The {@code [workspace]} table {@code build} parsed; a manifest without one fails the test here. */
    static Workspace workspaceOf(JkBuild build) {
        return Objects.requireNonNull(build.workspace(), "no [workspace] table");
    }

    /** The git source {@code dep} parsed; a dependency that is not a git one fails the test here. */
    static GitSource gitSourceOf(Dependency dep) {
        return Objects.requireNonNull(dep.gitSource(), () -> dep.module() + " has no git source");
    }

    /** The path source {@code dep} parsed; a dependency that is not a path one fails the test here. */
    static PathSource pathSourceOf(Dependency dep) {
        return Objects.requireNonNull(dep.pathSource(), () -> dep.module() + " has no path source");
    }

    private JkBuildParserFixtures() {}
}
