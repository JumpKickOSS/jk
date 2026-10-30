// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.extract.Extractors;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.StatIdentity;
import cc.jumpkick.layout.WalkSkip;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The files of a workspace the graph reads: every file some extractor handles, found in one walk
 * from the root. Build output, VCS and IDE metadata, installed packages, front-end build output,
 * hidden directories other than {@code .github}, nested checkouts, lockfiles, minified bundles and
 * files over {@link #MAX_BYTES} stay out.
 */
public final class GraphSources {

    private GraphSources() {}

    /** Larger files are generated or data, not source a reader navigates. */
    public static final long MAX_BYTES = 1_000_000;

    private static final Set<String> SKIP_DIRS =
            Set.of("dist", "out", "coverage", "bower_components", "jspm_packages", "vendor", "__pycache__", "venv");
    private static final Set<String> SKIP_FILES =
            Set.of("package-lock.json", "yarn.lock", "pnpm-lock.yaml", ManifestNames.LOCK, "npm-shrinkwrap.json");

    /** One source file: repo-relative path with {@code /} separators, size and modification time. */
    public record Source(String rel, Path path, StatIdentity identity) {
        public long size() {
            return identity.size();
        }
    }

    public static List<Source> walk(Path root) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        List<Source> out = new ArrayList<>();
        PathUtil.forEachRegularFile(base, dir -> skipDir(base, dir), (file, attrs) -> {
            String rel = base.relativize(file).toString().replace('\\', '/');
            if (attrs.size() > MAX_BYTES || skipFile(rel) || Extractors.forPath(rel) == null) return;
            out.add(new Source(rel, file, StatIdentity.of(file, attrs)));
        });
        out.sort(Comparator.comparing(Source::rel));
        return out;
    }

    static boolean skipDir(Path root, Path dir) {
        if (dir.equals(root)) return false;
        Path name = dir.getFileName();
        if (name == null) return false;
        String s = name.toString();
        if (s.startsWith(".") && !s.equals(".github")) return true;
        return WalkSkip.workspaceKey(dir) || SKIP_DIRS.contains(s) || WalkSkip.nestedCheckout(dir);
    }

    static boolean skipFile(String rel) {
        String name = rel.substring(rel.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return SKIP_FILES.contains(name) || name.contains(".min.") || name.endsWith(".map") || name.endsWith(".d.ts");
    }
}
