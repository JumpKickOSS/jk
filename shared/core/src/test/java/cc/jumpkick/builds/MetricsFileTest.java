// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The class-wall grammar: one table per package, simple names inside, qualified names out. */
class MetricsFileTest {

    @Test
    void a_package_table_reads_back_as_qualified_class_names() {
        Map<String, Double> walls = new LinkedHashMap<>();
        MetricsFile.scan("""
                [mean]
                task.compile-java.wall-ms = 12

                [test-class."server/io"."com.example"]
                IoTest = 300

                [test-class."server/io"."com.example.db"]
                DbTest = 70

                [test-class."_"]
                RootTest = 20
                """, (section, key, v) -> {}, (dir, fqcn, ms) -> walls.put(dir + " " + fqcn, ms));
        assertThat(walls)
                .containsExactly(
                        entry("server/io com.example.IoTest", 300.0),
                        entry("server/io com.example.db.DbTest", 70.0),
                        entry("_ RootTest", 20.0));
    }

    /** The writer opens each package once, in class order, and a default-package class opens the module's own table. */
    @Test
    void class_walls_are_written_one_table_per_package_in_class_order() {
        Map<String, String> byClass = new LinkedHashMap<>();
        byClass.put("com.example.db.DbTest", "70");
        byClass.put("RootTest", "20");
        byClass.put("com.example.SlowTest", "5000");
        byClass.put("com.example.IoTest", "300");
        StringBuilder sb = new StringBuilder();
        MetricsFile.appendClassWalls(sb, "server/io", byClass);
        assertThat(sb.toString()).isEqualTo("""

                        [test-class."server/io"]
                        RootTest = 20

                        [test-class."server/io"."com.example"]
                        IoTest = 300
                        SlowTest = 5000

                        [test-class."server/io"."com.example.db"]
                        DbTest = 70
                        """);
    }

    /** A table whose rows are qualified names — the module's own table holding them — reads as those classes. */
    @Test
    void a_module_table_of_qualified_names_reads_as_those_classes() {
        Map<String, Double> walls = new LinkedHashMap<>();
        MetricsFile.scan("""
                [test-class."server/io"]
                com.example.IoTest = 300
                """, (section, key, v) -> {}, (dir, fqcn, ms) -> walls.put(dir + " " + fqcn, ms));
        assertThat(walls).containsExactly(entry("server/io com.example.IoTest", 300.0));
    }
}
