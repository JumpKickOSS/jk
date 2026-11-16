// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The generated sources of a compile are unannotated to NullAway; hand-written ones are untouched. */
class GeneratedNullnessTest {

    private static final String NULLAWAY =
            "-Xplugin:ErrorProne -XepDisableAllChecks -Xep:NullAway:ERROR -XepOpt:NullAway:AnnotatedPackages=com.acme";

    @Test
    void every_top_level_class_under_the_build_output_is_named_unannotated(@TempDir Path module) throws Exception {
        Path target = module.resolve("target");
        Path generated = write(target.resolve("plugin/generate-jooq/out/com/acme/db/tables/Shipments.java"), """
                /* generated */
                package com.acme.db.tables;

                public class Shipments {}
                """);
        Path info = write(
                target.resolve("plugin/generate-jooq/out/com/acme/db/package-info.java"), "package com.acme.db;\n");
        Path written = write(module.resolve("src/main/java/com/acme/Main.java"), "package com.acme;\nclass Main {}\n");

        List<String> options = GeneratedNullness.withGeneratedUnannotated(
                List.of("-parameters", NULLAWAY), List.of(written, generated, info), target);

        assertThat(options)
                .containsExactly(
                        "-parameters", NULLAWAY + " -XepOpt:NullAway:UnannotatedClasses=com.acme.db.tables.Shipments");
    }

    @Test
    void a_compile_without_nullaway_or_without_generated_sources_is_unchanged(@TempDir Path module) throws Exception {
        Path target = module.resolve("target");
        Path generated = write(target.resolve("gen/x/Y.java"), "package x;\nclass Y {}\n");
        Path written = write(module.resolve("src/main/java/x/Z.java"), "package x;\nclass Z {}\n");
        List<String> plain = List.of("-parameters");
        List<String> off = List.of("-Xplugin:ErrorProne -Xep:NullAway:OFF");

        assertThat(GeneratedNullness.withGeneratedUnannotated(plain, List.of(generated), target))
                .isSameAs(plain);
        assertThat(GeneratedNullness.withGeneratedUnannotated(off, List.of(generated), target))
                .isSameAs(off);
        List<String> on = List.of(NULLAWAY);
        assertThat(GeneratedNullness.withGeneratedUnannotated(on, List.of(written), target))
                .isSameAs(on);
    }

    @Test
    void a_declared_unannotated_list_is_extended(@TempDir Path module) throws Exception {
        Path target = module.resolve("target");
        Path generated = write(target.resolve("gen/x/Y.java"), "package x;\nclass Y {}\n");
        String declared = NULLAWAY + " -XepOpt:NullAway:UnannotatedClasses=a.B";

        assertThat(GeneratedNullness.withGeneratedUnannotated(List.of(declared), List.of(generated), target))
                .containsExactly(NULLAWAY + " -XepOpt:NullAway:UnannotatedClasses=a.B,x.Y");
    }

    private static Path write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }
}
