// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Two versions of one JUnit line on a test classpath — the shape behind {@code TestEngine with ID
 * 'junit-jupiter' failed to discover tests}: an exact pin on one artifact of the line held it back
 * while the rest of the line moved. Read off the lock, with the pin the manifest declared.
 */
final class JUnitLineConflict {

    /** The JUnit groups whose artifacts release together and must agree on a version. */
    static final Set<String> LINES = Set.of("org.junit.jupiter", "org.junit.platform", "org.junit.vintage");

    /**
     * @param text the conflict spelled out, one version per line with its artifacts
     * @param coordinate the artifact to ask {@code jk why} about: the pinned one, else the odd one out
     */
    record Conflict(String text, String coordinate) {}

    private JUnitLineConflict() {}

    /** The conflict on {@code project}'s test classpath per {@code lock}, or {@code null} when every line agrees. */
    static @Nullable Conflict describe(JkBuild project, @Nullable Lockfile lock) {
        if (lock == null) return null;
        List<Dependency> declared = new ArrayList<>(project.dependencies().of(Scope.TEST));
        declared.addAll(project.dependencies().of(Scope.TEST_DEV));
        return describe(declared, lock.artifacts());
    }

    static @Nullable Conflict describe(List<Dependency> declaredTest, List<Lockfile.Artifact> artifacts) {
        Map<String, Map<String, List<String>>> byGroupThenVersion = new LinkedHashMap<>();
        for (Lockfile.Artifact a : artifacts) {
            String ga = groupArtifact(a.name());
            String group = ga.substring(0, ga.indexOf(':'));
            if (!LINES.contains(group)) continue;
            if (!a.inAnyScope(Set.of(Scope.TEST, Scope.TEST_DEV, Scope.MAIN, Scope.EXPORT, Scope.RUNTIME))) continue;
            byGroupThenVersion
                    .computeIfAbsent(group, k -> new TreeMap<>())
                    .computeIfAbsent(a.version(), k -> new ArrayList<>())
                    .add(ga);
        }
        for (var line : byGroupThenVersion.entrySet()) {
            Map<String, List<String>> versions = line.getValue();
            if (versions.size() < 2) continue;
            return conflict(line.getKey(), versions, exactPins(declaredTest));
        }
        return null;
    }

    private static Conflict conflict(String group, Map<String, List<String>> versions, Map<String, String> pins) {
        // The odd one out first: the version carried by the fewest artifacts is the pin that
        // held it back, and its declared artifact is the coordinate jk why should be asked about.
        List<Map.Entry<String, List<String>>> ordered = new ArrayList<>(versions.entrySet());
        ordered.sort((x, y) -> Integer.compare(x.getValue().size(), y.getValue().size()));
        StringBuilder sb = new StringBuilder("Two versions of the " + group + " line on the test classpath:");
        String coordinate = null;
        for (var e : ordered) {
            sb.append("\n  ").append(e.getKey()).append(": ");
            List<String> names = new ArrayList<>();
            for (String ga : e.getValue()) {
                String pin = pins.get(ga);
                names.add(pin == null ? ga : ga + " (declared " + pin + " in [test-dependencies])");
                if (pin != null && coordinate == null) coordinate = ga;
            }
            sb.append(String.join(", ", names));
        }
        if (coordinate == null) coordinate = ordered.get(0).getValue().get(0);
        return new Conflict(sb.toString(), coordinate);
    }

    /** {@code group:artifact} → the exact selector the manifest declared for it, as written. */
    private static Map<String, String> exactPins(List<Dependency> declared) {
        Map<String, String> pins = new LinkedHashMap<>();
        for (Dependency d : declared) {
            if (d.version() instanceof VersionSelector.Exact exact) pins.put(d.module(), exact.raw());
        }
        return pins;
    }

    /** {@code group:artifact} from a lock row's {@code group:artifact:type:classifier} name. */
    static String groupArtifact(String lockName) {
        int first = lockName.indexOf(':');
        int second = first < 0 ? -1 : lockName.indexOf(':', first + 1);
        return second < 0 ? lockName : lockName.substring(0, second);
    }
}
