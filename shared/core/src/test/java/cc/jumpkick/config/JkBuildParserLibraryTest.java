// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JkBuildParserLibraryTest {

    private static final String PROJECT = """
            group = "com.ex"
            name = "lucene9-shaded"
            version = "1.0.0"
            java = 25
            """;

    @Test
    void library_assembly_is_refused() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + "[library]\nassembly = false\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("library.assembly: library jars ship thin; use [application] to relocate");
    }

    @Test
    void library_relocation_is_refused() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [library.relocate]
                "com.example" = "com.example.shaded"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("library.relocate: library jars ship thin; use [application] to relocate");
    }
}
