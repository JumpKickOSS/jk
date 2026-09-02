// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.engine.protocol.IdeWireModel;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.Scope;
import cc.jumpkick.util.MinimalXml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emits IntelliJ IDEA project files ({@code .idea/} + {@code *.iml}) from {@link IdeModel}. Modules
 * map to stable {@code jk-<vendor>-<level>} SDKs in the global {@code jdk.table.xml}.
 */
public final class IntellijIdeGenerator implements IdeGenerator {

    @Override
    public IdeTarget target() {
        return IdeTarget.IDEA;
    }

    @Override
    public IdeGeneration generate(IdeModel model) throws IOException {
        Path wsRoot = model.wsRoot();
        Map<Path, IdeModule> modules = model.modules();
        Map<Path, IdeModule> allModules = model.allModules();
        Map<String, LibDef> allLibs = model.allLibs();
        SdkRef defaultSdk = model.defaultSdk();
        Map<Path, SdkRef> sdkRefs = model.sdkRefs();
        Map<Path, List<Path>> processorJars = model.processorJars();

        Path ideaDir = wsRoot.resolve(".idea");
        Files.createDirectories(ideaDir);

        // Register the SDKs into every IntelliJ/Android Studio jdk.table.xml (best-effort; may be
        // clobbered if an IDE is open — see note below).
        Path ideConfigOverride = model.ideConfigDir();
        IntellijSdkRegistrar registrar = ideConfigOverride != null
                ? IntellijSdkRegistrar.of(
                        List.of(ideConfigOverride.resolve("JetBrains"), ideConfigOverride.resolve("Google")))
                : IntellijSdkRegistrar.shared();
        List<Path> touchedTables = registrar.register(model.sdkEntries());

        int files = 0;
        files += writeModulesXml(ideaDir, wsRoot, allModules);
        files += writeMiscXml(ideaDir, defaultSdk);
        files += writeCompilerXml(ideaDir, allModules, processorJars);

        Path libsDir = ideaDir.resolve("libraries");
        Files.createDirectories(libsDir);
        for (LibDef lib : allLibs.values()) {
            write(libsDir.resolve(lib.fileName() + ".xml"), libraryXml(lib));
            files++;
        }

        Path runDir = ideaDir.resolve("runConfigurations");
        for (Map.Entry<Path, IdeModule> me : allModules.entrySet()) {
            String main = me.getValue().mainClass();
            if (main != null) {
                Files.createDirectories(runDir);
                String modName = me.getValue().name();
                write(runDir.resolve(IdeSupport.sanitize(modName) + ".xml"), runConfigXml(modName, main));
                files++;
            }
        }
        // jk test suite run configurations (workspace root working dir).
        files += writeJkTestRunConfigs(runDir, wsRoot, allModules.keySet());

        // ---- generate *.iml for each module --------------------------------
        for (Map.Entry<Path, IdeModule> me : allModules.entrySet()) {
            Path moduleDir = me.getKey();
            IdeModule module = me.getValue();
            List<ModuleRef> modRefs = model.siblingRefs().getOrDefault(moduleDir, List.of());
            List<LibRef> libRefs = libRefs(model, moduleDir);
            write(
                    moduleDir.resolve(module.name() + ".iml"),
                    imlXml(
                            moduleDir,
                            module,
                            modRefs,
                            libRefs,
                            allModules,
                            sdkRefs.get(moduleDir),
                            defaultSdk,
                            processorJars.getOrDefault(moduleDir, List.of())));
            files++;
        }

        List<RichText> details = new ArrayList<>();
        if (!touchedTables.isEmpty()) {
            details.add(RichText.parse("Registered the [cyan]" + RichText.escape(defaultSdk.sdkName()) + "[/] JDK"));
        }
        details.add(RichText.parse(
                "Generated " + files + " project file" + (files == 1 ? "" : "s") + " in [path].idea[/]"));
        return IdeGeneration.of(details);
    }

    // =========================================================================
    // Per-module dependency lists (IntelliJ scope vocabulary)
    // =========================================================================

    private static List<LibRef> libRefs(IdeModel model, Path moduleDir) {
        List<LibRef> out = new ArrayList<>();
        for (LibEntry e : model.libEntries().getOrDefault(moduleDir, List.of())) {
            out.add(new LibRef(e.libName(), ideaScope(e.scopes())));
        }
        return out;
    }

    // =========================================================================
    // XML generators
    // =========================================================================

