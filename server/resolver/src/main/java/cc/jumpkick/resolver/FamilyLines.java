// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.Scope;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * One library family on two lines inside a workspace member's classpath, said at lock time.
 *
 * <p>A member with no {@code [platform-dependencies]} table reads the workspace's plain rows, and
 * nothing aligns them for it: two artifacts of one family — {@code maven-resolver-api} and
 * {@code maven-resolver-transport-http}, Jackson's databind and a dataformat — can land on
 * different lines because different members' declarations pulled them, and the member that reads
 * both meets the mismatch at runtime. The converge-versions guard holds one version per artifact;
 * this holds one line per family, on the rows a member actually reads, and names the member, the
 * family and each artifact's version before a runtime failure does. A member under its own
 * platform table is aligned by it and is not judged.
 *
 * <p>A family is a group plus the first two hyphen-separated segments of the artifact name:
 * {@code maven-resolver-api}, {@code maven-resolver-transport-http} and {@code maven-resolver-named-locks}
 * share {@code maven-resolver}; {@code commons-io} and {@code commons-lang3} are each their own,
 * and a name without a hyphen belongs to none.
 */
final class FamilyLines {

    private FamilyLines() {}

    /** The scopes whose rows reach a JVM the member runs — its main, run and test classpaths. */
    private static final List<Scope> CLASSPATH_SCOPES =
            List.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME, Scope.PROVIDED, Scope.DEV, Scope.TEST, Scope.TEST_DEV);

    /**
     * One warning per member per family whose rows in {@code lock}, as that member reads them,
     * resolve to more than one version; members that declare a platform table are skipped.
     */
    static List<String> warnings(Lockfile lock, List<LockOrchestrator.Member> members) {
        List<String> out = new ArrayList<>();
        for (LockOrchestrator.Member member : members) {
            if (!member.manifest().dependencies().of(Scope.PLATFORM).isEmpty()) continue;
            Lockfile mine = lock.forMember(member.path());
            for (Map.Entry<String, Map<String, String>> family :
                    mixedFamilies(closure(mine, member.manifest())).entrySet()) {
                out.add(warning(member.path(), family.getKey(), family.getValue()));
            }
        }
        return out;
    }

    /** The rows the member's declared dependencies reach, walking the lock's edges. */
    private static List<Lockfile.Artifact> closure(Lockfile lock, JkBuild manifest) {
        Map<String, Lockfile.Artifact> byKey = new LinkedHashMap<>();
        Map<String, List<Lockfile.Artifact>> byGa = new LinkedHashMap<>();
        for (Lockfile.Artifact row : lock.artifacts()) {
            byKey.putIfAbsent(row.packageKey(), row);
            byGa.computeIfAbsent(row.moduleGroup() + ":" + row.moduleArtifact(), k -> new ArrayList<>())
                    .add(row);
        }
        Deque<Lockfile.Artifact> queue = new ArrayDeque<>();
        for (Scope scope : CLASSPATH_SCOPES) {
            for (Dependency d : manifest.dependencies().of(scope)) {
                queue.addAll(byGa.getOrDefault(d.module(), List.of()));
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Lockfile.Artifact> out = new ArrayList<>();
        while (!queue.isEmpty()) {
            Lockfile.Artifact row = queue.poll();
            if (!seen.add(row.packageKey() + "@" + row.version())) continue;
            out.add(row);
            for (String edge : row.deps()) {
                Lockfile.Artifact next = byKey.get(edgeKey(edge));
                if (next != null) queue.add(next);
            }
        }
        return out;
    }

    /** The package key of a {@code deps} edge: {@code g:a:jar:@1.0} names {@code g:a:jar:}. */
    private static String edgeKey(String edge) {
        int at = edge.lastIndexOf('@');
        String key = at < 0 ? edge : edge.substring(0, at);
        return PackageId.isMavenPackageKey(key) ? PackageId.parse(key).key() : key;
    }

    /**
     * family → (artifact → version) for every family of {@code rows} on more than one line; the
     * families sort by name, the artifacts within one by name.
     */
    static Map<String, Map<String, String>> mixedFamilies(List<Lockfile.Artifact> rows) {
        Map<String, Map<String, String>> members = new TreeMap<>();
        for (Lockfile.Artifact row : rows) {
            String prefix = familyPrefix(row.moduleArtifact());
            if (prefix == null) continue;
            members.computeIfAbsent(row.moduleGroup() + ":" + prefix, k -> new TreeMap<>())
                    .putIfAbsent(row.moduleArtifact(), row.version());
        }
        Map<String, Map<String, String>> mixed = new TreeMap<>();
        for (Map.Entry<String, Map<String, String>> e : members.entrySet()) {
            if (e.getValue().size() > 1 && new TreeSet<>(e.getValue().values()).size() > 1) {
                mixed.put(e.getKey(), e.getValue());
            }
        }
        return mixed;
    }

    /**
     * {@code maven-resolver} for {@code maven-resolver-api} and {@code maven-resolver-transport-http};
     * null for a name with no hyphen.
     */
    static @Nullable String familyPrefix(String artifact) {
        int first = artifact.indexOf('-');
        if (first <= 0 || first == artifact.length() - 1) return null;
        int second = artifact.indexOf('-', first + 1);
        return second < 0 ? artifact : artifact.substring(0, second);
    }

    private static String warning(String path, String family, Map<String, String> versions) {
        StringBuilder b = new StringBuilder(path)
                .append(" has no [platform-dependencies] table and its rows mix ")
                .append(family)
                .append("-* lines: ");
        boolean first = true;
        for (Map.Entry<String, String> e : versions.entrySet()) {
            if (!first) b.append(", ");
            b.append(e.getKey()).append(' ').append(e.getValue());
            first = false;
        }
        return b.append(" — pin the family to one line in the member, or hold it under a platform BOM there")
                .toString();
    }
}
