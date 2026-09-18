// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The findings a module's {@code [lint] pmd-exclude} file leaves out of the report — the file
 * {@code maven-pmd-plugin} reads as {@code excludeFromFailureFile}: one binary class name per
 * line, {@code =}, the rules it may break, comma-separated; {@code #} lines and blank lines are
 * skipped. A violation is excluded when its {@code package.class} is listed with its rule.
 *
 * @param rulesByClass {@code package.Class -> rule names}
 */
record PmdExclusions(Map<String, Set<String>> rulesByClass) {

    /** No file: nothing is left out. */
    static final PmdExclusions NONE = new PmdExclusions(Map.of());

    static PmdExclusions read(Path file) throws IOException {
        Map<String, Set<String>> rules = new HashMap<>();
        for (String line : Files.readAllLines(file)) {
            String entry = line.strip();
            int eq = entry.indexOf('=');
            if (entry.isEmpty() || entry.startsWith("#") || eq < 1) continue;
            Set<String> names = rules.computeIfAbsent(entry.substring(0, eq).strip(), k -> new HashSet<>());
            for (String rule : entry.substring(eq + 1).split(",")) {
                if (!rule.isBlank()) names.add(rule.strip());
            }
        }
        return new PmdExclusions(Map.copyOf(rules));
    }

    /** Whether the violation of {@code rule} in {@code pkg.cls} is left out. */
    boolean excludes(String pkg, String cls, String rule) {
        String name = pkg.isEmpty() ? cls : pkg + "." + cls;
        return rulesByClass.getOrDefault(name, Set.of()).contains(rule);
    }

    /** How many classes the file lists. */
    int size() {
        return rulesByClass.size();
    }
}