    private static int writeModulesXml(Path ideaDir, Path wsRoot, Map<Path, IdeModule> modules) throws IOException {
        StringBuilder sb = xmlHeader();
        sb.append("<project version=\"4\">\n");
        sb.append("  <component name=\"ProjectModuleManager\">\n");
        sb.append("    <modules>\n");

        for (Map.Entry<Path, IdeModule> me : modules.entrySet()) {
            Path iml = me.getKey().resolve(me.getValue().name() + ".iml");
            String rel = "$PROJECT_DIR$/" + wsRoot.relativize(iml).toString().replace('\\', '/');
            sb.append("      <module fileurl=\"file://")
                    .append(rel)
                    .append("\" filepath=\"")
                    .append(rel)
                    .append("\" />\n");
        }
        sb.append("    </modules>\n  </component>\n</project>\n");
        write(ideaDir.resolve("modules.xml"), sb.toString());
        return 1;
    }

    private static int writeMiscXml(Path ideaDir, SdkRef defaultSdk) throws IOException {
        StringBuilder sb = xmlHeader();
        sb.append("<project version=\"4\">\n");
        sb.append("  <component name=\"ProjectRootManager\" version=\"2\"");
        sb.append(" languageLevel=\"JDK_").append(defaultSdk.languageLevel()).append("\"");
        sb.append(" project-jdk-name=\"").append(esc(defaultSdk.sdkName())).append("\"");
        sb.append(" project-jdk-type=\"JavaSDK\">\n");
        // Project-wide compiler output lands under jk's build dir (target/), never IntelliJ's default
        // "out". Each module also overrides this with its own target/classes output (see imlXml), but
        // pointing the project default here too keeps anything not covered by a module out of an
        // "out"/"build" dir.
        sb.append("    <output url=\"file://$PROJECT_DIR$/target\" />\n");
        sb.append("  </component>\n</project>\n");
        write(ideaDir.resolve("misc.xml"), sb.toString());
        return 1;
    }

    private static int writeCompilerXml(Path ideaDir, Map<Path, IdeModule> modules, Map<Path, List<Path>> processorJars)
            throws IOException {
        StringBuilder sb = xmlHeader();
        sb.append("<project version=\"4\">\n");
        sb.append("  <component name=\"CompilerConfiguration\">\n");
        sb.append("    <bytecodeTargetLevel>\n");

        Iterable<Map.Entry<Path, IdeModule>> targets = modules.entrySet();

        for (Map.Entry<Path, IdeModule> me : targets) {
            int release = me.getValue().javaRelease();
            if (release > 0) {
                sb.append("      <module name=\"")
                        .append(esc(me.getValue().name()))
                        .append("\" targetLevel=\"")
                        .append(release)
                        .append("\" />\n");
            }
        }
        sb.append("    </bytecodeTargetLevel>\n");

        boolean anyProcessors = processorJars.values().stream().anyMatch(l -> !l.isEmpty());
        if (anyProcessors) {
            sb.append("    <annotationProcessing>\n");
            for (Map.Entry<Path, IdeModule> me : targets) {
                List<Path> procs = processorJars.getOrDefault(me.getKey(), List.of());
                if (procs.isEmpty()) continue;
                String mod = me.getValue().name();
                String genRel = me.getKey()
                        .relativize(me.getValue().generatedSourcesDir())
                        .toString()
                        .replace('\\', '/');
                String genTestRel = me.getKey()
                        .relativize(me.getValue().generatedTestSourcesDir())
                        .toString()
                        .replace('\\', '/');
                sb.append("      <profile name=\"jk-").append(esc(mod)).append("\" enabled=\"true\">\n");
                sb.append("        <sourceOutputDir name=\"")
                        .append(esc(genRel))
                        .append("\" />\n");
                sb.append("        <sourceTestOutputDir name=\"")
                        .append(esc(genTestRel))
                        .append("\" />\n");
                sb.append("        <outputRelativeToContentRoot value=\"true\" />\n");
                sb.append("        <module name=\"").append(esc(mod)).append("\" />\n");
                sb.append("        <processorPath useClasspath=\"false\">\n");
                for (Path jar : procs) {
                    sb.append("          <entry name=\"")
                            .append(repoJarUrl(jar))
                            .append("\" />\n");
                }
                sb.append("        </processorPath>\n");
                sb.append("      </profile>\n");
            }
            sb.append("    </annotationProcessing>\n");
        }

        sb.append("  </component>\n</project>\n");
        write(ideaDir.resolve("compiler.xml"), sb.toString());
        return 1;
    }

