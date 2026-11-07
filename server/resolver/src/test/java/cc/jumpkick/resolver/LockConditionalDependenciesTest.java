// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Extensions that declare conditional dependencies in a descriptor inside their jar, the way Quarkus
 * extensions do: the lock follows each one whose condition the graph meets, to a fixed point, and
 * records why the row is there.
 */
class LockConditionalDependenciesTest {

    private static final String DESCRIPTOR = "META-INF/quarkus-extension.properties";

    private static final ConditionalDependencies.Rule RULE =
            new ConditionalDependencies.Rule(DESCRIPTOR, "conditional-dependencies", "dependency-condition");

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void start() throws IOException {
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        // rest declares two conditional dependencies: rest-kotlin (condition: kotlin) and rest-scala
        // (condition: scala, which nothing brings).
        extension(
                "rest",
                "conditional-dependencies=com.acme\\:rest-kotlin\\:\\:jar\\:1.0 com.acme\\:rest-scala\\:\\:jar\\:1.0\n",
                List.of());
        // kotlin brings vertx through its POM and declares vertx-kotlin, conditional on vertx.
        extension("kotlin", "conditional-dependencies=com.acme\\:vertx-kotlin\\:\\:jar\\:1.0\n", List.of("vertx"));
        upstream.leaf("com.acme", "vertx", "1.0");
        // rest-kotlin brings helper through its POM, and declares chain on the condition helper, which
        // is only in the graph once rest-kotlin is: a second round.
        extension(
                "rest-kotlin",
                "dependency-condition=com.acme\\:kotlin\n"
                        + "conditional-dependencies=com.acme\\:chain\\:\\:jar\\:1.0\n",
                List.of("helper"));
        upstream.leaf("com.acme", "helper", "1.0");
        extension("chain", "dependency-condition=com.acme\\:helper\n", List.of());
        extension("vertx-kotlin", "dependency-condition=com.acme\\:vertx\n", List.of());
        extension("rest-scala", "dependency-condition=com.acme\\:scala\n", List.of());
    }

    @Test
    void a_conditional_dependency_whose_condition_holds_is_locked_to_a_fixed_point(@TempDir Path tempDir)
            throws Exception {
        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withConditionalDependencies(List.of(RULE))
                .lock(main(dep("rest"), dep("kotlin")), "test");

        assertThat(row(lock, "rest-kotlin")).hasValueSatisfying(r -> {
            assertThat(r.version()).isEqualTo("1.0");
            assertThat(r.scopes()).contains(Scope.MAIN);
            assertThat(r.addedBy()).containsExactly("com.acme:rest when com.acme:kotlin");
        });
        // What the joined dependency brings through its POM is solved with it.
        assertThat(row(lock, "helper")).isPresent();
        // Its own conditional dependency, whose condition only its POM met, joins on the next round.
        assertThat(row(lock, "chain").orElseThrow().addedBy())
                .containsExactly("com.acme:rest-kotlin when com.acme:helper");
        assertThat(row(lock, "vertx-kotlin").orElseThrow().addedBy())
                .containsExactly("com.acme:kotlin when com.acme:vertx");
        assertThat(row(lock, "rest-scala")).isEmpty();
        // The declaring row carries the edge, so a classpath walk from rest reaches it.
        assertThat(row(lock, "rest").orElseThrow().deps()).contains("com.acme:rest-kotlin:jar:@1.0");
        assertThat(row(lock, "kotlin").orElseThrow().deps()).contains("com.acme:vertx-kotlin:jar:@1.0");
        assertThat(row(lock, "rest").orElseThrow().addedBy()).isEmpty();

        String text = LockfileWriter.render(lock);
        assertThat(text.substring(text.indexOf("[[artifact]]")))
                .contains("added-by = [\n  \"com.acme:rest when com.acme:kotlin\",\n]");
    }

    @Test
    void no_condition_met_adds_nothing(@TempDir Path tempDir) throws Exception {
        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withConditionalDependencies(List.of(RULE))
                .lock(main(dep("rest")), "test");

        assertThat(row(lock, "rest-kotlin")).isEmpty();
        assertThat(row(lock, "rest-scala")).isEmpty();
        assertThat(row(lock, "rest").orElseThrow().deps()).isEmpty();
    }

    @Test
    void without_a_rule_the_descriptors_are_not_read(@TempDir Path tempDir) throws Exception {
        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(main(dep("rest"), dep("kotlin")), "test");

        assertThat(row(lock, "rest-kotlin")).isEmpty();
        assertThat(row(lock, "vertx-kotlin")).isEmpty();
    }

