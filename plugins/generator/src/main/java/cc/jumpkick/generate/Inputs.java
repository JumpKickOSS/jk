// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Module-relative input patterns: a plain path names one file, a glob names every match under its base. */
final class Inputs {

    private Inputs() {}

    /** Every file the patterns match under {@code moduleDir}, absolute, sorted, each once. */
    static List<Path> expand(Path moduleDir, List<String> patterns) throws IOException {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (String pattern : patterns) {
            if (!isGlob(pattern)) {
                Path file = moduleDir.resolve(pattern);
                if (Files.isRegularFile(file)) out.add(file.toAbsolutePath().normalize());
                continue;
            }
            Path base = moduleDir.resolve(globBase(pattern));
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<Path> matched = new ArrayList<>();
            PathUtil.forEachRegularFile(base, (file, attrs) -> {
                if (matcher.matches(moduleDir.relativize(file)))
                    matched.add(file.toAbsolutePath().normalize());
            });
            matched.sort(null);
            out.addAll(matched);
        }
        return List.copyOf(out);
    }

    /**
     * The path before the first segment carrying a glob character — the directory a pattern is
     * anchored under, or the pattern itself when it has none. {@code "."} for a pattern whose
     * first segment already globs.
     */
    static String globBase(String pattern) {
        if (!isGlob(pattern)) return pattern;
        StringBuilder base = new StringBuilder();
        for (String segment : pattern.split("/")) {
            if (isGlob(segment)) break;
            if (!base.isEmpty()) base.append('/');
            base.append(segment);
        }
        return base.isEmpty() ? "." : base.toString();
    }

    static boolean isGlob(String text) {
        for (char c : text.toCharArray()) {
            if (c == '*' || c == '?' || c == '[' || c == '{') return true;
        }
        return false;
    }
}
