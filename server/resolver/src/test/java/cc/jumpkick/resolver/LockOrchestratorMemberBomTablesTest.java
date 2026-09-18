// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PinPolicy;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.model.WorkspaceMerge;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A member's own platform table in a workspace lock: the BOMs a member holds fold for that member
 * alone, so members holding BOMs that disagree are each their own answer, and a member whose own
 * rows are all what its table says is noted as such.
 */
class LockOrchestratorMemberBomTablesTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void start() {
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
    }

    /**
     * Two members each hold one of two BOMs that disagree on a module both declare versionless; no
     * member holds both. Under {@code exact} the lock is not a refusal: the workspace's row takes the
     * say of the first member, in workspace order, whose own table manages the module, and the other
     * member reads a row of its own at its BOM's version — each member's table folds its own BOMs
     * alone, as the member pass folds them.
     */
    @Test
    void two_members_each_holding_one_of_two_disagreeing_boms_lock_under_exact_pins(@TempDir Path tempDir)
            throws Exception {
        serveLeaf();
        upstream.pom(
                "org.example",
                "quarkus-bom",
                "1.0",
                MavenStub.bom("org.example", "quarkus-bom", "1.0", List.of("com.foo:leaf:2.0")));
        upstream.pom(
                "org.example",
                "api-parent",
                "1.0",
                MavenStub.bom("org.example", "api-parent", "1.0", List.of("com.foo:leaf:1.0")));
        Dependency quarkusBom = Dependency.of("quarkus-bom", "org.example:quarkus-bom", VersionSelector.parse("=1.0"));
        Dependency apiParent = Dependency.of("api-parent", "org.example:api-parent", VersionSelector.parse("=1.0"));
        Dependency leaf = Dependency.platformManaged("leaf", "com.foo:leaf");
        JkBuild app = manifest("app", Map.of(Scope.PLATFORM, List.of(quarkusBom), Scope.MAIN, List.of(leaf)));
        JkBuild lib = manifest("lib", Map.of(Scope.PLATFORM, List.of(apiParent), Scope.MAIN, List.of(leaf)));

        Lockfile lock = lockWorkspace(tempDir, List.of(app, lib), new ArrayList<>());

        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::pinnedBy, Lockfile.Artifact::members)
                .containsExactlyInAnyOrder(
                        tuple("2.0", "org.example:quarkus-bom:1.0", List.of()),
                        tuple("1.0", "org.example:api-parent:1.0", List.of("lib")));
        assertThat(rows(lock.forMember("app"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("1.0");
    }

    /**
     * A member whose own rows are all pinned by a BOM or entry of its own table is the documented
     * shape — the rows say what the BOM says — so its note is one sentence naming the count and the
     * table, not a list of coordinates; a member whose own row nothing of its table pins keeps the
     * list, coordinate by coordinate.
     */
    @Test
    void a_members_rows_its_own_bom_manages_are_noted_by_the_bom_not_coordinate_by_coordinate(@TempDir Path tempDir)
            throws Exception {
        serveMiddleOverLeaf();
        Dependency bom = Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0"));
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild app = manifest("app", Map.of(Scope.PLATFORM, List.of(bom), Scope.MAIN, List.of(middle)));
        JkBuild lib = manifest("lib", Map.of(Scope.MAIN, List.of(middle)));
        List<String> notes = new ArrayList<>();

        Lockfile lock = lockWorkspace(tempDir, List.of(app, lib), notes);

        assertThat(rows(lock, "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version, Lockfile.Artifact::pinnedBy, Lockfile.Artifact::members)
                .containsExactlyInAnyOrder(
                        tuple("1.0", null, List.of()), tuple("2.0", "org.example:the-bom:1.0", List.of("app")));
        assertThat(notes)
                .filteredOn(n -> n.contains("reads its own rows"))
                .containsExactly("app reads its own rows for 1 coordinate org.example:the-bom:1.0 manages");
    }

    /** The workspace locked as the pipeline locks it under exact pins, recording the lock's notes into {@code notes}. */
    private Lockfile lockWorkspace(Path tempDir, List<JkBuild> modules, List<String> notes) throws Exception {
        List<String> names = modules.stream().map(m -> m.project().name()).toList();
        JkBuild root = JkBuild.builder(new Project("com.example", "root", "0.1.0", 25))
                .dependencies(new JkBuild.Dependencies(new EnumMap<>(Scope.class)))
                .workspace(new Workspace(names))
                .build();
        List<LockOrchestrator.Member> members = new ArrayList<>();
        for (JkBuild module : modules) {
            members.add(new LockOrchestrator.Member(
                    module.project().name(), WorkspaceMerge.applyToModule(root, module, modules)));
        }
        ResolveObserver recording = new ResolveObserver() {
            @Override
            public void onTotal(int total) {}

            @Override
            public void onPackage(String module, String version) {}

            @Override
            public void onNote(String line) {
                notes.add(line);
            }
        };
        return new LockOrchestrator(repoGroup(tempDir))
                .withPinPolicy(PinPolicy.EXACT)
                .withMembers(members)
                .lock(WorkspaceMerge.merge(root, modules), "test", List.of(), true, recording);
    }

    /** {@code the-bom} manages leaf at 2.0; {@code middle} declares leaf 1.0; both leaf releases exist. */
    private void serveMiddleOverLeaf() {
        upstream.pom(
                "org.example",
                "the-bom",
                "1.0",
                MavenStub.bom("org.example", "the-bom", "1.0", List.of("com.foo:leaf:2.0")));
        upstream.metadata("com.foo", "middle", "1.0");
        upstream.pom("com.foo", "middle", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>middle</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.jar("com.foo", "middle", "1.0");
        serveLeaf();
    }

    /** Both leaf releases, 1.0 and 2.0. */
    private void serveLeaf() {
        upstream.metadata("com.foo", "leaf", "1.0", "2.0");
        for (String v : List.of("1.0", "2.0")) {
            upstream.pom(
                    "com.foo",
                    "leaf",
                    v,
                    "<project><groupId>com.foo</groupId><artifactId>leaf</artifactId><version>" + v
                            + "</version></project>");
            upstream.jar("com.foo", "leaf", v);
        }
    }

    private static List<Lockfile.Artifact> rows(Lockfile lock, String packageKey) {
        List<Lockfile.Artifact> out = new ArrayList<>();
        for (Lockfile.Artifact row : lock.artifacts()) if (row.packageKey().equals(packageKey)) out.add(row);
        return out;
    }

    private static JkBuild manifest(String name, Map<Scope, List<Dependency>> byScope) {
        EnumMap<Scope, List<Dependency>> copy = new EnumMap<>(Scope.class);
        copy.putAll(byScope);
        return new JkBuild(new Project("com.example", name, "0.1.0", 25), new JkBuild.Dependencies(copy));
    }

    private RepoGroup repoGroup(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), cas));
    }
}
