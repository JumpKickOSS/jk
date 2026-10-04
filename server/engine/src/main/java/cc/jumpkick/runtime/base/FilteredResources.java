// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.ResourceFilter;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A module's resource roots with {@code [resources]} applied: the layout root, the extra
 * {@code dirs}, and each {@code filtered} root expanded into {@code target/filtered-resources/<root>}
 * (test roots into {@code target/filtered-test-resources/<root>}), which the resource copy mirrors
 * like any other root. A staged file is rewritten only when its bytes change.
 */
public final class FilteredResources {

    public static final String MAIN_STAGE = "filtered-resources";
    public static final String TEST_STAGE = "filtered-test-resources";

    private FilteredResources() {}

    /** The main resource roots in copy order, filtered ones staged first; adds unexpanded names to {@code unresolved}. */
    public static List<Path> mainRoots(
            Path moduleDir, JkBuild build, BuildLayout layout, boolean compact, Set<String> unresolved)
            throws IOException {
        BuildBlock.Resources r = build.build().resources();
        Path layoutRoot = ModuleLayout.mainResourcesDir(moduleDir, compact);
        return roots(
                moduleDir,
                layoutRoot,
                r.dirs(),
                r.filtered(),
                layout.targetDir().resolve(MAIN_STAGE),
                build,
                unresolved);
    }

    /** The default test suite's extra roots: {@code test-dirs} and the staged {@code test-filtered}. */
    public static List<Path> testRoots(Path moduleDir, JkBuild build, BuildLayout layout, Set<String> unresolved)
            throws IOException {
        BuildBlock.Resources r = build.build().resources();
        return roots(
                moduleDir,
                null,
                r.testDirs(),
                r.testFiltered(),
                layout.targetDir().resolve(TEST_STAGE),
                build,
                unresolved);
    }

    /**
     * Whether {@code classes} differs from what the main roots would copy into it, read-only: the
     * forecast's question. A filtered root is compared by its expanded bytes.
     */
    public static boolean mainOutOfSync(Path moduleDir, JkBuild build, Path classes) {
        BuildBlock.Resources r = build.build().resources();
        Map<String, String> values = ResourceFilter.values(build);
        try {
            for (String rel : r.filtered()) {
                Path root = moduleDir.resolve(rel);
                if (Files.isDirectory(root) && expandedOutOfSync(root, values, classes)) return true;
            }
        } catch (IOException e) {
            return true;
        }
        return false;
    }

    /** The extra, unfiltered main roots that exist; the forecast compares them as copied. */
    public static List<Path> extraMainDirs(Path moduleDir, JkBuild build) {
        List<Path> out = new ArrayList<>();
        for (String rel : build.build().resources().dirs()) {
            Path dir = moduleDir.resolve(rel);
            if (Files.isDirectory(dir)) out.add(dir);
        }
        return out;
    }

    /** Whether {@code root}'s layout root is listed as filtered, so the raw copy must not be compared. */
    public static boolean layoutRootFiltered(Path moduleDir, JkBuild build, boolean compact) {
        Path layoutRoot = ModuleLayout.mainResourcesDir(moduleDir, compact).normalize();
        for (String rel : build.build().resources().filtered()) {
            if (moduleDir.resolve(rel).normalize().equals(layoutRoot)) return true;
        }
        return false;
    }

    private static List<Path> roots(
            Path moduleDir,
            @Nullable Path layoutRoot,
            List<String> dirs,
            List<String> filtered,
            Path stageBase,
            JkBuild build,
            Set<String> unresolved)
            throws IOException {
        Map<String, String> values = ResourceFilter.values(build);
        Set<Path> filteredRoots = new HashSet<>();
        for (String rel : filtered) filteredRoots.add(moduleDir.resolve(rel).normalize());
        List<Path> out = new ArrayList<>();
        if (layoutRoot != null && Files.isDirectory(layoutRoot) && !filteredRoots.contains(layoutRoot.normalize())) {
            out.add(layoutRoot);
        }
        for (String rel : dirs) {
            Path dir = moduleDir.resolve(rel);
            if (Files.isDirectory(dir) && !filteredRoots.contains(dir.normalize())) out.add(dir);
        }
        for (String rel : filtered) {
            Path root = moduleDir.resolve(rel);
            Path staged = stageBase.resolve(rel);
            if (!Files.isDirectory(root)) {
                PathUtil.deleteRecursively(staged);
                continue;
            }
            stage(root, staged, values, unresolved);
            out.add(staged);
        }
        return out;
    }

    /** Expand {@code root} into {@code staged}: changed files rewritten, files no longer in {@code root} removed. */
    static void stage(Path root, Path staged, Map<String, String> values, Set<String> unresolved) throws IOException {
        Set<Path> wanted = new HashSet<>();
        List<Path> files = new ArrayList<>();
        PathUtil.forEachRegularFile(root, (file, attrs) -> files.add(file));
        for (Path file : files) {
            Path rel = root.relativize(file);
            Path target = staged.resolve(rel.toString());
            wanted.add(target.normalize());
            byte[] bytes = ResourceFilter.filter(
                    String.valueOf(file.getFileName()), Files.readAllBytes(file), values, unresolved);
            if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) continue;
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        }
        if (!Files.isDirectory(staged)) return;
        List<Path> stale = new ArrayList<>();
        PathUtil.forEachRegularFile(staged, (file, attrs) -> {
            if (!wanted.contains(file.normalize())) stale.add(file);
        });
        for (Path file : stale) Files.deleteIfExists(file);
    }

    private static boolean expandedOutOfSync(Path root, Map<String, String> values, Path classes) throws IOException {
        List<Path> files = new ArrayList<>();
        PathUtil.forEachRegularFile(root, (file, attrs) -> files.add(file));
        for (Path file : files) {
            Path copy = classes.resolve(root.relativize(file).toString());
            if (!Files.isRegularFile(copy)) return true;
            byte[] want = ResourceFilter.filter(
                    String.valueOf(file.getFileName()), Files.readAllBytes(file), values, new HashSet<>());
            if (!Arrays.equals(want, Files.readAllBytes(copy))) return true;
        }
        return false;
    }
}
