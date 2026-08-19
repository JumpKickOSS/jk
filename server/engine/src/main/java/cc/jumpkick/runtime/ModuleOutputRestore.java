// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NullMarked;

/**
 * Restore missing PACKAGE (and optional native) outputs from the action cache when module inputs
 * are unchanged. Used when preflight finds fingerprint matches but jars/classes/binaries are gone
 * ({@code jk clean}, hand-deleted artifacts, partial wipes).
 */
@NullMarked
public final class ModuleOutputRestore {

    private ModuleOutputRestore() {}

    /**
     * True when this module's required PACKAGE outputs are absent: main jar missing, or a sourced
     * module with an empty classes tree.
     */
    public static boolean packageOutputsMissing(Path workspaceRoot, Path moduleDir, JkBuild build) {
        BuildLayout layout = BuildLayout.of(workspaceRoot, moduleDir, build);
        if (!Files.isRegularFile(layout.mainJar())) return true;
        if (hasMainSources(moduleDir, build) && !classesDirHasContent(layout.classesDir())) return true;
        if (build.assembly() && !Files.isRegularFile(layout.assemblyJar())) return true;
        if (build.nativeMode() == JkBuild.NativeMode.ALWAYS && !nativePresent(layout)) return true;
        return false;
    }

    /**
     * Restore missing outputs for {@code moduleDir}. Returns {@code true} when required outputs are
     * present afterward (or there was nothing to restore). Returns {@code false} on action-cache
     * miss so the caller can fall through to a normal RUN.
     */
    public static boolean restorePackageOutputs(Path workspaceRoot, Path moduleDir, JkBuild build, Path cacheRoot)
            throws IOException {
        if (!packageOutputsMissing(workspaceRoot, moduleDir, build)) return true;
        BuildLayout layout = BuildLayout.of(workspaceRoot, moduleDir, build);
        ActionCache ac =
                new ActionCache(JkStores.cacheCas(cacheRoot), cacheRoot.resolve("actions"), JkStores.storeCas());

        // Compile outputs → classes (and language-private dirs when present).
        restoreCompile(ac, "compile-main", layout.classesDir());
        restoreCompile(ac, TaskNames.COMPILE_KOTLIN, layout.kotlinClassesDir());
        // Groovy shares the merged classes dir as its task tag (see PlannerCompile).
        restoreCompile(ac, TaskNames.COMPILE_GROOVY, layout.classesDir());

        // If kotlin restored into kotlinClassesDir and classes is still empty, merge.
        if (!classesDirHasContent(layout.classesDir()) && classesDirHasContent(layout.kotlinClassesDir())) {
            copyTree(layout.kotlinClassesDir(), layout.classesDir());
        }

        // Packaged jars / native binary via last task pointer + artifact restore.
        restoreArtifact(ac, TaskNames.PACKAGE_JAR, layout.mainJar());
        if (build.assembly()) {
            restoreArtifact(ac, TaskNames.PACKAGE_ASSEMBLY, layout.assemblyJar());
        }
        if (build.nativeMode() == JkBuild.NativeMode.ALWAYS) {
            Path nativeOut = layout.nativeBinary();
            restoreArtifact(ac, TaskNames.NATIVE_IMAGE, nativeOut);
        }

        return !packageOutputsMissing(workspaceRoot, moduleDir, build);
    }

    /**
     * Restore every listed module. Returns module dirs that still lack required outputs (need a
     * full RUN). Restores run concurrently on the IO pool.
     */
    public static List<Path> restoreAll(Path workspaceRoot, List<Path> moduleDirs, Path cacheRoot) throws IOException {
        if (moduleDirs.isEmpty()) return List.of();
        List<CompletableFuture<Path>> futures = new ArrayList<>(moduleDirs.size());
        for (Path dir : moduleDirs) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            Path toml = dir.resolve("jk.toml");
                            if (!Files.isRegularFile(toml)) return dir;
                            JkBuild build = JkBuildParser.parse(toml);
                            if (!packageOutputsMissing(workspaceRoot, dir, build)) return null;
                            return restorePackageOutputs(workspaceRoot, dir, build, cacheRoot) ? null : dir;
                        } catch (Exception e) {
                            return dir;
                        }
                    },
                    JkThreads.io()));
        }
        List<Path> failed = new ArrayList<>();
        for (CompletableFuture<Path> f : futures) {
            Path miss = f.join();
            if (miss != null) failed.add(miss);
        }
        return failed;
    }

    private static void restoreCompile(ActionCache ac, String taskBase, Path outputDir) throws IOException {
        if (outputDir == null) return;
        if (classesDirHasContent(outputDir)) return;
        String taskId = ActionKey.qualifiedTaskId(taskBase, outputDir);
        var rec = ac.lastFor(taskId);
        if (rec.isEmpty()) return;
        ac.restore(rec.get(), outputDir);
    }

    private static void restoreArtifact(ActionCache ac, String taskBase, Path artifact) throws IOException {
        if (artifact == null) return;
        if (Files.isRegularFile(artifact)) return;
        String taskId = ActionKey.qualifiedTaskId(taskBase, artifact);
        var rec = ac.lastFor(taskId);
        if (rec.isEmpty()) {
            // Native shared-library builds tag the library path instead.
            return;
        }
        Path parent = artifact.getParent();
        if (parent == null) return;
        ac.restoreArtifacts(rec.get(), parent);
    }

    private static boolean nativePresent(BuildLayout layout) {
        return Files.isRegularFile(layout.nativeBinary())
                || Files.isRegularFile(Path.of(layout.nativeLibrary() + ".so"))
                || Files.isRegularFile(Path.of(layout.nativeLibrary() + ".dylib"))
                || Files.isRegularFile(Path.of(layout.nativeLibrary() + ".dll"));
    }

    private static boolean hasMainSources(Path moduleDir, JkBuild build) {
        try {
            boolean compact = CompileSupport.isSimpleLayout(build.project(), moduleDir);
            Path javaRoot = compact ? moduleDir.resolve("src") : moduleDir.resolve("src/main/java");
            return !CompileSupport.collectJavaSources(javaRoot).isEmpty()
                    || !CompileSupport.collectKotlinSources(moduleDir, compact).isEmpty()
                    || !CompileSupport.collectGroovySources(moduleDir, compact).isEmpty();
        } catch (Exception e) {
            return true; // fail safe: treat as sourced so missing classes counts
        }
    }

    static boolean classesDirHasContent(Path classesDir) {
        if (classesDir == null || !Files.isDirectory(classesDir)) return false;
        try (var walk = Files.walk(classesDir, 3)) {
            return walk.anyMatch(p -> Files.isRegularFile(p)
                    && p.getFileName() != null
                    && p.getFileName().toString().endsWith(".class"));
        } catch (IOException e) {
            return false;
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (var walk = Files.walk(from)) {
            for (Path src : (Iterable<Path>) walk::iterator) {
                Path rel = from.relativize(src);
                Path dest = to.resolve(rel);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else if (Files.isRegularFile(src)) {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
