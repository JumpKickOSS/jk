// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.protocol.IdeWireModel;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The  shape: a sibling that is both a main dep ([dependencies] widget-core.workspace=true)
 * and a tests-kind dep ([test-dependencies] widget-core = { workspace = true, kind = "tests" }).
 * IdeOps collapses that to one {@link IdeWireModel#SCOPE_COMPILE_TEST_KIND} row; generators must
 * emit exactly one module entry (JDT rejects duplicates) plus the sibling test-classes attachment.
 */
class SiblingTestsKindGeneratorTest {

    private static IdeModule module(Path dir, String name) {
        Path target = dir.resolve("target");
        return new IdeModule(
                name,
                25,
                null,
                target.resolve("classes"),
                target.resolve("test-classes"),
                target.resolve("jdt/classes/main"),
                target.resolve("jdt/classes/test"),
                target.resolve("generated-sources/annotations"),
                target.resolve("generated-sources/annotations-test"));
    }

    @Test
    void intellij_main_plus_tests_kind_sibling_gets_one_module_entry(@TempDir Path ws) {
        Path appDir = ws.resolve("app");
        Path coreDir = ws.resolve("widget-core");
        IdeModule app = module(appDir, "app");
        IdeModule core = module(coreDir, "widget-core");
        Map<Path, IdeModule> all = new LinkedHashMap<>();
        all.put(appDir, app);
        all.put(coreDir, core);

        String iml = IntellijIdeGenerator.imlXml(
                appDir,
                app,
                List.of(new ModuleRef("widget-core", IdeWireModel.SCOPE_COMPILE_TEST_KIND)),
                List.of(),
                all,
                null,
                null,
                List.of());

        // One module ref, compile scope (main dep), plus the tests-kind classes attachment.
        assertThat(iml).containsOnlyOnce("module-name=\"widget-core\"");
        assertThat(iml).contains("<orderEntry type=\"module\" module-name=\"widget-core\" />");
        assertThat(iml).contains("widget-core (tests)");
        // the tests-kind library rides $MODULE_DIR$ like every other .iml path —
        // an absolute path breaks a moved or shared checkout.
        assertThat(iml).contains("file://$MODULE_DIR$/../widget-core/target/test-classes");
        assertThat(iml).doesNotContain("file://" + ws);
    }

    @Test
    void intellij_pure_tests_kind_sibling_is_test_scoped_with_attachment(@TempDir Path ws) {
        Path appDir = ws.resolve("app");
        Path coreDir = ws.resolve("widget-core");
        IdeModule app = module(appDir, "app");
        IdeModule core = module(coreDir, "widget-core");
        Map<Path, IdeModule> all = new LinkedHashMap<>();
        all.put(appDir, app);
        all.put(coreDir, core);

        String iml = IntellijIdeGenerator.imlXml(
                appDir,
                app,
                List.of(new ModuleRef("widget-core", IdeWireModel.SCOPE_TEST_KIND)),
                List.of(),
                all,
                null,
                null,
                List.of());

        assertThat(iml).containsOnlyOnce("module-name=\"widget-core\"");
        assertThat(iml).contains("module-name=\"widget-core\" scope=\"TEST\"");
        assertThat(iml).contains("widget-core (tests)");
    }

    @Test
    void vscode_main_plus_tests_kind_sibling_gets_one_src_entry(@TempDir Path ws) {
        Path appDir = ws.resolve("app");
        Path coreDir = ws.resolve("widget-core");
        IdeModule app = module(appDir, "app");
        IdeModule core = module(coreDir, "widget-core");
        Map<Path, IdeModule> all = new LinkedHashMap<>();
        all.put(appDir, app);
        all.put(coreDir, core);

        IdeModel model = new IdeModel(
                ws,
                "ws",
                all,
                all,
                Map.of(),
                Map.of(appDir, List.of(new ModuleRef("widget-core", IdeWireModel.SCOPE_COMPILE_TEST_KIND))),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                List.of(),
                null,
                null,
                null);

        String classpath = VscodeIdeGenerator.dotClasspath(model, appDir, app, 25);

        // Exactly one project src entry — JDT rejects "Build path contains duplicate entry" —
        // main-scoped (no test attribute on the src entry), plus the test-classes lib.
        assertThat(classpath).containsOnlyOnce("path=\"/widget-core\"");
        assertThat(classpath)
                .contains("<classpathentry combineaccessrules=\"false\" kind=\"src\" path=\"/widget-core\"/>");
        assertThat(classpath)
                .contains(core.testClassesDir()
                        .toAbsolutePath()
                        .normalize()
                        .toString()
                        .replace('\\', '/'));
    }
}
