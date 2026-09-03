// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.testing.SourceText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code shared/wire} publishes only {@code cc.jumpkick.wire} types; server engine implementation
 * stays out of that namespace. The two module package graphs do not share packages and wire does
 * not import engine implementation.
 */
class WirePackageBoundaryTest {

    private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)\\s*;");
    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?(cc\\.jumpkick\\.[\\w.]+)\\s*;");

    private static final int WIRE_SOURCES_FLOOR = 50;
    private static final int ENGINE_SOURCES_FLOOR = 200;

    @Test
    void wire_production_types_live_in_the_wire_namespace() throws IOException {
        Path root = RepoRoot.find(WirePackageBoundaryTest.class);
        List<Path> sources = productionJava("shared/wire/src/main/java");
        assertThat(sources).as("wire production sources").hasSizeGreaterThanOrEqualTo(WIRE_SOURCES_FLOOR);

        List<String> foreign = new ArrayList<>();
        for (Path file : sources) {
            if (file.getFileName().toString().equals("package-info.java")) continue;
            String pkg = packageOf(file);
            if (pkg == null || !(pkg.equals("cc.jumpkick.wire") || pkg.startsWith("cc.jumpkick.wire."))) {
                foreign.add(SourceText.rel(root, file) + " -> " + pkg);
            }
        }
        assertThat(foreign)
                .as("shared/wire production classes outside cc.jumpkick.wire")
                .isEmpty();
    }

    @Test
    void wire_and_engine_do_not_share_packages() throws IOException {
        Set<String> wire = packages(productionJava("shared/wire/src/main/java"));
        Set<String> engine = packages(productionJava("server/engine/src/main/java"));
        assertThat(wire).as("wire packages").hasSizeGreaterThanOrEqualTo(4);
        assertThat(engine).as("engine packages").hasSizeGreaterThanOrEqualTo(8);
        assertThat(productionJava("server/engine/src/main/java"))
                .as("engine production sources")
                .hasSizeGreaterThanOrEqualTo(ENGINE_SOURCES_FLOOR);

        Set<String> shared = new TreeSet<>(wire);
        shared.retainAll(engine);
        assertThat(shared)
                .as("packages declared in both shared/wire and server/engine")
                .isEmpty();
    }

    @Test
    void wire_does_not_import_engine_implementation() throws IOException {
        Path root = RepoRoot.find(WirePackageBoundaryTest.class);
        List<Path> sources = productionJava("shared/wire/src/main/java");
        assertThat(sources).as("wire production sources").hasSizeGreaterThanOrEqualTo(WIRE_SOURCES_FLOOR);

        List<String> hits = new ArrayList<>();
        for (Path file : sources) {
            String code = SourceText.codeOnly(Files.readString(file));
            String rel = SourceText.rel(root, file);
            Matcher imports = IMPORT.matcher(code);
            while (imports.find()) {
                String imported = imports.group(1);
                if (isEngineImplementation(imported)) {
                    hits.add(rel + " imports " + imported);
                }
            }
        }
        assertThat(hits).as("wire sources importing engine implementation").isEmpty();
    }

    private static List<Path> productionJava(String fromRoot) throws IOException {
        return SourceText.javaUnder(RepoRoot.dir(WirePackageBoundaryTest.class, fromRoot));
    }

    private static Set<String> packages(List<Path> sources) throws IOException {
        Set<String> out = new TreeSet<>();
        for (Path file : sources) {
            if (file.getFileName().toString().equals("package-info.java")) continue;
            String pkg = packageOf(file);
            if (pkg != null) out.add(pkg);
        }
        return out;
    }

    private static @Nullable String packageOf(Path file) throws IOException {
        Matcher m = PACKAGE_DECL.matcher(Files.readString(file));
        return m.find() ? m.group(1) : null;
    }

    private static boolean isEngineImplementation(String imported) {
        return imported.equals("cc.jumpkick.engine")
                || imported.startsWith("cc.jumpkick.engine.")
                || imported.equals("cc.jumpkick.runtime")
                || imported.startsWith("cc.jumpkick.runtime.");
    }
}
