// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace whose members each need a solve of their own locks to the same bytes whether those
 * solves run one at a time or overlap, and the pass labels them in the same order either way.
 */
class LockOrchestratorParallelMembersTest {

    private static final List<String> VERSIONS = List.of("1.0", "2.0", "3.0", "4.0", "5.0");

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @Test
    void overlapping_member_solves_write_the_lock_one_at_a_time_writes(@TempDir Path tempDir) throws Exception {
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        upstream.metadata("com.foo", "widget", VERSIONS.toArray(String[]::new));
        upstream.metadata("com.foo", "gadget", VERSIONS.toArray(String[]::new));
        for (String v : VERSIONS) {
            for (String artifact : List.of("widget", "gadget")) {
                upstream.pom("com.foo", artifact, v, pom(artifact, v));
                upstream.jar("com.foo", artifact, v);
            }
        }
        // Every member but the first pins its own versions, so each needs a solve of its own; two
        // members pin the same pair and share their rows.
        List<LockOrchestrator.Member> members = new ArrayList<>();
        members.add(member("m0", "1.0", "1.0"));
        members.add(member("m1", "2.0", "3.0"));
        members.add(member("m2", "3.0", "2.0"));
        members.add(member("m3", "4.0", "5.0"));
        members.add(member("m4", "5.0", "4.0"));
        members.add(member("m5", "2.0", "3.0"));
        members.add(member("m6", "4.0", "1.0"));
        JkBuild merged = manifest("root", "1.0", "1.0");

        Run serial = lock(tempDir.resolve("serial"), members, merged, new MemberSolves(1, Long.MAX_VALUE));
        MemberSolves overlapping = new MemberSolves(4, Long.MAX_VALUE);
        Run parallel = lock(tempDir.resolve("parallel"), members, merged, overlapping);

        assertThat(parallel.toml()).isEqualTo(serial.toml());
        assertThat(parallel.phases()).isEqualTo(serial.phases());
        assertThat(serial.phases())
                .filteredOn(p -> p.startsWith("Solving members"))
                .hasSize(6);
        assertThat(rows(parallel.lock(), "com.foo:widget:jar:"))
                .filteredOn(Lockfile.Artifact::isPartition)
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::members)
                .contains(tuple("2.0", List.of("m1", "m5")));
        assertThat(overlapping.peak()).isGreaterThan(1);
    }

    private record Run(Lockfile lock, String toml, List<String> phases) {}

    private Run lock(Path dir, List<LockOrchestrator.Member> members, JkBuild merged, MemberSolves solves)
            throws Exception {
        List<String> phases = new ArrayList<>();
        ResolveObserver observer = new ResolveObserver() {
            @Override
            public void onTotal(int total) {}

            @Override
            public void onPackage(String module, String version) {}

            @Override
            public void onPhase(String label) {
                synchronized (phases) {
                    phases.add(label);
                }
            }
        };
        Lockfile lock = new LockOrchestrator(repoGroup(dir))
                .withMembers(members)
                .withMemberSolves(solves)
                .lock(merged, "test", List.of(), true, observer);
        return new Run(lock, LockfileWriter.render(lock), phases);
    }

    private static LockOrchestrator.Member member(String name, String widget, String gadget) {
        return new LockOrchestrator.Member(name, manifest(name, widget, gadget));
    }

    private static JkBuild manifest(String name, String widget, String gadget) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.MAIN,
                List.of(
                        new Dependency("com.foo:widget", VersionSelector.parse("=" + widget)),
                        new Dependency("com.foo:gadget", VersionSelector.parse("=" + gadget))));
        return new JkBuild(new Project("com.example", name, "0.1.0", 25), new JkBuild.Dependencies(byScope));
    }

    private static String pom(String artifact, String version) {
        return "<project><groupId>com.foo</groupId><artifactId>" + artifact + "</artifactId><version>" + version
                + "</version></project>";
    }

    private static List<Lockfile.Artifact> rows(Lockfile lock, String packageKey) {
        List<Lockfile.Artifact> out = new ArrayList<>();
        for (Lockfile.Artifact row : lock.artifacts()) if (row.packageKey().equals(packageKey)) out.add(row);
        return out;
    }

    private RepoGroup repoGroup(Path dir) {
        Cas cas = new Cas(dir.resolve("cache"));
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), cas));
    }
}
