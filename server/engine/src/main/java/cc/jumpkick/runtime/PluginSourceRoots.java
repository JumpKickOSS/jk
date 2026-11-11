// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.build.In;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The module's configured source roots, for a step that declared {@link In#sourceRoots()}: the
 * layout's main roots and {@code [build] extra-src}, then the default suite's test roots and
 * {@code [test] extra-src}, each only where the directory exists.
 */
record PluginSourceRoots(List<Path> main, List<Path> test) {

    /** What a step that did not declare the input sees. */
    static final PluginSourceRoots NONE = new PluginSourceRoots(List.of(), List.of());

    PluginSourceRoots {
        main = List.copyOf(main);
        test = List.copyOf(test);
    }

    /** The roots of the module at {@code moduleDir} when {@code inputs} declares them, else {@link #NONE}. */
    static PluginSourceRoots forInputs(List<String> inputs, Path moduleDir, JkBuild project) {
        if (!inputs.contains(In.sourceRoots().wireName())) return NONE;
        return of(moduleDir, project);
    }

    /** The configured roots of the module at {@code moduleDir}. */
    static PluginSourceRoots of(Path moduleDir, JkBuild project) {
        boolean compact = ModuleLayout.isCompact(moduleDir);
        LinkedHashSet<Path> main = new LinkedHashSet<>();
        for (Path root : ModuleLayout.mainSourceRoots(moduleDir, compact)) addIfDir(main, root);
        for (String rel : project.build().extraSrc()) addIfDir(main, moduleDir.resolve(rel));
        LinkedHashSet<Path> test = new LinkedHashSet<>();
        String suite = TestSuites.DEFAULT;
        for (Path root : TestSuites.javaRoots(moduleDir, compact, suite)) addIfDir(test, root);
        for (Path root : TestSuites.kotlinRoots(moduleDir, compact, suite)) addIfDir(test, root);
        for (Path root : TestSuites.groovyRoots(moduleDir, compact, suite)) addIfDir(test, root);
        for (Path root : TestSuites.scalaRoots(moduleDir, compact, suite)) addIfDir(test, root);
        for (String rel : project.build().testExtraSrc()) addIfDir(test, moduleDir.resolve(rel));
        return new PluginSourceRoots(new ArrayList<>(main), new ArrayList<>(test));
    }

    private static void addIfDir(LinkedHashSet<Path> roots, Path dir) {
        if (Files.isDirectory(dir)) roots.add(dir.toAbsolutePath().normalize());
    }
}
