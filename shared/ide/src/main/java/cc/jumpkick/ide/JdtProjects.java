// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.ide;

import cc.jumpkick.model.Scope;
import cc.jumpkick.wire.protocol.IdeWireModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One Eclipse JDT project per module, with the raw classpath JDT gets for it: the single reading
 * of an {@link IdeModel} for the Java language server. {@link VscodeIdeGenerator} writes it as
 * {@code .project}/{@code .classpath} files; the engine's {@code GET /api/project/model} serves it
 * as JSON to the VS Code extension's JDT LS importer, which applies the entries as they are.
 */
public final class JdtProjects {

    /** Eclipse JRE container VM type; JDT binds the {@code JavaSE-<n>} EE to a configured runtime. */
    static final String STANDARD_VM_TYPE = "org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType";

    static final String JRE_CONTAINER = "org.eclipse.jdt.launching.JRE_CONTAINER";

    /** Highest Java execution-environment name JDT is assumed to know; newer levels clamp to it. */
    static final int MAX_KNOWN_EE = 25;

    private JdtProjects() {}

    /**
     * A module as a JDT project.
     *
     * @param level the language level the module compiles at (the SDK's, else {@code java =})
     * @param jdkHome the JDK the module builds with, or {@code null} when the model names none
     * @param output the default output folder, inside {@code dir}
     */
    public record Project(
            String name, Path dir, int level, @Nullable Path jdkHome, Path output, List<Entry> entries) {

        /** The compliance string JDT wants for {@link #level}: {@code 25}, {@code 1.8}. */
        public String compliance() {
            int l = Math.min(level, MAX_KNOWN_EE);
            return l <= 8 ? "1." + l : String.valueOf(l);
        }
    }

    /** A raw classpath entry, in the order JDT gets them. */
    public sealed interface Entry {}

    /**
     * A source folder inside the project.
     *
     * @param generated an annotation-processor output folder, which may not exist yet
     */
    public record Source(Path path, Path output, boolean test, boolean generated) implements Entry {}

    /** Another module's project, by name. */
    public record ProjectRef(String name, boolean test) implements Entry {}

    /** A jar or class folder outside the project; {@code sources} is its source attachment. */
    public record Library(Path path, @Nullable Path sources, boolean test) implements Entry {}

    /** A sibling module's test classes, on this project's test classpath ({@code kind = "tests"}). */
    public record SiblingTests(Path classes) implements Entry {}

    /** The JRE container for the project's execution environment. */
    public record Container(String path) implements Entry {}

    /** Every module of {@code model}, in model order. */
    public static List<Project> of(IdeModel model) {
        Map<String, IdeModule> byName = new LinkedHashMap<>();
        for (IdeModule m : model.allModules().values()) byName.put(m.name(), m);
        List<Project> out = new ArrayList<>();
        for (Map.Entry<Path, IdeModule> me : model.allModules().entrySet()) {
            out.add(project(model, me.getKey(), me.getValue(), byName));
        }
        return List.copyOf(out);
    }

    private static Project project(IdeModel model, Path moduleDir, IdeModule module, Map<String, IdeModule> byName) {
        SdkRef sdk = model.sdkRefs().get(moduleDir);
        int level = sdk != null ? sdk.languageLevel() : module.javaRelease();
        if (level <= 0) level = MAX_KNOWN_EE;
        Path jdkHome = sdk != null ? sdk.javaHome() : null;
        Path outMain = module.jdtClassesDir();
        Path outTest = module.jdtTestClassesDir();
        List<Entry> entries = new ArrayList<>();

        // Every source root on disk, test suites as test sources.
        for (IdeSourceRoots.Root root : IdeSourceRoots.of(moduleDir)) {
            Path dir = moduleDir.resolve(root.relative());
            if (!Files.isDirectory(dir)) continue;
            entries.add(new Source(dir, root.test() ? outTest : outMain, root.test(), false));
        }

        // Annotation-processor output, when the module declares processors or the folder exists.
        List<Path> procs = model.processorJars().getOrDefault(moduleDir, List.of());
        if (!procs.isEmpty() || Files.isDirectory(module.generatedSourcesDir())) {
            entries.add(new Source(module.generatedSourcesDir(), outMain, false, true));
            entries.add(new Source(module.generatedTestSourcesDir(), outTest, true, true));
        }

        // Live cross-module source dependencies. A tests-kind edge adds the sibling's test classes
        // to the test classpath; COMPILE+TEST_KIND keeps one project entry, since JDT rejects two.
        for (ModuleRef mr : model.siblingRefs().getOrDefault(moduleDir, List.of())) {
            boolean testScope =
                    IdeWireModel.SCOPE_TEST.equals(mr.scope()) || IdeWireModel.SCOPE_TEST_KIND.equals(mr.scope());
            boolean attachTests = IdeWireModel.SCOPE_TEST_KIND.equals(mr.scope())
                    || IdeWireModel.SCOPE_COMPILE_TEST_KIND.equals(mr.scope());
            entries.add(new ProjectRef(mr.name(), testScope));
            IdeModule sib = byName.get(mr.name());
            if (attachTests && sib != null) entries.add(new SiblingTests(sib.testClassesDir()));
        }

        int ee = Math.min(level, MAX_KNOWN_EE);
        entries.add(new Container(
                JRE_CONTAINER + "/" + STANDARD_VM_TYPE + "/JavaSE-" + (ee <= 8 ? "1." + ee : String.valueOf(ee))));

        for (LibEntry entry : model.libEntries().getOrDefault(moduleDir, List.of())) {
            LibDef def = model.allLibs().get(entry.libName());
            if (def == null) continue;
            entries.add(new Library(def.jarPath(), def.sourcesPath(), isTestOnly(entry.scopes())));
        }
        return new Project(module.name(), moduleDir, level, jdkHome, outMain, List.copyOf(entries));
    }

    /** True when TEST is the only classpath-relevant scope: Eclipse's {@code test} attribute. */
    static boolean isTestOnly(List<Scope> scopes) {
        if (!scopes.contains(Scope.TEST)) return false;
        for (Scope s : scopes) {
            if (s == Scope.MAIN || s == Scope.EXPORT || s == Scope.PROVIDED || s == Scope.RUNTIME) return false;
        }
        return true;
    }
}
