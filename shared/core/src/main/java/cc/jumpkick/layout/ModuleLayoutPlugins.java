// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Source roots a manifest declares beyond the fixed layout. Engine-only — pulls {@code JkBuildParser}. */
public final class ModuleLayoutPlugins {

    private ModuleLayoutPlugins() {}

    public static List<ModuleLayout.Root> pluginContributedRoots(Path moduleDir) {
        Path toml = ManifestPaths.manifestIn(moduleDir);
        if (!Files.isRegularFile(toml)) return List.of();
        try {
            JkBuild build = JkBuildParser.parse(toml);
            List<ModuleLayout.Root> out = new ArrayList<>();
            for (PluginContributions.SourceRoot root : PluginContributions.sourceRoots(build, moduleDir)) {
                out.add(new ModuleLayout.Root(
                        root.dir(), root.resource() ? ModuleLayout.Kind.RESOURCE : ModuleLayout.Kind.SOURCE));
            }
            return List.copyOf(out);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * Every input the manifest declares outside the layout's fixed trees, resolved against the
     * module dir and present on disk: {@code [build] extra-src}, plugin-contributed roots, and —
     * unless {@code skipTests} — {@code [test] extra-src} (a directory or a single source file)
     * and the {@code [test] fixtures} root. Absolute and normalized, declaration order.
     */
    public static List<Path> declaredRoots(Path moduleDir, JkBuild build, boolean skipTests) {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (String rel : build.build().extraSrc()) addDir(out, moduleDir, rel);
        for (PluginContributions.SourceRoot root : PluginContributions.sourceRoots(build, moduleDir)) {
            addDir(out, moduleDir, root.dir());
        }
        if (!skipTests) {
            for (String rel : build.build().testExtraSrc()) {
                Path p = moduleDir.resolve(rel);
                if (Files.isDirectory(p) || Files.isRegularFile(p))
                    out.add(p.toAbsolutePath().normalize());
            }
            String fixtures = build.build().fixtures();
            if (fixtures != null) addDir(out, moduleDir, fixtures);
        }
        return List.copyOf(out);
    }

    /**
     * Every root a module's inputs live under, each walked once: the layout's trees
     * ({@link ModuleLayout#fingerprintDirs}) plus the manifest's declared roots outside them
     * ({@link #declaredRoots}). A declared root under a layout root (a variant's
     * {@code src/demo/kotlin}) is already covered; a {@code [test] extra-src} entry may be a
     * single file rather than a directory. The preflight memo and a build-logic task's cache key
     * both read this list, so an edit under any of these roots moves both.
     */
    public static List<Path> fingerprintRoots(Path moduleDir, boolean skipTests) throws IOException {
        List<Path> roots = new ArrayList<>(ModuleLayout.fingerprintDirs(moduleDir, skipTests));
        Path toml = ManifestPaths.manifestIn(moduleDir);
        if (!Files.isRegularFile(toml)) return List.copyOf(roots);
        JkBuild manifest = JkBuildParser.parse(toml);
        for (Path declared : declaredRoots(moduleDir, manifest, skipTests)) {
            boolean covered = false;
            for (Path r : roots) {
                if (declared.startsWith(r)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) roots.add(declared);
        }
        return List.copyOf(roots);
    }

    private static void addDir(LinkedHashSet<Path> out, Path moduleDir, String rel) {
        Path p = moduleDir.resolve(rel);
        if (Files.isDirectory(p)) out.add(p.toAbsolutePath().normalize());
    }
}
