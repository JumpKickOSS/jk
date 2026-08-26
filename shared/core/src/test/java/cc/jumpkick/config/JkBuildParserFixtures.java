// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.library.LibraryCatalog;
import java.util.Map;

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

    static String graal(String value) {
        return PROJECT + """

                [native]
                graal = %s
                """.formatted(value);
    }

    private JkBuildParserFixtures() {}
}
