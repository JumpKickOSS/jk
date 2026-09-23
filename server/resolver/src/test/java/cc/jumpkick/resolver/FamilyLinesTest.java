// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A workspace member that holds no platform table of its own reads the workspace's plain rows, and
 * two of one library family's artifacts on different lines there — the shape a member's own BOM
 * would have aligned — are named at lock time, member by member, before a runtime failure names
 * them. A member under its own platform table, a family on one line, and artifacts outside the
 * member's closure say nothing.
 */
class FamilyLinesTest {

    private static final String RESOLVER = "org.apache.maven.resolver";

    @Test
    void a_member_without_a_platform_table_is_warned_when_its_rows_mix_a_familys_lines() {
        Lockfile lock = lock(
                row(RESOLVER + ":maven-resolver-api", "2.0.22"),
                row(RESOLVER + ":maven-resolver-impl", "2.0.22", RESOLVER + ":maven-resolver-api:jar:@2.0.22"),
                row(
                        RESOLVER + ":maven-resolver-transport-http",
                        "1.9.27",
                        RESOLVER + ":maven-resolver-api:jar:@2.0.22"));
        JkBuild member = manifest(Map.of(
                Scope.MAIN,
                List.of(dep(RESOLVER + ":maven-resolver-impl"), dep(RESOLVER + ":maven-resolver-transport-http"))));

        List<String> warnings =
                FamilyLines.warnings(lock, List.of(new LockOrchestrator.Member("plugins/tool", member)));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .startsWith("plugins/tool")
                .contains("no [platform-dependencies] table")
                .contains(RESOLVER + ":maven-resolver-*")
                .contains("maven-resolver-api 2.0.22")
                .contains("maven-resolver-impl 2.0.22")
                .contains("maven-resolver-transport-http 1.9.27")
                .contains("one line");
    }

    @Test
    void a_member_under_its_own_platform_table_is_not_warned() {
        Lockfile lock = lock(
                row(RESOLVER + ":maven-resolver-api", "2.0.22"),
                row(RESOLVER + ":maven-resolver-transport-http", "1.9.27"));
        JkBuild member = manifest(Map.of(
                Scope.PLATFORM,
                List.of(dep("io.quarkus:quarkus-bootstrap-bom")),
                Scope.MAIN,
                List.of(dep(RESOLVER + ":maven-resolver-api"), dep(RESOLVER + ":maven-resolver-transport-http"))));

        assertThat(FamilyLines.warnings(lock, List.of(new LockOrchestrator.Member("plugins/tool", member))))
                .isEmpty();
    }

    @Test
    void a_family_on_one_line_and_rows_outside_the_members_closure_say_nothing() {
        Lockfile lock = lock(
                row(RESOLVER + ":maven-resolver-api", "2.0.22"),
                row(RESOLVER + ":maven-resolver-impl", "2.0.22", RESOLVER + ":maven-resolver-api:jar:@2.0.22"),
                // Another member's row: the same family on another line, not on this member's classpath.
                row(RESOLVER + ":maven-resolver-transport-http", "1.9.27"));
        JkBuild member = manifest(Map.of(Scope.MAIN, List.of(dep(RESOLVER + ":maven-resolver-impl"))));

        assertThat(FamilyLines.warnings(lock, List.of(new LockOrchestrator.Member("lib", member))))
                .isEmpty();
    }

    @Test
    void a_one_segment_prefix_is_not_a_family() {
        // commons-io and commons-lang3 share `commons-` and nothing else; their lines are independent.
        Lockfile lock =
                lock(row("org.apache.commons:commons-io", "2.20.0"), row("org.apache.commons:commons-lang3", "3.18.0"));
        JkBuild member = manifest(Map.of(
                Scope.MAIN, List.of(dep("org.apache.commons:commons-io"), dep("org.apache.commons:commons-lang3"))));

        assertThat(FamilyLines.warnings(lock, List.of(new LockOrchestrator.Member("lib", member))))
                .isEmpty();
    }

    @Test
    void the_members_own_partition_rows_are_the_rows_judged() {
        Lockfile.Artifact plain = row(RESOLVER + ":maven-resolver-transport-http", "1.9.27");
        Lockfile.Artifact mine =
                row(RESOLVER + ":maven-resolver-transport-http", "2.0.22").withMembers(List.of("plugins/tool"));
        Lockfile lock = lock(row(RESOLVER + ":maven-resolver-api", "2.0.22"), plain, mine);
        JkBuild member = manifest(Map.of(
                Scope.MAIN,
                List.of(dep(RESOLVER + ":maven-resolver-api"), dep(RESOLVER + ":maven-resolver-transport-http"))));

        assertThat(FamilyLines.warnings(lock, List.of(new LockOrchestrator.Member("plugins/tool", member))))
                .isEmpty();
    }

    private static Lockfile lock(Lockfile.Artifact... rows) {
        return Lockfile.empty("test").withArtifacts(List.of(rows));
    }

    private static Lockfile.Artifact row(String ga, String version, String... deps) {
        return new Lockfile.Artifact(
                ga + ":jar:",
                version,
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:0000",
                null,
                List.of(Scope.MAIN),
                List.of(deps));
    }

    private static Dependency dep(String module) {
        return new Dependency(module, VersionSelector.parse("latest"));
    }

    private static JkBuild manifest(Map<Scope, List<Dependency>> byScope) {
        EnumMap<Scope, List<Dependency>> copy = new EnumMap<>(Scope.class);
        copy.putAll(byScope);
        return new JkBuild(new Project("com.example", "tool", "0.1.0", 25), new JkBuild.Dependencies(copy));
    }
}
