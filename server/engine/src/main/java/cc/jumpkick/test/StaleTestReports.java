// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Removes the {@code TEST-<class>.xml} reports a module's run did not write and no longer stands
 * behind: a class whose {@code .class} file is gone from the test classes, and a class this run
 * reported into the other suite's directory. A report of a class that still exists but did not run
 * this time is kept.
 *
 * <p>Only a package-qualified name is judged by its class file: a name without a package, such as
 * another runner's {@code TEST-node.xml}, may not name a class at all.
 */
final class StaleTestReports {

    private static final String PREFIX = "TEST-";
    private static final String SUFFIX = ".xml";

    private StaleTestReports() {}

    /**
     * Delete the stale reports in {@code dirs}, a null one skipped; none without {@code
     * testClassesDir}. Best effort, never throws.
     *
     * @param written the classes this run wrote a report for
     * @param dirFor the directory each written class's report went to
     * @return how many files were deleted
     */
    static int prune(
            @Nullable Path testClassesDir,
            Collection<String> written,
            Function<String, Path> dirFor,
            @Nullable Path... dirs) {
        if (testClassesDir == null) return 0;
        Map<Path, Set<String>> writtenIn = new HashMap<>();
        Set<String> writtenAnywhere = new HashSet<>();
        for (String className : written) {
            String file = XmlTestReport.fileName(className);
            writtenIn
                    .computeIfAbsent(dirFor.apply(className), d -> new HashSet<>())
                    .add(file);
            writtenAnywhere.add(file);
        }
        int deleted = 0;
        for (Path dir : new LinkedHashSet<>(Arrays.asList(dirs))) {
            if (dir == null || !Files.isDirectory(dir)) continue;
            Set<String> here = writtenIn.getOrDefault(dir, Set.of());
            try {
                for (Path report : PathUtil.childFiles(dir)) {
                    String file = report.getFileName().toString();
                    if (!file.startsWith(PREFIX) || !file.endsWith(SUFFIX) || here.contains(file)) continue;
                    boolean stale = writtenAnywhere.contains(file) || classGone(testClassesDir, file);
                    if (stale && Files.deleteIfExists(report)) deleted++;
                }
            } catch (IOException | RuntimeException e) {
                // Best effort: a report left behind is what this run would have left anyway.
            }
        }
        return deleted;
    }

    /** Whether {@code file} names a package-qualified class with no class file under {@code testClassesDir}. */
    private static boolean classGone(Path testClassesDir, String file) {
        String className = file.substring(PREFIX.length(), file.length() - SUFFIX.length());
        List<String> parts = List.of(className.split("\\.", -1));
        if (parts.size() < 2) return false;
        for (String part : parts) {
            if (!identifier(part)) return false;
        }
        return !Files.exists(testClassesDir.resolve(String.join("/", parts) + ".class"));
    }

    private static boolean identifier(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }
}
