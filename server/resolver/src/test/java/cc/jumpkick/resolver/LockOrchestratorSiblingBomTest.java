// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
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
 * A sibling's BOM a member's lock folds in: one the sibling names through the root's {@code
 * [workspace.dependencies]} constrains the members that depend on the sibling, as it constrains
 * the sibling.
 */
class LockOrchestratorSiblingBomTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void start() {
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
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

    /**
     * lib holds a BOM it names through the root's {@code [workspace.dependencies]}; app depends on
     * lib, so the BOM's version of leaf is app's too, as it is lib's.
     */
    @Test
    void a_siblings_bom_named_through_workspace_dependencies_constrains_its_consumer(@TempDir Path tempDir)
            throws Exception {
        upstream.pom(
                "org.example",
                "the-bom",
                "1.0",
                MavenStub.bom("org.example", "the-bom", "1.0", List.of("com.foo:leaf:2.0")));
        Dependency middle = new Dependency("com.foo:middle", VersionSelector.parse("=1.0"));
        JkBuild lib = manifest(
                "lib", Map.of(Scope.PLATFORM, List.of(Dependency.workspace("the-bom")), Scope.MAIN, List.of(middle)));
        JkBuild app = manifest("app", Map.of(Scope.MAIN, List.of(Dependency.workspace("lib"), middle)));
        JkBuild other = manifest("other", Map.of(Scope.MAIN, List.of(middle)));
        JkBuild root = root(
                List.of("lib", "app", "other"),
                Map.of(
                        "the-bom",
                        new Workspace.WorkspaceDependency(
                                "org.example", "the-bom", VersionSelector.parse("=1.0"), null)));

        Lockfile lock = lockWorkspace(tempDir, root, List.of(lib, app, other));

        assertThat(rows(lock.forMember("lib"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
        assertThat(rows(lock.forMember("app"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0");
        assertThat(rows(lock.forMember("other"), "com.foo:leaf:jar:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("1.0");
    }

    /** The merged manifest of {@code root} and {@code modules} with every member behind it, as the pipeline locks it. */
    private Lockfile lockWorkspace(Path tempDir, JkBuild root, List<JkBuild> modules) throws Exception {
        List<LockOrchestrator.Member> members = new ArrayList<>();
        for (JkBuild module : modules) {
            WorkspaceMerge.Applied applied = WorkspaceMerge.applyToModuleCarrying(root, module, modules);
            members.add(new LockOrchestrator.Member(module.project().name(), applied.manifest(), applied.carried()));
        }
        return new LockOrchestrator(repoGroup(tempDir))
                .withMembers(members)
                .lock(WorkspaceMerge.merge(root, modules), "test", List.of(), true, ResolveObserver.NOOP);
    }

    private static JkBuild root(List<String> names, Map<String, Workspace.WorkspaceDependency> shared) {
        return JkBuild.builder(new Project("com.example", "root", "0.1.0", 25))
                .dependencies(new JkBuild.Dependencies(new EnumMap<>(Scope.class)))
                .workspace(new Workspace(names, shared))
                .build();
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
