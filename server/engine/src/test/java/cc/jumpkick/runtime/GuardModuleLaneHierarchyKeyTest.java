// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.layout.BuildLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A module lane's key moves with what its type hierarchy resolves through: a sibling's ABI. */
class GuardModuleLaneHierarchyKeyTest {

    @Test
    void a_sibling_api_change_moves_the_lane_key_and_a_body_change_does_not(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("tree"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "t"
                name  = "root"
                version = "0.0.1"
                java = 25

                [workspace]
                modules = ["core", "app"]
                """);
        Path core = Files.createDirectories(root.resolve("core"));
        Files.writeString(core.resolve("jk.toml"), "group = \"t\"\nname = \"core\"\nversion = \"0.0.1\"\njava = 25\n");
        Path app = Files.createDirectories(root.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group = "t"
                name  = "app"
                version = "0.0.1"
                java = 25

                [dependencies]
                core = { workspace = true }
                """);
        BuildLayout coreLayout = BuildLayout.of(core, JkBuildParser.parse(core.resolve("jk.toml")));
        BuildLayout appLayout = BuildLayout.of(app, JkBuildParser.parse(app.resolve("jk.toml")));

        compile(tmp.resolve("core-1"), coreLayout.classesDir(), "Base", "public class Base { public void run() {} }");
        compile(tmp.resolve("app-src"), appLayout.classesDir(), "Main", "public class Main {}");
        FactsIndexing.ensure(appLayout.classesDir(), FactsIndexing.indexPath(appLayout.buildDir(), "main"));
        LoadResult load = PlannerGuards.rules(PlannerGuards.detectAt(root));
        String key = key(root, app, appLayout, load);

        compile(
                tmp.resolve("core-2"),
                coreLayout.classesDir(),
                "Base",
                "public class Base { public void run() { System.gc(); } }");
        assertThat(key(root, app, appLayout, load))
                .as("a body edit leaves the sibling's ABI, and so the lane key, as it was")
                .isEqualTo(key);

        compile(
                tmp.resolve("core-3"),
                coreLayout.classesDir(),
                "Base",
                "public class Base implements Runnable { public void run() {} }");
        assertThat(key(root, app, appLayout, load))
                .as("a new supertype on the sibling changes what app's classes are, though none of them moved")
                .isNotEqualTo(key);
    }

    private static String key(Path root, Path module, BuildLayout layout, LoadResult load) throws Exception {
        return Objects.requireNonNull(
                GuardKeys.probeModuleLane(root, module, layout, load, true).key(), "the lane's key");
    }

    private static void compile(Path src, Path out, String simpleName, String source) throws Exception {
        Path file = Files.createDirectories(src).resolve(simpleName + ".java");
        Files.writeString(file, source);
        Files.createDirectories(out);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "-d", out.toString(), "-proc:none", file.toString());
        assertThat(rc).as("javac").isZero();
    }
}