    private static String libraryXml(LibDef lib) {
        StringBuilder sb = xmlHeader();
        sb.append("<component name=\"libraryTable\">\n");
        sb.append("  <library name=\"").append(esc(lib.name())).append("\">\n");
        sb.append("    <CLASSES>\n");
        sb.append("      <root url=\"").append(repoJarUrl(lib.jarPath())).append("\" />\n");
        sb.append("    </CLASSES>\n");
        sb.append("    <JAVADOC />\n");
        sb.append("    <SOURCES>\n");
        if (lib.sourcesPath() != null) {
            sb.append("      <root url=\"")
                    .append(repoJarUrl(lib.sourcesPath()))
                    .append("\" />\n");
        }
        sb.append("    </SOURCES>\n");
        sb.append("  </library>\n</component>\n");
        return sb.toString();
    }

    static String imlXml(
            Path moduleDir,
            IdeModule module,
            List<ModuleRef> modRefs,
            List<LibRef> libRefs,
            Map<Path, IdeModule> allModules,
            SdkRef moduleSdk,
            SdkRef defaultSdk,
            List<Path> processorFiles) {
        boolean ownJdk =
                moduleSdk != null && defaultSdk != null && !moduleSdk.sdkName().equals(defaultSdk.sdkName());
        int langLevel = moduleSdk != null ? moduleSdk.languageLevel() : 0;

        StringBuilder sb = xmlHeader();
        sb.append("<module type=\"JAVA_MODULE\" version=\"4\">\n");
        sb.append("  <component name=\"NewModuleRootManager\" inherit-compiler-output=\"false\"");
        if (ownJdk && langLevel > 0) {
            sb.append(" LANGUAGE_LEVEL=\"JDK_").append(langLevel).append("\"");
        }
        sb.append(">\n");

        sb.append("    <output url=\"file://$MODULE_DIR$/")
                .append(moduleDir.relativize(module.classesDir()).toString().replace('\\', '/'))
                .append("\" />\n");
        sb.append("    <output-test url=\"file://$MODULE_DIR$/")
                .append(moduleDir.relativize(module.testClassesDir()).toString().replace('\\', '/'))
                .append("\" />\n");
        sb.append("    <exclude-output />\n");

        sb.append("    <content url=\"file://$MODULE_DIR$\">\n");
        // all TestSuites roots as test sources (not only default test/).
        for (IdeSourceRoots.Root root : IdeSourceRoots.of(moduleDir)) {
            if (root.resource()) {
                addResourceFolder(sb, moduleDir, root.relative(), root.test());
            } else {
                addSourceFolder(sb, moduleDir, root.relative(), root.test());
            }
        }

        Path gen = module.generatedSourcesDir();
        if (!processorFiles.isEmpty() || Files.isDirectory(gen)) {
            String genRel = moduleDir.relativize(gen).toString().replace('\\', '/');
            sb.append("      <sourceFolder url=\"file://$MODULE_DIR$/")
                    .append(genRel)
                    .append("\" isTestSource=\"false\" generated=\"true\" />\n");
            Path genTest = module.generatedTestSourcesDir();
            String genTestRel = moduleDir.relativize(genTest).toString().replace('\\', '/');
            sb.append("      <sourceFolder url=\"file://$MODULE_DIR$/")
                    .append(genTestRel)
                    .append("\" isTestSource=\"true\" generated=\"true\" />\n");
        }

        sb.append("      <excludeFolder url=\"file://$MODULE_DIR$/target\" />\n");
        sb.append("    </content>\n");

        if (ownJdk) {
            sb.append("    <orderEntry type=\"jdk\" jdkName=\"")
                    .append(esc(moduleSdk.sdkName()))
                    .append("\" jdkType=\"JavaSDK\" />\n");
        } else {
            sb.append("    <orderEntry type=\"inheritedJdk\" />\n");
        }
        sb.append("    <orderEntry type=\"sourceFolder\" forTests=\"false\" />\n");

        Map<String, IdeModule> byName = new LinkedHashMap<>();
        for (IdeModule m : allModules.values()) byName.put(m.name(), m);

        for (ModuleRef mr : modRefs) {
            boolean testScope =
                    IdeWireModel.SCOPE_TEST.equals(mr.scope()) || IdeWireModel.SCOPE_TEST_KIND.equals(mr.scope());
            boolean attachTests = IdeWireModel.SCOPE_TEST_KIND.equals(mr.scope())
                    || IdeWireModel.SCOPE_COMPILE_TEST_KIND.equals(mr.scope());
            sb.append("    <orderEntry type=\"module\" module-name=\"")
                    .append(esc(mr.name()))
                    .append("\"");
            if (testScope) sb.append(" scope=\"TEST\"");
            sb.append(" />\n");
            // kind=tests: sibling test classes as a module-library (Maven test-jar parity).
            // COMPILE+TEST_KIND keeps the single compile-scoped module entry above and only
            // attaches the test classes — never a second module ref for the same sibling.
            if (attachTests) {
                IdeModule sib = byName.get(mr.name());
                if (sib != null) {
                    appendTestsKindLibrary(sb, moduleDir, sib);
                }
            }
        }

        for (LibRef lr : libRefs) {
            sb.append("    <orderEntry type=\"library\" name=\"")
                    .append(esc(lr.name()))
                    .append("\" level=\"project\"");
            if (!"COMPILE".equals(lr.scope()))
                sb.append(" scope=\"").append(lr.scope()).append("\"");
            sb.append(" />\n");
        }

        sb.append("  </component>\n</module>\n");
        return sb.toString();
    }

