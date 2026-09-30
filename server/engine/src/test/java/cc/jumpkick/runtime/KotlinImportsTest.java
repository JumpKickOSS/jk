// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.CompileResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A kotlinc unresolved reference on an {@code import} line names the package the import needs. */
class KotlinImportsTest {

    @Test
    void a_class_import_is_its_package_and_a_star_import_is_the_path() {
        assertThat(KotlinImports.packages("import com.ninjasquad.springmockk.MockkBean", "ninjasquad"))
                .containsExactly("com.ninjasquad.springmockk");
        assertThat(KotlinImports.packages("import com.ninjasquad.springmockk.*", "springmockk"))
                .containsExactly("com.ninjasquad.springmockk");
        assertThat(KotlinImports.packages("import kotlinx.coroutines.runBlocking as block", "coroutines"))
                .containsExactly("kotlinx.coroutines");
        assertThat(KotlinImports.packages("  import `org`.acme.Outer.Inner", "acme"))
                .as("a nested class import also offers the package above the outer class")
                .containsExactly("org.acme.Outer", "org.acme");
    }

    @Test
    void a_line_that_is_not_an_import_of_the_reference_has_no_package() {
        assertThat(KotlinImports.packages("val x = ninjasquad.thing", "ninjasquad"))
                .isEmpty();
        assertThat(KotlinImports.packages("import org.acme.Thing", "ninjasquad"))
                .isEmpty();
        assertThat(KotlinImports.packages("import Thing", "Thing")).isEmpty();
    }

    @Test
    void the_source_line_is_read_from_the_file_the_header_names(@TempDir Path tmp) throws Exception {
        Path source = tmp.resolve("Main.kt");
        Files.writeString(source, "package app\n\nimport com.ninjasquad.springmockk.MockkBean\n");
        assertThat(KotlinImports.missingPackages(
                        error("e: " + source.toUri() + ":3:12 Unresolved reference 'ninjasquad'.")))
                .containsExactly("com.ninjasquad.springmockk");
        assertThat(KotlinImports.missingPackages(error(source.toUri() + ":1:9 Unresolved reference 'app'.")))
                .as("the package line is not an import")
                .isEmpty();
        assertThat(KotlinImports.missingPackages(error(source.toUri() + ":3:12 Type mismatch.")))
                .isEmpty();
    }

    private static CompileResult.Diagnostic error(String message) {
        return new CompileResult.Diagnostic(CompileResult.Severity.ERROR, null, 0, 0, message);
    }
}
