// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.pubgrub.Term;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Diagnostic walk of the quarkus-junit5 graph (network). Prefer {@link QuarkusJunit5ResolveTest}. */
@Tag("integration")
class QuarkusJunit5WalkTest {
    @Test
    void walk_with_highest(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(Path.of(System.getProperty("user.home"), ".jk/cache"));
        RepoGroup repos =
                RepoGroup.of(new MavenRepo("central", URI.create("https://repo1.maven.org/maven2/"), new Http(), cas));
        EffectivePomBuilder b = new EffectivePomBuilder(repos);
        Map<String, String> bom = new LinkedHashMap<>();
        for (var m : b.build(Coordinate.of("io.quarkus.platform", "quarkus-bom", "3.38.0"))
                .managedDependencies()) {
            if (m.version() != null && !m.version().isBlank()) bom.putIfAbsent(m.module(), m.version());
        }
        MavenPackageSource src = new MavenPackageSource(repos, b, bom);
        Map<String, String> chosen = new HashMap<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        String root = PackageId.ofGa("io.quarkus:quarkus-junit5").key();
        chosen.put(root, "3.38.0");
        q.add(root);
        Set<String> seen = new HashSet<>();
        int opens = 0;
        while (!q.isEmpty() && chosen.size() < 800) {
            String pkg = q.removeFirst();
            String ver = chosen.get(pkg);
            if (!seen.add(pkg + "@" + ver)) continue;
            List<Term> deps;
            try {
                deps = src.dependencies(pkg, ver);
            } catch (Exception e) {
                System.out.println("UNAVAIL " + pkg + "@" + ver + " " + e.getMessage());
                continue;
            }
            for (Term t : deps) {
                var ex = t.versions().asExactSingleton();
                String v;
                if (ex.isPresent()) {
                    v = ex.get();
                } else {
                    opens++;
                    List<String> cands = src.versions(t.pkg());
                    v = null;
                    for (String c : cands) {
                        if (t.versions().contains(c)) {
                            v = c;
                            break; // highest-first
                        }
                    }
                    if (v == null) {
                        System.out.println("NO_CAND " + t.pkg() + " " + t.versions() + " cands=" + cands);
                        continue;
                    }
                }
                String prev = chosen.putIfAbsent(t.pkg(), v);
                if (prev == null) q.add(t.pkg());
                else if (!prev.equals(v)) {
                    // keep higher
                    int cmp = Versions.compare(v, prev);
                    if (cmp > 0) {
                        System.out.println("UPGRADE " + t.pkg() + " " + prev + " -> " + v);
                        chosen.put(t.pkg(), v);
                        q.add(t.pkg());
                    } else if (cmp < 0) {
                        // need prev, ignore lower demand if still satisfies? floor issue
                        System.out.println("KEEP " + t.pkg() + " have " + prev + " also need " + v + " from " + pkg);
                    }
                }
            }
        }
        System.out.println("packages=" + chosen.size() + " opensSeen=" + opens);
    }
}