    private static String runConfigXml(String moduleName, String mainClass) {
        StringBuilder sb = xmlHeader();
        sb.append("<component name=\"ProjectRunConfigurationManager\">\n");
        sb.append("  <configuration default=\"false\" name=\"")
                .append(esc(moduleName))
                .append("\" type=\"Application\" factoryName=\"Application\">\n");
        sb.append("    <option name=\"MAIN_CLASS_NAME\" value=\"")
                .append(esc(mainClass))
                .append("\" />\n");
        sb.append("    <module name=\"").append(esc(moduleName)).append("\" />\n");
        sb.append("    <method v=\"2\">\n");
        sb.append("      <option name=\"Make\" enabled=\"true\" />\n");
        sb.append("    </method>\n");
        sb.append("  </configuration>\n</component>\n");
        return sb.toString();
    }

    /**
     * Shell run configs: {@code jk test}, {@code jk test --all}, and one per non-default suite
     * discovered under any module.
     */
    static int writeJkTestRunConfigs(Path runDir, Path wsRoot, Set<Path> moduleDirs) throws IOException {
        Files.createDirectories(runDir);
        int n = 0;
        write(runDir.resolve("jk_test.xml"), shellRunConfigXml("jk test", "jk test", wsRoot));
        n++;
        write(runDir.resolve("jk_test_all.xml"), shellRunConfigXml("jk test (all suites)", "jk test --all", wsRoot));
        n++;
        LinkedHashSet<String> extraSuites = new LinkedHashSet<>();
        for (Path mod : moduleDirs) {
            for (String suite : IdeSourceRoots.discoveredSuites(mod)) {
                if (!TestSuites.DEFAULT.equals(suite)) extraSuites.add(suite);
            }
        }
        for (String suite : extraSuites) {
            String file = "jk_test_" + IdeSupport.sanitize(suite) + ".xml";
            write(runDir.resolve(file), shellRunConfigXml("jk test · " + suite, "jk test --suite " + suite, wsRoot));
            n++;
        }
        return n;
    }

