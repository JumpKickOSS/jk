// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.BuildGraph;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-2087: the HTTP native job's target selection must respect the explicit module selection and
 * the {@code [native]} gate. The old "empty eligible ⇒ image every module" fallback launched
 * unrequested multi-minute native-image runs; eligibility was also computed over the
 * dirty-filtered subset, so a dirty non-native module flipped the fallback on.
 */
class EngineHttpFrontNativeEligibilityTest {

    private static Path module(Path tmp, String name, boolean nativeTable, boolean withMain) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(name)).toRealPath();
        String manifest = """
                group = "t"
                name = "%s"
                version = "0.1.0"
                jdk = 25
                java = 25
                """.formatted(name);
        if (nativeTable) manifest += "\n[native]\nenabled = true\n";
        Files.writeString(dir.resolve("jk.toml"), manifest);
        if (withMain) {
            Path src = Files.createDirectories(dir.resolve("src/main/java/t"));
            Files.writeString(
                    src.resolve("Main" + name + ".java"),
                    "package t; public class Main" + name + " { public static void main(String[] a) {} }\n");
        }
        return dir;
    }

    private static Map<Path, JkBuild> load(Path... dirs) throws Exception {
        Map<Path, JkBuild> m = new LinkedHashMap<>();
        for (Path d : dirs) m.put(d, JkBuildParser.parse(d.resolve("jk.toml")));
        return m;
    }

    @Test
    void selection_of_a_non_native_module_yields_empty_not_everything(@TempDir Path tmp) throws Exception {
        Path lib = module(tmp, "lib", false, false);
        Path cli = module(tmp, "cli", true, true);
        Map<Path, JkBuild> all = load(lib, cli);

        // Selecting only the non-native lib: nothing eligible — and never "everything".
        Set<Path> targets = EngineHttpFront.nativeEligibleTargets(all, Set.of(BuildGraph.canonicalPath(lib)));
        assertThat(targets).isEmpty();

        // Selecting the native cli works.
        assertThat(EngineHttpFront.nativeEligibleTargets(all, Set.of(BuildGraph.canonicalPath(cli))))
                .containsExactly(cli);
    }

    @Test
    void table_presence_anywhere_disables_the_unique_main_fallback(@TempDir Path tmp) throws Exception {
        // JK-2087 (b): eligibility must consider the FULL module set. When cli has a [native]
        // table, a unique-main lib is NOT eligible via fallback — even if only lib were dirty.
        Path lib = module(tmp, "lib", false, true);
        Path cli = module(tmp, "cli", true, true);
        Map<Path, JkBuild> all = load(lib, cli);
        assertThat(EngineHttpFront.nativeEligibleTargets(all, null)).containsExactly(cli);
    }

    @Test
    void no_table_anywhere_falls_back_to_unique_main_modules(@TempDir Path tmp) throws Exception {
        Path lib = module(tmp, "lib", false, false); // no main — ineligible
        Path app = module(tmp, "app", false, true); // unique main — eligible via fallback
        Map<Path, JkBuild> all = load(lib, app);
        assertThat(EngineHttpFront.nativeEligibleTargets(all, null)).containsExactly(app);
    }
}
