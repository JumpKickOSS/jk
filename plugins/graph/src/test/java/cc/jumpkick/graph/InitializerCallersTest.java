// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.guard.extract.FactsIndexing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A call made while a type initializes, or in a lambda its constructor builds, is the type's call. */
class InitializerCallersTest {

    private static final String PKG = "src/main/java/com/acme/shop/";

    private static final String CALC = """
            package com.acme.shop;

            public final class Calc {
                public static int compute() {
                    return 1;
                }

                public static int twice() {
                    return 2;
                }
            }
            """;

    private static final String HOLDER = """
            package com.acme.shop;

            import java.util.function.IntSupplier;

            public class Holder {
                static final int VALUE = Calc.compute();

                static {
                    IntSupplier later = () -> Calc.twice();
                }

                final IntSupplier deferred;

                Holder() {
                    deferred = () -> Calc.twice();
                }
            }
            """;

    private static String callers(Path dir, String address) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), "group = \"com.acme\"\nname = \"shop\"\nversion = \"1.0.0\"\n");
        Path pkg = Files.createDirectories(dir.resolve(PKG));
        Path classes = Files.createDirectories(dir.resolve("target/classes"));
        List<String> args = new ArrayList<>(List.of("-g", "-d", classes.toString()));
        for (Map.Entry<String, String> e :
                Map.of("Calc.java", CALC, "Holder.java", HOLDER).entrySet()) {
            Path f = pkg.resolve(e.getKey());
            Files.writeString(f, e.getValue());
            args.add(f.toString());
        }
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)))
                .isZero();
        FactsIndexing.Ensured e = FactsIndexing.ensure(classes, FactsIndexing.indexPath(dir.resolve("target"), "main"));
        GraphService.Workspace w = new GraphService.Workspace(
                dir,
                dir.resolve("target/graph"),
                dir.resolve("target/jk-cache"),
                List.of(new GraphBuild.ClassIndex(
                        e.index(), "", "main", e.bodyDigest(), e.classes(), Set.of(), List.of())),
                Modules.NONE,
                new ScriptReader(null, null),
                Map.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                ModuleReach.UNKNOWN);
        GraphService.Request r = new GraphService.Request(
                "usages", List.of(address), "bfs", 3, 4000, List.of(), false, 8, null, false, false);
        return GraphService.run(w, r, GraphBuild.Progress.NONE).text();
    }

    @Test
    void aStaticFieldsInitializerIsACallerUnderItsType(@TempDir Path dir) throws IOException {
        assertThat(callers(dir, "Calc.compute"))
                .startsWith("Callers of Calc.compute(): 1 in main sources, 0 in tests.")
                .contains("  Holder  src/main/java/com/acme/shop/Holder.java:6", "L6| static final int VALUE");
    }

    @Test
    void aLambdaInAStaticBlockOrAConstructorCallsForItsType(@TempDir Path dir) throws IOException {
        assertThat(callers(dir, "Calc.twice"))
                .startsWith("Callers of Calc.twice(): 1 in main sources, 0 in tests.")
                .contains("  Holder  src/main/java/com/acme/shop/Holder.java:")
                .contains("L9| IntSupplier later = () -> Calc.twice();", "L15| deferred = () -> Calc.twice();");
    }
}