    /** IntelliJ ShConfigurationType: run a shell command in the workspace root. */
    static String shellRunConfigXml(String name, String command, Path workingDir) {
        String cwd = workingDir.toAbsolutePath().normalize().toString().replace('\\', '/');
        StringBuilder sb = xmlHeader();
        sb.append("<component name=\"ProjectRunConfigurationManager\">\n");
        sb.append("  <configuration default=\"false\" name=\"")
                .append(esc(name))
                .append("\" type=\"ShConfigurationType\">\n");
        sb.append("    <option name=\"SCRIPT_TEXT\" value=\"")
                .append(esc(command))
                .append("\" />\n");
        sb.append("    <option name=\"INDEPENDENT_SCRIPT_PATH\" value=\"true\" />\n");
        sb.append("    <option name=\"SCRIPT_PATH\" value=\"\" />\n");
        sb.append("    <option name=\"SCRIPT_OPTIONS\" value=\"\" />\n");
        sb.append("    <option name=\"INDEPENDENT_SCRIPT_WORKING_DIRECTORY\" value=\"true\" />\n");
        sb.append("    <option name=\"SCRIPT_WORKING_DIRECTORY\" value=\"")
                .append(esc(cwd))
                .append("\" />\n");
        sb.append("    <option name=\"INDEPENDENT_INTERPRETER_PATH\" value=\"true\" />\n");
        sb.append("    <option name=\"INTERPRETER_PATH\" value=\"/bin/zsh\" />\n");
        sb.append("    <option name=\"INTERPRETER_OPTIONS\" value=\"\" />\n");
        sb.append("    <option name=\"EXECUTE_IN_TERMINAL\" value=\"true\" />\n");
        sb.append("    <option name=\"EXECUTE_SCRIPT_FILE\" value=\"false\" />\n");
        sb.append("    <method v=\"2\" />\n");
        sb.append("  </configuration>\n</component>\n");
        return sb.toString();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Sibling tests kind (kind=tests): attach the sibling's test classes dir as a TEST-scoped
     * module-library so IDE test compile sees Mill testModuleDeps / Maven test-jar helpers.
     * $MODULE_DIR$-relative like every other .iml path — an absolute path breaks a moved or
     * shared checkout.
     */
    private static void appendTestsKindLibrary(StringBuilder sb, Path moduleDir, IdeModule sibling) {
        Path testClasses = sibling.testClassesDir().toAbsolutePath().normalize();
        String classesUrl = "file://$MODULE_DIR$/"
                + moduleDir
                        .toAbsolutePath()
                        .normalize()
                        .relativize(testClasses)
                        .toString()
                        .replace('\\', '/');
        sb.append("    <orderEntry type=\"module-library\" scope=\"TEST\">\n");
        sb.append("      <library name=\"")
                .append(esc(sibling.name() + " (tests)"))
                .append("\">\n");
        sb.append("        <CLASSES>\n");
        sb.append("          <root url=\"").append(esc(classesUrl)).append("\" />\n");
        sb.append("        </CLASSES>\n");
        sb.append("        <JAVADOC />\n");
        sb.append("        <SOURCES />\n");
        sb.append("      </library>\n");
        sb.append("    </orderEntry>\n");
    }

    private static void addSourceFolder(StringBuilder sb, Path moduleDir, String relative, boolean test) {
        Path dir = moduleDir.resolve(relative);
        if (!Files.isDirectory(dir)) return;
        sb.append("      <sourceFolder url=\"file://$MODULE_DIR$/")
                .append(relative)
                .append("\" isTestSource=\"")
                .append(test)
                .append("\" />\n");
    }

    private static void addResourceFolder(StringBuilder sb, Path moduleDir, String relative, boolean test) {
        Path dir = moduleDir.resolve(relative);
        if (!Files.isDirectory(dir)) return;
        sb.append("      <sourceFolder url=\"file://$MODULE_DIR$/")
                .append(relative)
                .append("\" type=\"")
                .append(test ? "java-test-resource" : "java-resource")
                .append("\" />\n");
    }

    /** Convert a repo JAR path to IntelliJ's {@code jar://...!/} URL format ({@code $USER_HOME$}-relative). */
    private static String repoJarUrl(Path jar) {
        String absStr = jar.toAbsolutePath().normalize().toString().replace('\\', '/');
        String home = System.getProperty("user.home", "").replace('\\', '/');
        if (!home.isBlank() && absStr.startsWith(home + "/")) {
            absStr = "$USER_HOME$/" + absStr.substring(home.length() + 1);
        }
        return "jar://" + absStr + "!/";
    }

    /** Map jk scopes to the most permissive IntelliJ scope. */
    private static String ideaScope(List<Scope> scopes) {
        Set<Scope> s = EnumSet.noneOf(Scope.class);
        s.addAll(scopes);
        if (s.contains(Scope.EXPORT) || s.contains(Scope.MAIN)) return "COMPILE";
        if (s.contains(Scope.PROVIDED)) return "PROVIDED";
        if (s.contains(Scope.RUNTIME)) return "RUNTIME";
        if (s.contains(Scope.TEST)) return "TEST";
        return "COMPILE";
    }

    private static StringBuilder xmlHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        return sb;
    }

    private static String esc(String s) {
        return MinimalXml.escapeAttr(s);
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** A module's reference to an external library, tagged with the IntelliJ order-entry scope. */
    private record LibRef(String name, String scope) {}
}
