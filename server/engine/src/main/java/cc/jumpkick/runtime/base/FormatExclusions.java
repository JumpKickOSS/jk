// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * The sources jk never formats or checks: a module's generated roots ({@code [build] generated-src}
 * and the source roots a plugin contributes as {@code generated}) and the workspace root's {@code
 * [format] exclude} globs, which match paths relative to that root. A file whose header says it is
 * generated is skipped by the formatter itself; these cover the files with no such header.
 */
public final class FormatExclusions {

    private final Path root;
    private final List<Path> roots = new ArrayList<>();
    private final List<PathMatcher> globs = new ArrayList<>();

    private FormatExclusions(Path root, List<String> excludeGlobs) {
        this.root = root.toAbsolutePath().normalize();
        for (String g : excludeGlobs) globs.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
    }

    /** {@code format}'s globs over {@code root}, and no generated roots yet. */
    public static FormatExclusions of(Path root, JkBuild.FormatConfig format) {
        return new FormatExclusions(root, format.exclude());
    }

    /** The exclusions of the tree at {@code root}: its own {@code [format]} table, when it has a manifest. */
    static FormatExclusions forRoot(Path root) {
        Path manifest = ManifestPaths.manifestIn(root);
        if (!Files.isRegularFile(manifest)) return new FormatExclusions(root, List.of());
        try {
            return of(root, JkBuildParser.parse(manifest).format());
        } catch (Exception e) {
            Log.debug("format exclusions: unreadable " + manifest + ": " + e.getMessage());
            return new FormatExclusions(root, List.of());
        }
    }

    /** Adds the generated roots of the module at {@code moduleDir}, read from {@code build}. */
    public FormatExclusions module(Path moduleDir, JkBuild build) {
        Path dir = moduleDir.toAbsolutePath().normalize();
        for (String rel : build.build().generatedSrc())
            roots.add(dir.resolve(rel).normalize());
        try {
            for (PluginContributions.SourceRoot r : PluginContributions.sourceRoots(build, moduleDir)) {
                if (r.generated()) roots.add(dir.resolve(r.dir()).normalize());
            }
        } catch (RuntimeException e) {
            // A plugin that cannot be read contributes no roots to this module's build either.
            Log.debug("format exclusions: plugin roots of " + moduleDir + ": " + e.getMessage());
        }
        return this;
    }

    /** Adds the generated roots of the module whose manifest sits in {@code moduleDir}, if it parses. */
    FormatExclusions moduleIn(Path moduleDir) {
        Path manifest = ManifestPaths.manifestIn(moduleDir);
        if (!Files.isRegularFile(manifest)) return this;
        try {
            return module(moduleDir, JkBuildParser.parse(manifest));
        } catch (Exception e) {
            Log.debug("format exclusions: unreadable " + manifest + ": " + e.getMessage());
            return this;
        }
    }

    /** Whether {@code file} is a source jk leaves as it is. */
    public boolean excludes(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        for (Path r : roots) {
            if (abs.startsWith(r)) return true;
        }
        if (globs.isEmpty() || !abs.startsWith(root)) return false;
        Path rel = root.relativize(abs);
        for (PathMatcher m : globs) {
            if (m.matches(rel)) return true;
        }
        return false;
    }

    /** {@code files} less the ones this excludes. */
    public List<Path> filter(List<Path> files) {
        if (roots.isEmpty() && globs.isEmpty()) return files;
        List<Path> out = new ArrayList<>(files.size());
        for (Path f : files) {
            if (!excludes(f)) out.add(f);
        }
        return out;
    }
}