    @Test
    void a_joined_dependency_follows_the_platform_table(@TempDir Path tempDir) throws Exception {
        extension("rest-kotlin", "1.1", "dependency-condition=com.acme\\:kotlin\n", List.of());
        upstream.metadata("com.acme", "rest-kotlin", "1.0", "1.1");
        upstream.metadata("org.example", "the-bom", "1.0");
        upstream.pom(
                "org.example",
                "the-bom",
                "1.0",
                MavenStub.bom("org.example", "the-bom", "1.0", List.of("com.acme:rest-kotlin:1.1")));
        EnumMap<Scope, List<Dependency>> deps = new EnumMap<>(Scope.class);
        deps.put(Scope.MAIN, List.of(dep("rest"), dep("kotlin")));
        deps.put(
                Scope.PLATFORM,
                List.of(Dependency.of("the-bom", "org.example:the-bom", VersionSelector.parse("=1.0"))));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withConditionalDependencies(List.of(RULE))
                .lock(build(deps), "test");

        assertThat(row(lock, "rest-kotlin").orElseThrow().version()).isEqualTo("1.1");
    }

    @Test
    void a_test_extension_meets_its_condition_against_the_main_graph(@TempDir Path tempDir) throws Exception {
        extension("junit-ext", "conditional-dependencies=com.acme\\:junit-kotlin\\:\\:jar\\:1.0\n", List.of());
        extension("junit-kotlin", "dependency-condition=com.acme\\:kotlin\n", List.of());
        EnumMap<Scope, List<Dependency>> deps = new EnumMap<>(Scope.class);
        deps.put(Scope.MAIN, List.of(dep("kotlin")));
        deps.put(Scope.TEST, List.of(dep("junit-ext")));

        Lockfile lock = new LockOrchestrator(repoGroup(tempDir))
                .withConditionalDependencies(List.of(RULE))
                .lock(build(deps), "test");

        assertThat(row(lock, "junit-kotlin")).hasValueSatisfying(r -> {
            assertThat(r.scopes()).containsExactly(Scope.TEST);
            assertThat(r.addedBy()).containsExactly("com.acme:junit-ext when com.acme:kotlin");
        });
        assertThat(row(lock, "junit-ext").orElseThrow().deps()).contains("com.acme:junit-kotlin:jar:@1.0");
    }

    @Test
    void descriptor_coordinates_parse_as_quarkus_writes_them() {
        assertThat(ConditionalDependencies.dependency("io.quarkus:quarkus-rest-kotlin::jar:3.40.1"))
                .hasToString("io.quarkus:quarkus-rest-kotlin:3.40.1");
        assertThat(ConditionalDependencies.dependency("g:a:1.0")).hasToString("g:a:1.0");
        assertThat(ConditionalDependencies.dependency("g:a:natives:jar:1.0")).isNull();
        assertThat(ConditionalDependencies.dependency("g:a::pom:1.0")).isNull();
        assertThat(ConditionalDependencies.conditionKey("io.quarkus:quarkus-kotlin"))
                .isEqualTo("io.quarkus:quarkus-kotlin");
        assertThat(ConditionalDependencies.conditionKey("g:a:linux:jar")).isEqualTo("g:a:linux");
        assertThat(ConditionalDependencies.conditionKey("g")).isNull();
    }

    /** {@code com.acme:<artifact>:1.0}, its POM depending on {@code deps}, its jar holding the descriptor. */
    private void extension(String artifact, String properties, List<String> deps) throws IOException {
        extension(artifact, "1.0", properties, deps);
    }

    private void extension(String artifact, String version, String properties, List<String> deps) throws IOException {
        upstream.metadata("com.acme", artifact, version);
        StringBuilder pom = new StringBuilder("<project><groupId>com.acme</groupId><artifactId>")
                .append(artifact)
                .append("</artifactId><version>")
                .append(version)
                .append("</version><dependencies>");
        for (String d : deps) {
            pom.append("<dependency><groupId>com.acme</groupId><artifactId>")
                    .append(d)
                    .append("</artifactId><version>1.0</version></dependency>");
        }
        pom.append("</dependencies></project>");
        upstream.pom("com.acme", artifact, version, pom.toString());
        upstream.bytes(MavenStub.path("com.acme", artifact, version, ".jar"), jar(properties));
    }

    private static byte[] jar(String properties) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(DESCRIPTOR));
            zip.write(properties.getBytes(StandardCharsets.ISO_8859_1));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static Dependency dep(String artifact) {
        return new Dependency("com.acme:" + artifact, VersionSelector.parse("=1.0"));
    }

    private static JkBuild main(Dependency... deps) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, new ArrayList<>(List.of(deps)));
        return build(byScope);
    }

    private static JkBuild build(Map<Scope, List<Dependency>> byScope) {
        EnumMap<Scope, List<Dependency>> copy = new EnumMap<>(Scope.class);
        copy.putAll(byScope);
        return new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(copy));
    }

    private static Optional<Lockfile.Artifact> row(Lockfile lock, String artifact) {
        return lock.artifacts().stream()
                .filter(a -> a.packageKey().equals("com.acme:" + artifact + ":jar:"))
                .findFirst();
    }

    private RepoGroup repoGroup(Path tempDir) {
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(tempDir.resolve("cache"))));
    }
}
