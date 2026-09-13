// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.CompileSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;

/**
 * Which of a module's PACKAGE outputs are missing while its inputs are unchanged: the main jar,
 * a sourced module's classes tree, the assembly jar, the native binary — {@code jk clean},
 * hand-deleted artifacts, partial wipes. A module that answers yes is scheduled, and its own plan
 * brings the outputs back: every step restores by the action key it computes from its current
 * inputs, so the tree and the jar that return are the ones this build would produce.
 */
@NullMarked
public final class ModuleOutputs {

    private ModuleOutputs() {}

    /**
     * True when this module's required PACKAGE outputs are absent: main jar missing, or a sourced
     * module with an empty classes tree.
     */
    public static boolean packageOutputsMissing(Path workspaceRoot, Path moduleDir, JkBuild build) {
        BuildLayout layout = BuildLayout.of(workspaceRoot, moduleDir, build);
        // Sources-less modules (jk-web: resources/test-only) plan no package-jar step at all —
        // demanding one flagged them restore-needed on every fully-cached build.
        boolean hasSources = hasMainSources(moduleDir, build);
        if (hasSources && !Files.isRegularFile(layout.mainJar())) return true;
        if (hasSources && !classesDirHasContent(layout.classesDir())) return true;
        if (build.assembly() && !Files.isRegularFile(layout.assemblyJar())) return true;
        if (build.nativeMode() == JkBuild.NativeMode.ALWAYS && !nativePresent(layout, build)) return true;
        return false;
    }

    private static boolean nativePresent(BuildLayout layout, JkBuild build) {
        // nativeBinary() already applies [native].name and the Windows .exe suffix.
        if (Files.isRegularFile(layout.nativeBinary())) return true;
        String named = build.nativeConfigOpt()
                .map(JkBuild.NativeConfig::name)
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);
        Path libBase = named != null
                ? layout.moduleTargetDir().resolve(named.startsWith("lib") ? named : "lib" + named)
                : layout.nativeLibrary();
        return Files.isRegularFile(Path.of(libBase + ".so"))
                || Files.isRegularFile(Path.of(libBase + ".dylib"))
                || Files.isRegularFile(Path.of(libBase + ".dll"));
    }

    private static boolean hasMainSources(Path moduleDir, JkBuild build) {
        try {
            boolean compact = CompileSupport.isSimpleLayout(build.project(), moduleDir);
            Path javaRoot = compact ? moduleDir.resolve("src") : moduleDir.resolve("src/main/java");
            return !CompileSupport.collectJavaSources(javaRoot).isEmpty()
                    || !PlannerCompile.mainKotlinSources(build, moduleDir, compact)
                            .isEmpty()
                    || !PlannerCompile.mainGroovySources(build, moduleDir, compact)
                            .isEmpty()
                    || !CompileSupport.collectScalaSources(moduleDir, compact).isEmpty();
        } catch (Exception e) {
            return true; // fail safe: treat as sourced so missing classes counts
        }
    }

    static boolean classesDirHasContent(Path classesDir) {
        if (classesDir == null || !Files.isDirectory(classesDir)) return false;
        // Unbounded walk (findFirst short-circuits at the first class file): a depth cap of 3
        // missed every package deeper than three segments — cc/jumpkick/... classes sit at
        // depth 4+, so every self-host module would read as "outputs missing" and the restore
        // path would re-run the whole workspace on a fully-cached build.
        try (var walk = Files.find(
                classesDir,
                Integer.MAX_VALUE,
                (p, attrs) -> attrs.isRegularFile()
                        && p.getFileName() != null
                        && p.getFileName().toString().endsWith(".class"))) {
            return walk.findFirst().isPresent();
        } catch (IOException e) {
            return false;
        }
    }
}
